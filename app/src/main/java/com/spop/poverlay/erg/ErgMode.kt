package com.spop.poverlay.erg

import android.content.SharedPreferences

/** Native Auto Follow or the app's table-assisted software controller. */
enum class ErgMode {
    /** Use native control after a successful capability probe; otherwise software. */
    Auto,
    /** Require a successful native capability probe (an external meter overrides this). */
    Native,
    Host;

    companion object {
        val Default = Auto
        fun fromPreference(value: String?): ErgMode = values().firstOrNull { it.name == value } ?: Default
        fun read(preferences: SharedPreferences, key: String): ErgMode = fromPreference(preferences.getString(key, null))
    }
}
