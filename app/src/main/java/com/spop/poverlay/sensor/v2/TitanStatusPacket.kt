package com.spop.poverlay.sensor.v2

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The 256-byte Titan status record, as read from `BikeData.getPacketData()`.
 *
 * Preferred over the parcel's individual fields. The packet validates itself with
 * a magic and a footer, and it sits eight fields into the parcel rather than at
 * the very end: Peloton's own copy of `BikeData` writes sixty fields where the
 * one in this tree reads fifty-six, and the four it appended land immediately
 * after the PZAF block. One more upstream insertion anywhere in those fifty-six
 * and the PZAF values read as garbage, silently.
 *
 * Every multi-byte value is big-endian. The firmware writes most significant
 * byte first (`mem_byte_rev` in `send_topaz_packet`) and Java's ByteBuffer
 * default agrees, so no order needs to be set for anything but clarity.
 */
class TitanStatusPacket private constructor(private val raw: ByteBuffer) {

    val firmwareMajor: Int get() = raw.getShort(12).toInt() and 0xFFFF
    val firmwareMinor: Int get() = raw.getShort(14).toInt() and 0xFFFF

    val cadenceRpm: Int get() = raw.getInt(32)

    /** Where the brake actually is. */
    val currentResistance: Int get() = raw.getInt(36)

    /** Where the brake has been told to go. This is what Grupetto publishes today. */
    val targetResistance: Int get() = raw.getInt(40)

    /** Calibration state: 0 idle, 1 homing, 2 load-cell table generation. */
    val calibrationState: Int get() = raw.getInt(60)

    val powerWatts: Float get() = raw.getInt(68) / 100f

    val systemState: Int get() = raw.get(73).toInt() and 0xFF

    /** `pzaf_get_mode()`: 1 while the controller's watt loop owns the brake. */
    val pzafMode: Int get() = raw.get(240).toInt() and 0xFF

    /** Setpoint the controller is holding, after its own 15..800 W clamp. */
    val pzafSetpointWatts: Int get() = raw.getShort(241).toInt() and 0xFFFF

    /** Resistance the controller's feed-forward has inferred for that setpoint. */
    val pzafTargetResistance: Float get() = raw.getFloat(243)

    val pzafStatus: Int get() = raw.get(247).toInt() and 0xFF

    /**
     * Effective PZAF settings, after the firmware's own clamps. Worth reading
     * rather than assuming: AffernetService accepts a ramp-down rate of 5..100
     * but Titan 5.08 clamps it to 5..10, so anything above 10 quietly becomes 10
     * and only these bytes say so.
     */
    val pzafRampUpRate: Int get() = raw.get(248).toInt() and 0xFF
    val pzafRampDownRate: Int get() = raw.get(249).toInt() and 0xFF
    val pzafMaxResistance: Int get() = raw.get(250).toInt() and 0xFF
    val pzafMinUpdateRpm: Int get() = raw.get(251).toInt() and 0xFF

    /**
     * Titan 1.88 and 1.94 write this fixed marker across offsets 240..243, where
     * 5.08 keeps live PZAF state. It identifies firmware with no watt-target loop
     * without trusting the version string to be formatted the way we expect.
     */
    val isLegacyPzafMarker: Boolean
        get() = raw.getInt(240) == LEGACY_PZAF_MARKER

    /** True in all four enabled statuses, false for every disable reason. */
    val pzafActive: Boolean get() = pzafStatus >= PzafStatus.FIRST_ENABLED

    /** Copy of the raw bytes, for recording a frame verbatim. */
    fun toByteArray(): ByteArray = ByteArray(LENGTH).also { raw.duplicate().get(it, 0, LENGTH) }

    override fun toString(): String =
        "TitanStatusPacket(fw=$firmwareMajor.$firmwareMinor cadence=$cadenceRpm " +
            "power=${"%.1f".format(powerWatts)}W resistance=$currentResistance/$targetResistance " +
            "pzaf(mode=$pzafMode sp=${pzafSetpointWatts}W target=${"%.1f".format(pzafTargetResistance)}% " +
            "status=${PzafStatus.name(pzafStatus)} up=$pzafRampUpRate down=$pzafRampDownRate " +
            "maxRes=$pzafMaxResistance minRpm=$pzafMinUpdateRpm))"

    companion object {
        const val LENGTH = 256

        private const val MAGIC = 0xDEADBEEF.toInt()
        private const val FOOTER = 0xCCDDEEFF.toInt()
        private const val LEGACY_PZAF_MARKER = 0x00112233

        /**
         * Returns null for anything that is not a well-formed status record.
         * AffernetService's own validator checks exactly these two anchors.
         */
        fun parse(bytes: ByteArray?): TitanStatusPacket? {
            if (bytes == null || bytes.size < LENGTH) return null
            val buffer = ByteBuffer.wrap(bytes, 0, LENGTH).order(ByteOrder.BIG_ENDIAN)
            if (buffer.getInt(0) != MAGIC || buffer.getInt(252) != FOOTER) return null
            return TitanStatusPacket(buffer)
        }
    }
}

/**
 * The PZAF control status at packet offset 247.
 *
 * Peloton's `PowerZoneAutoFollowStatus` names 0..6 and 20..23, but the firmware's
 * `pzaf_set_control_status` also accepts 7 and `pzaf_no_usage_timeout` passes it:
 * `pzaf_disable_control` moves its reason argument into r4 and calls
 * `pzaf_set_control_status` without reloading r0, so the reason code *is* the
 * status. Peloton's enum has a gap. Nothing here switches on the individual
 * disable values for that reason -- [isEnabled] is the test that matters.
 */
object PzafStatus {
    const val DISABLED_BY_COMMAND = 0
    const val DISABLED_BY_KNOB = 1
    const val DISABLED_BY_NO_COMMS = 2
    const val DISABLED_BY_LOW_POWER = 3
    const val DISABLED_BY_HOMING = 4
    const val DISABLED_BY_CALIBRATION = 5
    const val DISABLED_BY_ERROR = 6

    /** Firmware-only; unnamed by Peloton. Fires after 60s of *zero cadence*. */
    const val DISABLED_BY_NO_USAGE_TIMEOUT = 7

    const val ENABLED_ZERO_RPM = 20
    const val ENABLED_LOW_RPM = 21
    const val ENABLED_POWER_IN_RANGE = 22
    const val ENABLED_ACTIVE = 23

    /** Every enabled status is >= this; treat anything below it as disabled. */
    const val FIRST_ENABLED = ENABLED_ZERO_RPM

    fun isEnabled(status: Int) = status >= FIRST_ENABLED

    fun name(status: Int) = when (status) {
        DISABLED_BY_COMMAND -> "disabled by command"
        DISABLED_BY_KNOB -> "disabled by knob"
        DISABLED_BY_NO_COMMS -> "disabled by no communications"
        DISABLED_BY_LOW_POWER -> "disabled by low power"
        DISABLED_BY_HOMING -> "disabled by homing"
        DISABLED_BY_CALIBRATION -> "disabled by calibration"
        DISABLED_BY_ERROR -> "disabled by error"
        DISABLED_BY_NO_USAGE_TIMEOUT -> "disabled by no-usage timeout"
        ENABLED_ZERO_RPM -> "enabled, zero rpm"
        ENABLED_LOW_RPM -> "enabled, low rpm"
        ENABLED_POWER_IN_RANGE -> "enabled, power in range"
        ENABLED_ACTIVE -> "enabled, active"
        else -> "unknown ($status)"
    }
}
