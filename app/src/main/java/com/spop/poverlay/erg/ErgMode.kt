package com.spop.poverlay.erg

import android.content.SharedPreferences

/**
 * Which ERG implementation to use.
 *
 * [Auto] is the ordinary setting. The other two exist so the two loops can be
 * compared on the same bike on the same day: the host loop has a measured
 * baseline -- two 96-second runs at 150 W holding a mean of 149.6 W, sigma 5.2 W,
 * 94% within +/-10 W -- and native PZAF has to be held to it rather than assumed
 * better.
 */
enum class ErgMode {
    /** Probe the bike, use native where it answers, host where it does not. */
    Auto,

    /** Force native PZAF, skipping the probe. Does nothing on a bike without it. */
    Native,

    /** Force the host PID loop, even on a bike that supports native. */
    Host;

    companion object {
        val Default = Auto

        fun fromPreference(value: String?): ErgMode =
            values().firstOrNull { it.name == value } ?: Default

        fun read(preferences: SharedPreferences, key: String): ErgMode =
            fromPreference(preferences.getString(key, null))
    }
}
