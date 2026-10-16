package com.savedcloud.app

import android.content.Context
import android.os.Build
import android.util.Log
import com.savedcloud.app.td.TdRepository
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Records crashes so the app can show what went wrong instead of crash-looping. */
object Diagnostics {
    private fun crashFile(ctx: Context) = File(ctx.filesDir, "last_crash.txt")
    private fun marker(ctx: Context) = File(ctx.filesDir, "td_starting.marker")

    fun deviceInfo(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "ABIs: ${Build.SUPPORTED_ABIS.joinToString()}"

    fun format(e: Throwable, thread: String = "main"): String =
        "Saved Cloud ${BuildConfig.VERSION_NAME}\n${deviceInfo()}\nThread: $thread\n\n${Log.getStackTraceString(e)}"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                crashFile(app).writeText(format(e, t.name))
            } catch (_: Throwable) { }
            previous?.uncaughtException(t, e)
        }
    }

    fun lastCrash(ctx: Context): String? =
        try { crashFile(ctx).takeIf { it.exists() }?.readText() } catch (_: Throwable) { null }

    fun clearCrash(ctx: Context) {
        try { crashFile(ctx).delete() } catch (_: Throwable) { }
    }

    /** Written just before TDLib starts, removed once TDLib has answered. Survives native crashes. */
    fun markStarting(ctx: Context) {
        try { marker(ctx).writeText("starting") } catch (_: Throwable) { }
    }

    /** Records how far startup got. Only writes while the start marker exists. */
    fun markStep(ctx: Context, step: String) {
        try {
            val m = marker(ctx)
            if (m.exists()) m.writeText(step)
        } catch (_: Throwable) { }
    }

    fun lastStep(ctx: Context): String =
        try { marker(ctx).takeIf { it.exists() }?.readText() ?: "unknown" } catch (_: Throwable) { "unknown" }

    fun markHealthy(ctx: Context) {
        try { marker(ctx).delete() } catch (_: Throwable) { }
    }

    fun startedUncleanly(ctx: Context): Boolean = marker(ctx).exists()

    /**
     * Android lets an app read its own earlier log lines, including the native crash message of the
     * previous process. Returns the interesting lines (signal, abort message, JNI errors, backtrace).
     */
    fun recentLog(): String {
        return try {
            val proc = ProcessBuilder("logcat", "-d", "-t", "1500", "-v", "threadtime")
                .redirectErrorStream(true)
                .start()
            val lines = proc.inputStream.bufferedReader().readLines()
            try { proc.destroy() } catch (_: Throwable) { }
            val keep = Regex(
                "tdjni|tdlib|JNI|CheckJNI|SIGSEGV|SIGABRT|SIGBUS|SIGILL|signal \\d|Fatal|FATAL|abort|" +
                    "AndroidRuntime|DEBUG|linker|dlopen|UnsatisfiedLink|OutOfMemory|savedcloud|backtrace|#\\d\\d ",
                RegexOption.IGNORE_CASE
            )
            val hits = lines.filter { keep.containsMatchIn(it) }.takeLast(70)
            if (hits.isEmpty()) "(no matching system log lines found)" else hits.joinToString("\n") { it.take(240) }
        } catch (t: Throwable) {
            "(could not read system log: ${t.message})"
        }
    }
}

/** Starts TDLib safely and holds a problem description for the recovery screen. */
object Boot {
    var problem by mutableStateOf<String?>(null)

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        val crash = Diagnostics.lastCrash(app)
        if (crash != null) {
            problem = crash
            return
        }
        if (Diagnostics.startedUncleanly(app)) {
            val base = "The app closed while starting Telegram last time. If you simply closed it while it was " +
                "still loading, tap Retry. If it keeps happening, it is a crash: copy this report.\n\n" +
                "Saved Cloud ${BuildConfig.VERSION_NAME}\n" +
                "Last startup step reached: ${Diagnostics.lastStep(app)}\n\n" +
                Diagnostics.deviceInfo()
            problem = base + "\n\n(reading system log...)"
            Thread({
                val log = Diagnostics.recentLog()
                if (problem != null && problem!!.startsWith(base)) {
                    problem = base + "\n\nSystem log:\n" + log
                }
            }, "savedcloud-log").start()
            return
        }
        start(app)
    }

    /**
     * Starts TDLib on a background thread: loading the native library initialises thousands of
     * classes, which can take many seconds on slower phones and must not block the UI thread.
     */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (Config.credentials(app) != null) TdRepository.beginStarting()
        // 16 MB stack: loading the native library runs a very large initialiser.
        Thread(null, {
            try {
                Diagnostics.markStarting(app)
                TdRepository.onStep = { Diagnostics.markStep(app, it) }
                if (!Config.startIfPossible(app)) Diagnostics.markHealthy(app)
            } catch (t: Throwable) {
                Diagnostics.markHealthy(app)
                problem = Diagnostics.format(t)
            }
        }, "savedcloud-boot", 16L * 1024 * 1024).start()
    }

    fun retry(ctx: Context) {
        val app = ctx.applicationContext
        Diagnostics.clearCrash(app)
        Diagnostics.markHealthy(app)
        problem = null
        start(app)
    }

    /** Deletes the local Telegram session/database (you will have to log in again) and retries. */
    fun resetAndRetry(ctx: Context) {
        val app = ctx.applicationContext
        try { File(app.filesDir, "td").deleteRecursively() } catch (_: Throwable) { }
        retry(app)
    }
}
