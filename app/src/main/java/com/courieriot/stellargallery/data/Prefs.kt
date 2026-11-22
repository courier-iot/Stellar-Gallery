package com.courieriot.stellargallery.data

import android.content.Context

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var sortOrder: SortOrder
        get() = runCatching { SortOrder.valueOf(p.getString("sort", null)!!) }.getOrDefault(SortOrder.NEWEST)
        set(v) = p.edit().putString("sort", v.name).apply()

    var gridColumns: Int
        get() = p.getInt("cols", 4)
        set(v) = p.edit().putInt("cols", v).apply()

    var groupByDate: Boolean
        get() = p.getBoolean("group", true)
        set(v) = p.edit().putBoolean("group", v).apply()

    var checkUpdates: Boolean
        get() = p.getBoolean("updates", true)
        set(v) = p.edit().putBoolean("updates", v).apply()
}
