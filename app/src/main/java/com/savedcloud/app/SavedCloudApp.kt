package com.savedcloud.app

import android.app.Application
import android.content.Context
import android.os.Build
import com.savedcloud.app.td.TdParams
import com.savedcloud.app.td.TdRepository
import java.io.File
import java.util.Locale

class SavedCloudApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
    }
}

/** Stores the Telegram API credentials and starts TDLib once they are known. */
object Config {
    private const val PREFS = "savedcloud"

    fun credentials(ctx: Context): Pair<Int, String>? {
        if (BuildConfig.TG_API_ID > 0 && BuildConfig.TG_API_HASH.isNotBlank()) {
            return BuildConfig.TG_API_ID to BuildConfig.TG_API_HASH
        }
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getInt("api_id", 0)
        val hash = p.getString("api_hash", "") ?: ""
        return if (id > 0 && hash.isNotBlank()) id to hash else null
    }

    fun save(ctx: Context, apiId: Int, apiHash: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt("api_id", apiId)
            .putString("api_hash", apiHash.trim())
            .apply()
    }

    /** Returns false when no credentials are known yet. */
    fun startIfPossible(ctx: Context): Boolean {
        val c = credentials(ctx) ?: return false
        val lang = Locale.getDefault().language.ifBlank { "en" }
        TdRepository.start(
            TdParams(
                rootDir = File(ctx.applicationContext.filesDir, "td").absolutePath,
                apiId = c.first,
                apiHash = c.second,
                language = lang,
                deviceModel = Build.MODEL ?: "Android",
                systemVersion = "Android ${Build.VERSION.RELEASE}",
                appVersion = BuildConfig.VERSION_NAME
            )
        )
        return true
    }
}
