package org.pilink.android

import android.content.Context

class AppSettings(context: Context) {
    companion object { const val POWER_SAVING = "power_saving" }
    val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var powerSaving: Boolean
        get() = preferences.getBoolean(POWER_SAVING, false)
        set(value) { preferences.edit().putBoolean(POWER_SAVING, value).apply() }
}
