package com.spop.poverlay.erg

import com.spop.poverlay.sensor.v2.PzafStatus
import com.spop.poverlay.sensor.v2.TitanControl

sealed interface PzafCapability {
    data class Supported(val firmware: String) : PzafCapability
    data class Unsupported(val reason: String, val transient: Boolean = false) : PzafCapability
}

/**
 * Tick-driven native capability check. Only a harmless configuration value is
 * changed; this probe never enables Auto Follow or sends a resistance target.
 * The caller serializes start/tick/cancel with every actuator command.
 */
class PzafProbe(private val control: TitanControl?, private val clock: () -> Long) {
    var result: PzafCapability? = null
        private set
    private var deadline: Long? = null
    private var writtenAt: Long? = null
    private var probeValue = 0
    private var firmware = ""

    fun start() {
        if (result != null || deadline != null) return
        if (control == null) result = PzafCapability.Unsupported("no native controller on this bike")
        else deadline = clock() + PACKET_TIMEOUT_MS
    }

    fun cancel() { deadline = null; writtenAt = null }

    fun tick() {
        val end = deadline ?: return
        val now = clock()
        val reading = control?.latestStatusReading?.takeIf { now - it.timestamp in 0..1500 }
        if (writtenAt == null) {
            if (reading != null) {
                val packet = reading.packet
                firmware = "${packet.firmwareMajor}.${packet.firmwareMinor}"
                if (packet.isLegacyPzafMarker || packet.firmwareMajor < MIN_FIRMWARE_MAJOR) {
                    finish(PzafCapability.Unsupported("controller firmware $firmware has no native Auto Follow")); return
                }
                if (PzafStatus.isEnabled(packet.pzafStatus)) {
                    // A preceding disable may still be in flight. Do not alter
                    // configuration while the packet reports an active loop.
                    if (now >= end) finish(PzafCapability.Unsupported("native controller did not release control"))
                    return
                }
                probeValue = probeValueFor(packet.pzafMinUpdateRpm)
                if (!runCatching { control!!.setPzafMinUpdateRpm(probeValue) }.getOrDefault(false)) {
                    finish(PzafCapability.Unsupported("native configuration command failed")); return
                }
                writtenAt = now
                deadline = now + ECHO_TIMEOUT_MS
            } else if (now >= end) finish(PzafCapability.Unsupported("no fresh native status packet", transient = true))
        } else {
            if (reading != null && reading.timestamp >= writtenAt!! && reading.packet.pzafMinUpdateRpm == probeValue)
                finish(PzafCapability.Supported(firmware))
            else if (now >= end) finish(PzafCapability.Unsupported("native controller did not acknowledge configuration"))
        }
    }

    private fun finish(value: PzafCapability) { result = value; cancel() }

    companion object {
        const val MIN_FIRMWARE_MAJOR = 5
        const val PROBE_RPM_PRIMARY = 40
        const val PROBE_RPM_ALTERNATE = 45
        const val PACKET_TIMEOUT_MS = 10_000L
        const val ECHO_TIMEOUT_MS = 3_000L
        fun probeValueFor(currentMinUpdateRpm: Int) =
            if (currentMinUpdateRpm == PROBE_RPM_PRIMARY) PROBE_RPM_ALTERNATE else PROBE_RPM_PRIMARY
    }
}
