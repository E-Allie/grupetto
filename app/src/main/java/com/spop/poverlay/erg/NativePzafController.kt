package com.spop.poverlay.erg

import com.spop.poverlay.sensor.v2.PzafStatus
import com.spop.poverlay.sensor.v2.TitanControl

/** Auto Follow backend. The coordinator holds commandLock for every call. */
class NativePzafController(
    private val control: TitanControl,
    private val clock: () -> Long,
    private val onStandDown: (String) -> Unit
) {
    var isActive = false
        private set
    var targetWatts = 0
        private set
    /** An accepted/uncertain enable or release remains ours until disable succeeds. */
    var ownsControl = false
        private set
    private var enabledAt = 0L
    private var armed = false

    fun enable(watts: Int): Boolean {
        val reading = control.latestStatusReading
        if (reading == null || clock() - reading.timestamp !in 0..1500) {
            fail("Native status data lost"); return false
        }
        val clamped = watts.coerceIn(TitanControl.PZAF_POWER_RANGE)
        val accepted = runCatching {
            control.setPzafRampUpRate(DEFAULT_RAMP_UP) &&
                control.setPzafRampDownRate(DEFAULT_RAMP_DOWN) &&
                control.setPzafMaxResistance(DEFAULT_MAX_RESISTANCE) &&
                control.setPzafMinUpdateRpm(DEFAULT_MIN_UPDATE_RPM) && run {
                    // A failed transaction may still have reached the controller.
                    ownsControl = true
                    control.setPowerZoneAutoFollow(clamped)
                }
        }.getOrDefault(false)
        if (!accepted) { fail("Native Auto Follow command failed"); return false }
        targetWatts = clamped
        enabledAt = clock()
        armed = false
        isActive = true
        return true
    }

    fun setTarget(watts: Int): Boolean {
        if (!isActive) return false
        // This transaction also enables native control. Observe an already
        // received knob/error stand-down before a new remote target can rearm it,
        // even when the periodic watcher has not reached its next tick yet.
        tick()
        if (!isActive) return false
        val clamped = watts.coerceIn(TitanControl.PZAF_POWER_RANGE)
        if (clamped == targetWatts) return true
        if (!runCatching { control.setPowerZoneAutoFollow(clamped) }.getOrDefault(false)) {
            fail("Native Auto Follow target failed"); return false
        }
        targetWatts = clamped
        return true
    }

    fun disable(force: Boolean = false): Boolean {
        isActive = false
        armed = false
        // Observing another app's native status does not give us ownership. Only
        // an intentional handoff may release a loop this instance did not start.
        if (!force && !ownsControl) return true
        ownsControl = true
        val released = runCatching { control.disablePowerZoneAutoFollow() }.getOrDefault(false)
        if (released) ownsControl = false
        return released
    }

    fun tick() {
        if (!isActive) return
        val reading = control.latestStatusReading
        if (reading == null || clock() - reading.timestamp !in 0..1500) {
            fail("Native status data lost"); return
        }
        if (reading.timestamp >= enabledAt) {
            if (PzafStatus.isEnabled(reading.packet.pzafStatus)) armed = true
            else if (armed) { fail("Native Auto Follow ${PzafStatus.name(reading.packet.pzafStatus)}"); return }
        }
        if (!armed && clock() - enabledAt >= ARM_GRACE_MS)
            fail("Native Auto Follow did not arm")
    }

    private fun fail(reason: String) { disable(); onStandDown(reason) }

    companion object {
        // Existing Peloton service configuration; these are not the removed
        // application-wide resistance-per-second limiter.
        const val DEFAULT_RAMP_UP = 50
        const val DEFAULT_RAMP_DOWN = 8
        const val DEFAULT_MAX_RESISTANCE = 100
        const val DEFAULT_MIN_UPDATE_RPM = 30
        const val ARM_GRACE_MS = 3000L
    }
}
