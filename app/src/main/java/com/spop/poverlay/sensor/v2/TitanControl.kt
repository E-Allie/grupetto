package com.spop.poverlay.sensor.v2

import kotlinx.coroutines.flow.Flow

/**
 * The parts of the Titan controller that are not sensor readings: its own
 * watt-setpoint loop, and the raw status record that reports on it.
 *
 * Only the Bike+ has this. [com.spop.poverlay.sensor.interfaces.SensorInterface]
 * returns null for it everywhere else, and that null is the first gate on the
 * whole native-ERG path.
 */
interface TitanControl {

    /** Every valid 256-byte status record, at the sensor poll rate. */
    val status: Flow<TitanStatusPacket>

    /** Most recent status record, or null before the first one arrives. */
    val latestStatus: TitanStatusPacket?

    /**
     * Enable Power Zone Auto Follow and hand it a target, in watts.
     *
     * The firmware clamps to 15..800 W; [PZAF_POWER_RANGE] is that clamp, applied
     * here so the number we asked for and the number the bike will hold are the
     * same. Android would happily serialise up to 32767.
     *
     * While this is active the controller owns the brake and ordinary
     * set-resistance commands are **dropped** -- `StartListnerTask` checks
     * `is_pzaf_enabled()` and logs rather than storing the new target.
     */
    fun setPowerZoneAutoFollow(watts: Int)

    /** Hand the brake back. Safe to call when PZAF is already off. */
    fun disablePowerZoneAutoFollow()

    /**
     * These four return whether AffernetService accepted the value, which is
     * **not** whether the controller did: `BikeServiceHelper` returns true as
     * soon as its own range check passes and the message is queued, and it never
     * hears back from the bike. A `true` here says only that the value was in
     * range and was sent. Firmware with no PZAF at all would also return true.
     * The status packet is the only place an effect is actually observable, which
     * is what [com.spop.poverlay.erg.PzafProbe] uses.
     */
    fun setPzafRampUpRate(rate: Int): Boolean
    fun setPzafRampDownRate(rate: Int): Boolean
    fun setPzafMaxResistance(percent: Int): Boolean
    fun setPzafMinUpdateRpm(rpm: Int): Boolean

    companion object {
        /**
         * `pzaf_mode_set_power_sp` clamps to these; 800 is the literal
         * IEEE-754 constant 0x44480000 loaded at 0x080073B4 in Titan 5.08.
         *
         * Measured on Titan 5.11 hardware rather than assumed to carry over:
         * with this clamp widened, requests of 2000, 900 and 801 W all came back
         * at offset 241 as 800, and 5 and 1 W both came back as 15. The two
         * firmwares agree, and clamping here keeps the number we asked for and
         * the number the bike will hold the same.
         */
        val PZAF_POWER_RANGE = 15..800

        /** Host-side ranges AffernetService enforces before sending anything. */
        val RAMP_RATE_RANGE = 5..100
        val MAX_RESISTANCE_RANGE = 30..100
        val MIN_UPDATE_RPM_RANGE = 30..120
    }
}
