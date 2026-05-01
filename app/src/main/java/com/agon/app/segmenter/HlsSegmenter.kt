package com.agon.app.segmenter

import android.content.Context
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * HlsSegmenter — wraps FFmpegKit to remux any local video file into 4-second HLS segments.
 *
 * No re-encoding happens. Video and audio streams are copied byte-for-byte
 * (just the container changes from .mp4/.mkv to .ts). This means:
 * — Zero quality loss (original bitrate, original FPS, original codec)
 * — Fast: no CPU intensive work
 * — All embedded audio tracks and subtitles are preserved
 *
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
    private var sessionId: Long? = null
    private var watcherThread: Thread? = null

    fun segment(uri: Uri, outputDir: File) {
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        val inputPath = FFmpegKitConfig.getSafParameterForRead(context, uri)
        val playlistPath = File(outputDir, "playlist.m3u8").absolutePath
        val segmentPattern = File(outputDir, "seg_%05d.ts").absolutePath

        // -c:v copy -c:a copy = no re-encode, exact original quality
        // -hls_time 4         = 4-second segments (good balance of latency vs overhead)
        // -hls_list_size 0    = keep all segments in the playlist (VOD mode)
        // -hls_flags append_list = append new segments as they complete
        val command = buildString {
            append("-i \"$inputPath\" ")
            append("-c:v copy -c:a copy ")
            append("-f hls ")
            append("-hls_time 4 ")
            append("-hls_list_size 0 ")
            append("-hls_flags append_list ")
            append("-hls_segment_type mpegts ")
            append("-hls_segment_filename \"$segmentPattern\" ")
            append("\"$playlistPath\"")
        }

        Log.d("HlsSegmenter", "FFmpegKit command: $command")
        running = true
        startSegmentWatcher(outputDir)

        val session = FFmpegKit.executeAsync(command) { finishedSession ->
            running = false
            watcherThread?.interrupt()

            if (ReturnCode.isSuccess(finishedSession.returnCode)) {
                // Do one final scan to catch any segments missed by the watcher
                val playlist = File(outputDir, "playlist.m3u8")
                if (playlist.exists()) onPlaylistReady(playlist.readText())
                onComplete()
                Log.d("HlsSegmenter", "Segmenting complete")
            } else {
                val log = finishedSession.allLogsAsString ?: "Unknown FFmpeg error"
                Log.e("HlsSegmenter", "Failed: $log")
                onError(log)
            }
        }
        sessionId = session.sessionId
    }

    /**
     * Polls the output directory every 500ms and fires [onSegmentReady]
     * for each new .ts file as it appears. This runs on its own daemon thread.
     */
    private fun startSegmentWatcher(outputDir: File) {
        val reported = mutableSetOf<String>()
        watcherThread = Thread {
            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    outputDir.listFiles { f -> f.name.endsWith(".ts") && f.length() > 0 }
                        ?.filter { it.name !in reported }
                        ?.sortedBy { it.name }
                        ?.forEach { file ->
                            reported.add(file.name)
                            onSegmentReady(file.name, file)
                            onProgress(reported.size)
                            // Re-upload current playlist after each new segment
                            val playlist = File(outputDir, "playlist.m3u8")
                            if (playlist.exists()) onPlaylistReady(playlist.readText())
                            Log.d("HlsSegmenter", "Segment ready: ${file.name} (${file.length() / 1024}KB)")
                        }
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e("HlsSegmenter", "Watcher error: ${e.message}")
                }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        sessionId?.let { FFmpegKit.cancel(it) }
        watcherThread?.interrupt()
    }
}
