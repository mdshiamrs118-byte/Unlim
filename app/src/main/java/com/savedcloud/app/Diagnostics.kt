package com.savedcloud.app

import android.content.Context
import android.os.Build
import android.util.Log
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
        try { marker(ctx).writeText("1") } catch (_: Throwable) { }
    }

    fun markHealthy(ctx: Context) {
        try { marker(ctx).delete() } catch (_: Throwable) { }
    }

    fun startedUncleanly(ctx: Context): Boolean = marker(ctx).exists()
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
            problem = "The app closed while starting Telegram last time (probably a native crash).\n\n" +
                Diagnostics.deviceInfo()
            return
        }
        start(app)
    }

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        try {
            Diagnostics.markStarting(app)
            if (!Config.startIfPossible(app)) Diagnostics.markHealthy(app)
        } catch (t: Throwable) {
            Diagnostics.markHealthy(app)
            problem = Diagnostics.format(t)
        }
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
