package com.agon.app.debug

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uncaught-exception handler that:
 *  1. Formats the full stack trace
 *  2. Appends the current AppLogger buffer (log snapshot at time of crash)
 *  3. Saves everything synchronously to SharedPreferences BEFORE the process dies
 *  4. Hands control to the original default handler (so Android still shows
 *     the system crash dialog / restarts the app normally)
 *
 * Install via Thread.setDefaultUncaughtExceptionHandler() in Application.onCreate().
 */
class CrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val PREFS     = "mariash_crash"
        private const val KEY_CRASH = "last_crash"
        private const val KEY_TIME  = "crash_time"

        /** Returns (crashReport, timeString) or (null, null) if no crash recorded. */
        fun getLastCrash(context: Context): Pair<String?, String?> {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Pair(p.getString(KEY_CRASH, null), p.getString(KEY_TIME, null))
        }

        fun clearCrash(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        val report = buildString {
            appendLine("=== CRASH REPORT ===")
            appendLine("Time   : $timeStr")
            appendLine("Thread : ${thread.name} (id=${thread.id})")
            appendLine("Error  : ${throwable.javaClass.name}: ${throwable.message}")
            appendLine()
            appendLine("--- Stack Trace ---")
            append(sw.toString())
            appendLine()
            appendLine("--- App Log at Time of Crash ---")
            append(AppLogger.export())
        }

        // MUST use commit() (synchronous) not apply() (async) here —
        // the process is about to die immediately after this call.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CRASH, report)
            .putString(KEY_TIME, timeStr)
            .commit()

        AppLogger.e("CrashHandler", "CRASH saved: ${throwable.javaClass.simpleName}: ${throwable.message}")
        defaultHandler?.uncaughtException(thread, throwable)
    }
}
