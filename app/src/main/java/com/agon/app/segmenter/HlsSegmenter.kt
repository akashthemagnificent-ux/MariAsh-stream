package com.agon.app.segmenter

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.agon.app.debug.AppLogger
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil

/**
 * HlsSegmenter — splits any local video file into 4-second HLS segments using
 * Android's built-in MediaExtractor + MediaMuxer (MP4 segment output).
 *
 * No re-encoding happens. Video and audio streams are copied byte-for-byte
 * (just the container is segmented into individual .mp4 files). This means:
 * — Zero quality loss (original bitrate, original FPS, original codec)
 * — Fast: no CPU-intensive work
 * — No external native libraries required (pure Android SDK)
 *
 * Each segment rolls on the next sync (keyframe) sample after 4 seconds.
 * As each segment finishes writing, [onSegmentReady] fires so the caller
 * can start uploading immediately without waiting for the full file to segment.
 *
 * Look-ahead throttle:
 * If [pauseCheck] is set, the segmenter calls it after each segment. If it
 * returns true the worker thread sleeps (checking every 200 ms) until either
 * [pauseCheck] returns false or [stop] is called. This prevents the segmenter
 * from running the entire movie into memory / disk at once.
 */
class HlsSegmenter(
    private val context: Context,
    private val onSegmentReady: (name: String, file: File) -> Unit,
    private val onPlaylistReady: (content: String) -> Unit,
    private val onProgress: (segmentsCompleted: Int) -> Unit,
    private val onError: (errorLog: String) -> Unit,
    private val onComplete: () -> Unit
) {
    @Volatile private var running = false
    private var workerThread: Thread? = null

    /**
     * Optional look-ahead gate. The segmenter worker thread calls this after
     * each segment is handed off to [onSegmentReady]. While it returns true the
     * worker sleeps in 200 ms increments. Set from the caller's thread at any
     * time — reads are done under a volatile read so no lock is needed.
     */
    @Volatile var pauseCheck: (() -> Boolean)? = null

    companion object {
        private const val TAG = "HlsSegmenter"
        private const val SEGMENT_DURATION_US = 4_000_000L
        // MediaMuxer.OutputFormat.MUXER_OUTPUT_MP4 = 0 (stable since API 18).
        // Do NOT reference MediaMuxer.OutputFormat.MUXER_OUTPUT_MP4 directly:
        // in Android SDK compileSdk ≥ 29 the OutputFormat @interface is declared
        // with @Retention(RetentionPolicy.SOURCE), so its members are stripped from
        // the class stubs. The Kotlin 2.0 K2 compiler cannot resolve them at compile
        // time, producing "Unresolved reference 'MUXER_OUTPUT_MP4'". Using the raw
        // integer value here is the only reliable fix.
        private const val MUXER_OUTPUT_MP4 = 0
    }

    fun segment(uri: Uri, outputDir: File) {
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        running = true
        AppLogger.i(TAG, "segment() start — uri=$uri outputDir=$outputDir")
        workerThread = Thread {
            try {
                doSegment(uri, outputDir)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Segmenter crashed: ${e.message}")
                running = false
                onError(e.message ?: "Unknown segmenter error")
            }
        }.also {
            it.isDaemon = true
            it.name = "HlsSegmenter"
            it.start()
        }
    }

    private fun doSegment(uri: Uri, outputDir: File) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        data class TrackInfo(val extractorIndex: Int, val format: MediaFormat)

        val tracks = mutableListOf<TrackInfo>()
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                extractor.selectTrack(i)
                tracks.add(TrackInfo(i, fmt))
            }
        }

        if (tracks.isEmpty()) {
            extractor.release()
            running = false
            AppLogger.e(TAG, "No video or audio tracks found in source file")
            onError("No video or audio tracks found in source file")
            return
        }

        AppLogger.i(TAG, "Found ${tracks.size} tracks in source")

        val readBuffer = ByteBuffer.allocate(4 * 1024 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()

        var segmentIndex = 0
        var muxer: MediaMuxer? = null
        val trackIndexMap = mutableMapOf<Int, Int>()
        var segmentFile: File? = null
        val completedFiles = mutableListOf<File>()
        val completedDurations = mutableListOf<Double>()
        var segmentStartUs = 0L
        var lastSampleUs = 0L

        fun finalizeCurrentSegment() {
            muxer?.stop()
            muxer?.release()
            muxer = null
            trackIndexMap.clear()
            segmentFile?.let { file ->
                if (file.exists() && file.length() > 0) {
                    val dur = (lastSampleUs - segmentStartUs).coerceAtLeast(0L) / 1_000_000.0
                    completedFiles.add(file)
                    completedDurations.add(dur)

                    // Notify caller — caller is responsible for reading the bytes.
                    // Bug fix: delete the file immediately after the caller has read
                    // it so segments don't accumulate on disk (each 4-second segment
                    // at typical movie bitrates is 0.5–3 MB; a full movie would
                    // otherwise fill hundreds of MB of app cache).
                    onSegmentReady(file.name, file)
                    file.delete()

                    onProgress(completedFiles.size)
                    onPlaylistReady(buildPlaylist(completedFiles, completedDurations, isComplete = false))
                    AppLogger.d(TAG, "Segment ${file.name} ready (${"%.2f".format(dur)}s, total=${completedFiles.size})")

                    // Look-ahead throttle: pause the worker thread if the caller
                    // signals we are too far ahead of the consumer (e.g. the host's
                    // current playback position). This prevents the entire movie from
                    // being decoded into memory at once.
                    val check = pauseCheck
                    if (check != null) {
                        var waited = 0
                        while (running && check()) {
                            try { Thread.sleep(200) } catch (_: InterruptedException) { running = false }
                            waited += 200
                            if (waited % 2000 == 0) {
                                AppLogger.d(TAG, "Segmenter paused — look-ahead window full, waited ${waited}ms")
                            }
                        }
                    }
                }
            }
        }

        fun startNewSegment(startUs: Long) {
            val name = "seg_%05d.mp4".format(segmentIndex++)
            val file = File(outputDir, name)
            segmentFile = file
            muxer = MediaMuxer(file.absolutePath, MUXER_OUTPUT_MP4)
            for (track in tracks) {
                val muxerIdx = muxer!!.addTrack(track.format)
                trackIndexMap[track.extractorIndex] = muxerIdx
            }
            muxer!!.start()
            segmentStartUs = startUs
            AppLogger.d(TAG, "Started segment $name at ${"%.2f".format(startUs / 1_000_000.0)}s")
        }

        startNewSegment(0L)

        while (running) {
            readBuffer.clear()
            val sampleSize = extractor.readSampleData(readBuffer, 0)
            if (sampleSize < 0) break

            val sampleTimeUs = extractor.sampleTime
            val trackIndex = extractor.sampleTrackIndex
            val sampleFlags = extractor.sampleFlags
            lastSampleUs = sampleTimeUs

            val isSync = (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
            if (isSync && sampleTimeUs - segmentStartUs >= SEGMENT_DURATION_US) {
                finalizeCurrentSegment()
                startNewSegment(sampleTimeUs)
            }

            bufferInfo.set(0, sampleSize, sampleTimeUs, sampleFlags)

            trackIndexMap[trackIndex]?.let { muxerTrack ->
                muxer?.writeSampleData(muxerTrack, readBuffer, bufferInfo)
            }

            extractor.advance()
        }

        finalizeCurrentSegment()
        extractor.release()

        if (completedFiles.isNotEmpty()) {
            val finalPlaylist = buildPlaylist(completedFiles, completedDurations, isComplete = true)
            onPlaylistReady(finalPlaylist)
            AppLogger.i(TAG, "Segmenting complete: ${completedFiles.size} segments")
            if (running) onComplete()
        } else {
            AppLogger.e(TAG, "No segments produced — source file may be empty or unsupported")
            onError("No segments produced — source file may be empty or unsupported")
        }

        running = false
    }

    private fun buildPlaylist(
        files: List<File>,
        durations: List<Double>,
        isComplete: Boolean
    ): String {
        val targetDuration = durations.maxOrNull()
            ?.let { ceil(it).toInt() }
            ?.coerceAtLeast(1) ?: 4
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            // EVENT type = play from segment 0, never seek to live edge.
            // Without this ExoPlayer treats the playlist as a live stream and
            // jumps to the last segment, causing black screen + stall.
            appendLine("#EXT-X-PLAYLIST-TYPE:EVENT")
            // Force ExoPlayer to start at the beginning of the event
            appendLine("#EXT-X-START:TIME=0")
            appendLine("#EXT-X-TARGETDURATION:$targetDuration")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            files.forEachIndexed { i, file ->
                appendLine("#EXTINF:${"%.6f".format(durations[i])},")
                appendLine(file.name)
            }
            if (isComplete) appendLine("#EXT-X-ENDLIST")
        }
    }

    fun stop() {
        AppLogger.i(TAG, "stop() called")
        running = false
        workerThread?.interrupt()
    }
}
