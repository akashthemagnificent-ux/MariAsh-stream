package com.agon.app.segmenter

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil

/**
 * HlsSegmenter — splits any local video file into 4-second HLS segments using
 * Android's built-in MediaExtractor + MediaMuxer (MPEG-TS output).
 *
 * No re-encoding happens. Video and audio streams are copied byte-for-byte
 * (just the container changes from .mp4/.mkv to .ts). This means:
 * — Zero quality loss (original bitrate, original FPS, original codec)
 * — Fast: no CPU-intensive work
 * — No external native libraries required (pure Android SDK)
 *
 * Each segment rolls on the next sync (keyframe) sample after 4 seconds.
 * As each segment finishes writing, [onSegmentReady] fires so the caller
 * can start uploading immediately without waiting for the full file to segment.
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

    companion object {
        private const val TAG = "HlsSegmenter"
        private const val SEGMENT_DURATION_US = 4_000_000L
    }

    fun segment(uri: Uri, outputDir: File) {
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        running = true
        workerThread = Thread {
            try {
                doSegment(uri, outputDir)
            } catch (e: Exception) {
                Log.e(TAG, "Segmenter error: ${e.message}", e)
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
            onError("No video or audio tracks found in source file")
            return
        }

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
                    onSegmentReady(file.name, file)
                    onProgress(completedFiles.size)
                    onPlaylistReady(buildPlaylist(completedFiles, completedDurations, isComplete = false))
                    Log.d(TAG, "Segment ${file.name} ready (${file.length() / 1024}KB, ${"%.2f".format(dur)}s)")
                }
            }
        }

        fun startNewSegment(startUs: Long) {
            val name = "seg_%05d.ts".format(segmentIndex++)
            val file = File(outputDir, name)
            segmentFile = file
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_TS)
            for (track in tracks) {
                val muxerIdx = muxer!!.addTrack(track.format)
                trackIndexMap[track.extractorIndex] = muxerIdx
            }
            muxer!!.start()
            segmentStartUs = startUs
            Log.d(TAG, "Started segment $name at ${"%.2f".format(startUs / 1_000_000.0)}s")
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
            File(outputDir, "playlist.m3u8").writeText(finalPlaylist)
            onPlaylistReady(finalPlaylist)
            Log.d(TAG, "Segmenting complete: ${completedFiles.size} segments")
            if (running) onComplete()
        } else {
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
        running = false
        workerThread?.interrupt()
    }
}
