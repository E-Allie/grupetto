package com.spop.poverlay.erg

import com.spop.poverlay.control.PowerModel
import com.spop.poverlay.control.ResistanceAcknowledgement
import kotlin.math.abs
import kotlin.math.roundToInt

/** Upstream 5061774 ERG model/ramp/feedback, isolated from FTMS ownership and SIM. */
class UpstreamSoftwareErgController(
    private val writeResistance: (Int) -> Boolean,
    private val clock: () -> Long,
    private val commandLock: Any,
    private val onStandDown: (String) -> Unit,
) {
    private data class Reading(val power: Float, val nativePower: Float, val cadence: Float,
        val resistance: Int, val powerTime: Long, val bikeTime: Long, val expiry: Long, val external: Boolean)
    private var reading: Reading? = null
    val powerModel = PowerModel()
    var isActive = false; private set
    var targetWatts = 0; private set
    private var gain = .007f
    private var filteredPower: Float? = null
    private var requestedResistance = 0f
    private var previousTick = 0L
    private var lastPowerTime = Long.MIN_VALUE
    private var lastBikeTime = Long.MIN_VALUE
    private var feedForwardTarget: Int? = null
    private var feedForwardLevel = 0f
    private var feedForwardUntil = 0L
    private var feedForwardDeadline = 0L
    private var arrivalPending = false
    private var rampLevel: Float? = null
    private var rampTime = 0L
    private var rampOffSince: Long? = null
    private var expected: Int? = null
    private var steadyResistance = -1
    private var steadySince = 0L
    private var cadenceAverage: Float? = null
    private var cadenceTime = 0L
    private val acknowledgement = ResistanceAcknowledgement()

    fun setGain(value: Float) = synchronized(commandLock) { if (value.isFinite()) gain = value.coerceIn(.001f, .03f) }
    fun sample(power: Float, cadence: Float, resistance: Int, timestamp: Long,
        staleAfterMs: Long, nativePower: Float = power, bikeTimestamp: Long = timestamp, external: Boolean = false
    ) = synchronized(commandLock) {
        if (!power.isFinite() || power < 0 || !cadence.isFinite() || cadence < 0 || resistance !in 0..100) {
            reading = null; if (isActive) fail("Invalid ERG feedback"); return@synchronized
        }
        reading = Reading(power, nativePower, cadence, resistance, timestamp, bikeTimestamp, staleAfterMs, external)
        val r = reading!!
        if (clock() - bikeTimestamp in 0..1500) learn(r)
    }
    /** Learn native bike watts even while another backend/manual riding is selected. */
    private fun learn(r: Reading) {
        if (r.bikeTime != lastBikeTime) {
            lastBikeTime = r.bikeTime
            if (steadyResistance != r.resistance) { steadyResistance = r.resistance; steadySince = clock() }
            else if (clock() - steadySince >= 2500) powerModel.learn(r.resistance, r.nativePower, r.cadence)
            val seconds = ((r.bikeTime - cadenceTime) / 1000f).coerceIn(0f, 1f)
            cadenceTime = r.bikeTime
            cadenceAverage = when { r.cadence < 50 -> null; cadenceAverage == null -> r.cadence
                else -> cadenceAverage!! + seconds / (5f + seconds) * (r.cadence - cadenceAverage!!) }
        }
    }
    private fun fresh(r: Reading) = clock() - r.powerTime in 0..r.expiry && clock() - r.bikeTime in 0..1500
    fun enable(watts: Int): Boolean = synchronized(commandLock) {
        val r = reading ?: return@synchronized false
        if (!fresh(r)) return@synchronized false
        targetWatts = watts.coerceIn(25, 1000); isActive = true
        requestedResistance = r.resistance.toFloat(); expected = r.resistance
        previousTick = clock(); lastPowerTime = Long.MIN_VALUE
        feedForwardTarget = null; filteredPower = null; rampLevel = null; arrivalPending = false
        feedForwardUntil = 0; acknowledgement.clear()
        true
    }
    fun setTarget(watts: Int): Boolean = synchronized(commandLock) {
        if (!isActive) return@synchronized false
        targetWatts = watts.coerceIn(25, 1000); true
    }
    fun disable() = synchronized(commandLock) { isActive = false; rampLevel = null; acknowledgement.clear() }

    fun tick() = synchronized(commandLock) {
        if (!isActive) return@synchronized
        val r = reading
        if (r == null || !fresh(r)) { fail("Power or cadence data lost"); return@synchronized }
        val newPower = r.powerTime != lastPowerTime
        if (rampLevel != null) { ramp(r); return@synchronized }
        if (acknowledgement.sample(r.resistance, r.bikeTime, clock()) != null) {
            fail("Resistance changed outside app control"); return@synchronized
        }
        // Do not advance ordinary feedback against an unacknowledged write. Otherwise
        // repeated new targets could perpetually extend the acknowledgement deadline.
        if (acknowledgement.isPending) return@synchronized
        if (!newPower) return@synchronized
        lastPowerTime = r.powerTime
        val dt = ((clock() - previousTick) / 1000f).coerceIn(0f, .4f); previousTick = clock()
        if (r.cadence < 25) { filteredPower = null; requestedResistance = r.resistance.toFloat(); return@synchronized }
        if (feedForwardTarget != targetWatts) {
            feedForwardTarget = targetWatts
            val level = if (!r.external && abs(targetWatts - r.power) >= 15)
                powerModel.nearestLevelFor(targetWatts.toFloat(), cadenceAverage ?: r.cadence)?.coerceAtMost(75) else null
            if (level != null) {
                feedForwardLevel = level.toFloat(); feedForwardUntil = clock() + 4500
                feedForwardDeadline = clock() + 10_000; arrivalPending = true; filteredPower = null
                rampLevel = r.resistance.toFloat(); rampTime = clock() - 100; rampOffSince = null
                ramp(r); return@synchronized
            }
            feedForwardUntil = 0; arrivalPending = false
        }
        if (arrivalPending && abs(r.resistance - feedForwardLevel.roundToInt()) <= 2) {
            arrivalPending = false; feedForwardUntil = maxOf(feedForwardUntil, clock() + 4500)
        }
        if (clock() < feedForwardUntil || arrivalPending && clock() < feedForwardDeadline) return@synchronized
        val power = filteredPower?.let { it + dt / (2f + dt) * (r.power - it) } ?: r.power
        filteredPower = power
        val level = requestedResistance.roundToInt().coerceIn(0, 99)
        val cadence = cadenceAverage ?: r.cadence
        val band = maxOf(3f, .5f * (powerModel.wattsAt(level + 1, cadence) - powerModel.wattsAt(level, cadence)))
        val error = targetWatts - power
        val target = requestedResistance + if (abs(error) <= band) 0f else gain * error * dt / .1f
        requestedResistance = target.coerceIn(requestedResistance - 3f * dt, requestedResistance + 3f * dt).coerceIn(0f, 100f)
        val next = requestedResistance.roundToInt()
        if (next != expected && next != r.resistance) write(next, r)
    }
    private fun ramp(r: Reading) {
        val from = rampLevel ?: return
        if (targetWatts != feedForwardTarget || r.cadence < 25 || r.external) {
            rampLevel = null; requestedResistance = r.resistance.toFloat(); expected = r.resistance
            acknowledgement.clear(); previousTick = clock(); return
        }
        if (expected?.let { abs(r.resistance - it) > 5 } == true) {
            val since = rampOffSince ?: clock().also { rampOffSince = it }
            if (clock() - since > 1500) fail("Resistance changed during modeled ramp")
            rampTime = clock(); return
        }
        rampOffSince = null
        val seconds = ((clock() - rampTime) / 1000f).coerceIn(0f, .2f); rampTime = clock()
        val goal = feedForwardLevel.roundToInt()
        val next = if (goal > from) minOf(from + 10f * seconds, goal.toFloat()) else maxOf(from - 10f * seconds, goal.toFloat())
        rampLevel = next; val level = next.roundToInt()
        if (level != expected && level != r.resistance && !write(level, r)) return
        requestedResistance = level.toFloat()
        if (level == goal) {
            rampLevel = null; previousTick = clock()
            acknowledgement.command(level, clock(), r.resistance)
        }
    }
    private fun write(level: Int, r: Reading): Boolean {
        if (!runCatching { writeResistance(level) }.getOrDefault(false)) { fail("Resistance command failed"); return false }
        expected = level; acknowledgement.command(level, clock(), r.resistance); return true
    }
    private fun fail(reason: String) { disable(); onStandDown(reason) }
}
