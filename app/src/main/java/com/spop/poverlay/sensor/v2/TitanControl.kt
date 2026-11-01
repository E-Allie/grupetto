package com.spop.poverlay.sensor.v2

import kotlinx.coroutines.flow.Flow

/** A validated native status packet and the monotonic time it was received. */
data class TitanStatusReading(val packet: TitanStatusPacket, val timestamp: Long)

/**
 * Bike+ Power Zone Auto Follow commands, through the existing Peloton service.
 * The nullable SensorInterface handle is the hardware-support gate. Commands
 * return false while disconnected or when the service rejects the transaction.
 * Acceptance by the service does not confirm execution by the bike: the status
 * packet is the acknowledgement, and its timestamp must be checked for freshness.
 */
interface TitanControl {
    val status: Flow<TitanStatusPacket>
    val latestStatusReading: TitanStatusReading?
    val latestStatus: TitanStatusPacket? get() = latestStatusReading?.packet
    val latestStatusTimestamp: Long? get() = latestStatusReading?.timestamp

    /** Enable native watt control; the bike clamps setpoints to 15..800 W. */
    fun setPowerZoneAutoFollow(watts: Int): Boolean

    /** Release the brake from native watt control before sending resistance. */
    fun disablePowerZoneAutoFollow(): Boolean

    /** These report the service's range-check/queue result, not motor execution. */
    fun setPzafRampUpRate(rate: Int): Boolean
    fun setPzafRampDownRate(rate: Int): Boolean
    fun setPzafMaxResistance(percent: Int): Boolean
    fun setPzafMinUpdateRpm(rpm: Int): Boolean

    companion object {
        val PZAF_POWER_RANGE = 15..800
        val RAMP_RATE_RANGE = 5..100
        val MAX_RESISTANCE_RANGE = 30..100
        val MIN_UPDATE_RPM_RANGE = 30..120
    }
}
