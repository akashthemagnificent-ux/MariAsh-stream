package com.agon.app.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory log buffer that Compose can observe via [logs] StateFlow.
 *
 * Every call also forwards to android.util.Log so logcat still works normally.
 * Holds at most MAX_ENTRIES entries; oldest entries are dropped when full.
 * Thread-safe — log() is @Synchronized.
 */
object AppLogger {

    const val MAX_ENTRIES = 1500

    data class LogEntry(
        val id: Long,
        val sessionId: String,
        val timeMs: Long,
        val timestamp: String,  // "HH:mm:ss.SSS"
        val level: String,      // "D" "I" "W" "E"
        val tag: String,
        val message: String
    )

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    private val counter = AtomicLong(0)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // One session ID per process lifetime — used as a visual separator in the
    // log screen so you can tell different test sessions apart in one buffer.
    val sessionId: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    @Synchronized
    fun log(level: String, tag: String, message: String) {
        val priority = when (level) {
            "E" -> Log.ERROR
            "W" -> Log.WARN
            "I" -> Log.INFO
            else -> Log.DEBUG
        }
        Log.println(priority, tag, message)

        val entry = LogEntry(
            id        = counter.incrementAndGet(),
            sessionId = sessionId,
            timeMs    = System.currentTimeMillis(),
            timestamp = timeFmt.format(Date()),
            level     = level,
            tag       = tag,
            message   = message
        )
        val current = _logs.value
        _logs.value = if (current.size >= MAX_ENTRIES) current.drop(1) + entry else current + entry
    }

    fun d(tag: String, msg: String) = log("D", tag, msg)
    fun i(tag: String, msg: String) = log("I", tag, msg)
    fun w(tag: String, msg: String) = log("W", tag, msg)
    fun e(tag: String, msg: String) = log("E", tag, msg)

    fun clear() {
        _logs.value = emptyList()
    }

    /** Full plain-text dump suitable for copying to clipboard or storing. */
    fun export(): String = buildString {
        appendLine("=== MariAsh Stream Log — Session: $sessionId ===")
        _logs.value.forEach { appendLine("${it.timestamp} ${it.level}/${it.tag}: ${it.message}") }
    }
}
