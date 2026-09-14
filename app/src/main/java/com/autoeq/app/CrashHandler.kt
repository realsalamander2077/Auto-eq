package com.autoeq.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Catches any uncaught exception, writes the full stack trace to a
 * plain text file in app-private storage, then hands off to whatever
 * default handler Android already had (so the system's normal "app
 * keeps stopping" behavior still happens - we're just recording the
 * reason first).
 *
 * The saved file can be read back via MainActivity's "View Crash Log"
 * button, so the actual error is visible on-device without needing a
 * computer or ADB.
 */
class CrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    companion object {
        const val CRASH_LOG_FILENAME = "last_crash.txt"

        fun readLastCrash(context: Context): String? {
            val file = File(context.filesDir, CRASH_LOG_FILENAME)
            return if (file.exists()) file.readText() else null
        }

        fun clearLastCrash(context: Context) {
            File(context.filesDir, CRASH_LOG_FILENAME).delete()
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val text = "Crashed at: ${java.util.Date()}\nThread: ${thread.name}\n\n$sw"
            File(context.filesDir, CRASH_LOG_FILENAME).writeText(text)
        } catch (_: Exception) {
            // If we can't even write the crash log, don't let that mask the original crash
        }
        defaultHandler?.uncaughtException(thread, throwable)
    }
}
