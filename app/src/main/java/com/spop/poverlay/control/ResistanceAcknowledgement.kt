package com.spop.poverlay.control

import kotlin.math.abs

/** Tracks setpoint echoes, not physical motor position. Never retries a motor command. */
class ResistanceAcknowledgement(
    private val tolerance: Int = 2,
    private val deadlineMs: Long = 3_000,
    private val confirmations: Int = 2,
) {
    private var requested: Int? = null
    private var previous: Int? = null
    private var at = 0L
    private var candidate: Int? = null
    private var count = 0
    private var acknowledged: Int? = null
    private var lastTimestamp: Long? = null
    val isPending: Boolean get() = requested != null && acknowledged == null

    fun command(target: Int, now: Long, preceding: Int?) {
        requested = target; previous = preceding; at = now
        candidate = null; count = 0; acknowledged = null; lastTimestamp = null
    }
    fun clear() { requested = null; acknowledged = null; lastTimestamp = null }

    fun sample(targetEcho: Int, timestamp: Long, now: Long): ControlFault? {
        val target = requested ?: return null
        if (now - timestamp !in 0..1500 || lastTimestamp?.let { timestamp <= it } == true) return null
        lastTimestamp = timestamp
        // Once accepted, even a one-point *new setpoint* is an intervention. The arrival
        // tolerance is not a permanent dead zone around the requested resistance.
        acknowledged?.let { return if (targetEcho == it) null else ControlFault.RiderOverride }
        if (abs(targetEcho - target) <= tolerance) {
            if (candidate == targetEcho) count++ else { candidate = targetEcho; count = 1 }
            if (count >= confirmations) acknowledged = targetEcho
        } else {
            candidate = null; count = 0
            // A change away from both the prior and requested setpoints is not motor travel.
            if (previous != null && targetEcho != previous && now - at > 200)
                return ControlFault.RiderOverride
        }
        return if (acknowledged == null && now - at >= deadlineMs) ControlFault.ResistanceTimeout else null
    }
}
