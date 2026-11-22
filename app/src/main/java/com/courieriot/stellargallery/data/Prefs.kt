package com.courieriot.stellargallery.data

import android.content.Context
import com.courieriot.stellargallery.ui.theme.Accent

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var sortOrder: SortOrder
        get() = runCatching { SortOrder.valueOf(p.getString("sort", null)!!) }.getOrDefault(SortOrder.NEWEST)
        set(v) = p.edit().putString("sort", v.name).apply()

    var gridColumns: Int
        get() = p.getInt("cols", 4).coerceIn(2, 6)
        set(v) = p.edit().putInt("cols", v).apply()

    var groupByDate: Boolean
        get() = p.getBoolean("group", true)
        set(v) = p.edit().putBoolean("group", v).apply()

    var checkUpdates: Boolean
        get() = p.getBoolean("updates", true)
        set(v) = p.edit().putBoolean("updates", v).apply()

    // ---- v2.0.0 ----

    var accent: Accent
        get() = runCatching { Accent.valueOf(p.getString("accent", null)!!) }.getOrDefault(Accent.CYAN)
        set(v) = p.edit().putString("accent", v.name).apply()

    var pureBlack: Boolean
        get() = p.getBoolean("black", false)
        set(v) = p.edit().putBoolean("black", v).apply()

    var slideshowSeconds: Int
        get() = p.getInt("slide_secs", 4)
        set(v) = p.edit().putInt("slide_secs", v).apply()

    var stripMetadata: Boolean
        get() = p.getBoolean("strip_meta", true)
        set(v) = p.edit().putBoolean("strip_meta", v).apply()

    var hideVaultEntry: Boolean
        get() = p.getBoolean("hide_vault", false)
        set(v) = p.edit().putBoolean("hide_vault", v).apply()

    var showMemories: Boolean
        get() = p.getBoolean("memories", true)
        set(v) = p.edit().putBoolean("memories", v).apply()

    var widgetFavoritesOnly: Boolean
        get() = p.getBoolean("widget_fav", true)
        set(v) = p.edit().putBoolean("widget_fav", v).apply()

    // Backup (WebDAV) — off unless configured
    var backupUrl: String
        get() = p.getString("bk_url", "") ?: ""
        set(v) = p.edit().putString("bk_url", v).apply()
    var backupUser: String
        get() = p.getString("bk_user", "") ?: ""
        set(v) = p.edit().putString("bk_user", v).apply()
    var backupPassword: String
        get() = p.getString("bk_pass", "") ?: ""
        set(v) = p.edit().putString("bk_pass", v).apply()
    var backupOnLaunch: Boolean
        get() = p.getBoolean("bk_auto", false)
        set(v) = p.edit().putBoolean("bk_auto", v).apply()
    var backupIncludeVideos: Boolean
        get() = p.getBoolean("bk_video", true)
        set(v) = p.edit().putBoolean("bk_video", v).apply()

    // ---- v2.1.0 ----
    var sorterEnabled: Boolean
        get() = p.getBoolean("sorter", true)
        set(v) = p.edit().putBoolean("sorter", v).apply()

    var sorterSimplified: Boolean
        get() = p.getBoolean("sorter_simple", true)
        set(v) = p.edit().putBoolean("sorter_simple", v).apply()

    companion object {
        fun get(context: Context) = Prefs(context.applicationContext)
    }
}
