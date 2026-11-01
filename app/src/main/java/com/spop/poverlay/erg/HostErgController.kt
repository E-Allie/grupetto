package com.spop.poverlay.erg

import kotlin.math.abs
import kotlin.math.pow

/**
 * Timestamp-driven version of the fork's table feed-forward, gain-scheduled PD
 * controller. No coroutine reads a replayed Flow: each telemetry timestamp is
 * consumed at most once and stale input disarms the loop. All entry points share
 * the trainer's command lock; session ownership and the rider latch live there.
 */
class HostErgController(
    private val writeResistance: (Int) -> Boolean,
    private val clock: () -> Long,
    private val commandLock: Any = Any(),
    private val onStandDown: (String) -> Unit = {}
) {
    companion object {
        const val MIN_TARGET_POWER = 25
        const val MAX_TARGET_POWER = 1000
        const val FRESHNESS_MS = 1500L
        private const val DEFAULT_KP = 0.007
        private const val KD = 0.02
        private const val EMA_ALPHA = 0.047619047619047616
        private const val DEADBAND = 3.0
        private const val SENSITIVITY = 0.05
        private const val SETTLE_MS = 2000L
    }

    private data class Sample(val power: Double, val cadence: Double, val resistance: Int,
        val timestamp: Long, val staleAfterMs: Long)
    private var reading: Sample? = null
    private val tracker = ResistanceCommandTracker()
    private var lastProcessed: Long? = null
    private var currentResistance = 0.0
    private var smoothedPower: Double? = null
    private var previousSmoothedPower = 0.0
    private var firstDerivative = true
    private var feedForwardPending = false
    private var settleUntil = 0L
    private var gain = DEFAULT_KP
    var isActive: Boolean = false
        private set
    var targetWatts: Int = 0
        private set

    fun setGain(value: Float) = synchronized(commandLock) {
        if (value.isFinite() && value > 0f) gain = value.toDouble()
    }

    fun sample(power: Float, cadence: Float, resistance: Int, timestamp: Long,
               staleAfterMs: Long = FRESHNESS_MS) = synchronized(commandLock) {
        if (!power.isFinite() || power < 0 || !cadence.isFinite() || cadence < 0 || resistance !in 0..100) {
            if (isActive) fail("Invalid power, cadence, or resistance feedback")
            reading = null
            return@synchronized
        }
        // An inactive loop may receive a fresh earlier timestamp after the owner
        // deliberately switches power sources. Never replay old data while active.
        if (isActive && reading?.let { timestamp < it.timestamp } == true) return@synchronized
        reading = Sample(power.toDouble(), cadence.toDouble(), resistance, timestamp, staleAfterMs.coerceIn(1, 3000))
        if (isActive && fresh(reading!!) && tracker.isUnexpected(resistance, clock()))
            fail("Resistance changed outside app control")
    }

    fun enable(watts: Int): Boolean = synchronized(commandLock) {
        val sample = reading
        if (sample == null || !fresh(sample)) {
            fail("Power or cadence data lost")
            return@synchronized false
        }
        targetWatts = watts.coerceIn(MIN_TARGET_POWER, MAX_TARGET_POWER)
        currentResistance = sample.resistance.toDouble()
        tracker.reset(sample.resistance, clock())
        lastProcessed = null
        smoothedPower = null
        previousSmoothedPower = 0.0
        firstDerivative = true
        feedForwardPending = true
        settleUntil = 0L
        isActive = true
        true
    }

    fun setTarget(watts: Int): Boolean = synchronized(commandLock) {
        if (!isActive) return@synchronized false
        val clamped = watts.coerceIn(MIN_TARGET_POWER, MAX_TARGET_POWER)
        if (abs(clamped - targetWatts) > 20) feedForwardPending = true
        targetWatts = clamped
        true
    }

    fun disable() = synchronized(commandLock) { isActive = false }

    fun tick() = synchronized(commandLock) {
        if (!isActive) return@synchronized
        val sample = reading
        if (sample == null || !fresh(sample)) {
            fail("Power or cadence data lost")
            return@synchronized
        }
        if (sample.timestamp == lastProcessed) return@synchronized
        // The old loop ran every 100 ms. Integrate its EMA and its correction over
        // the actual sample interval, so 200 ms sensor polling does not halve gain.
        val steps = lastProcessed?.let { (sample.timestamp - it) / 100.0 } ?: 1.0
        lastProcessed = sample.timestamp
        if (steps <= 0) return@synchronized
        update(sample, steps)
    }

    private fun update(sample: Sample, steps: Double) {
        // Exact discrete-time extension of the original 100 ms EMA. The integrated
        // P error also follows the same repeated constant-sample baseline.
        val oldPower = smoothedPower ?: sample.power
        val decay = (1 - EMA_ALPHA).pow(steps)
        val power = sample.power + (oldPower - sample.power) * decay
        smoothedPower = power
        if (sample.cadence < 25) return
        if (feedForwardPending) {
            val predicted = PowerTable.resistanceFor(targetWatts, sample.cadence)
            if (predicted != null) {
                feedForwardPending = false
                currentResistance = predicted.coerceIn(0.0, 100.0)
                if (!write(currentResistance.toInt())) return
                settleUntil = clock() + SETTLE_MS
                return
            }
            if (abs(targetWatts - power) < DEADBAND) feedForwardPending = false
        }
        if (clock() < settleUntil) {
            previousSmoothedPower = power
            return
        }
        val error = targetWatts - power
        val scheduled = PowerTable.resistancePerWatt(targetWatts, sample.cadence)
            ?.let { (it * SENSITIVITY).coerceIn(0.002, 0.050) * gain / DEFAULT_KP }
        val kp = scheduled ?: gain
        // Sum e[k] over n original iterations (geometric EMA series), including
        // fractional intervals. With 100 ms input this is the original P term.
        val integratedError = steps * (targetWatts - sample.power) -
            (oldPower - sample.power) * (1 - EMA_ALPHA) * (1 - decay) / EMA_ALPHA
        val p = if (abs(error) < DEADBAND) 0.0 else kp * integratedError
        // D is on measurement, never target. Integrating n old D updates telescopes.
        val d = if (firstDerivative) {
            firstDerivative = false
            0.0
        } else -KD * (power - previousSmoothedPower) / 0.1
        previousSmoothedPower = power
        // This is the old controller's per-iteration stability bound, not the
        // upstream blanket 3 resistance levels/second ramp. Feed-forward bypasses it.
        val next = (currentResistance + (p + d).coerceIn(-3 * steps, 3 * steps)).coerceIn(0.0, 100.0)
        if (next.toInt() != currentResistance.toInt() && !write(next.toInt())) return
        currentResistance = next
    }

    // External meters may report at 1 Hz and use the upstream 3 s expiry.
    // The owner independently checks bike cadence/resistance freshness at 1.5 s.
    private fun fresh(sample: Sample) = clock() - sample.timestamp in 0..sample.staleAfterMs
    private fun write(value: Int): Boolean {
        // A sensor adapter can deliver an immediate echo from inside the write.
        tracker.commanded(value, clock())
        val accepted = runCatching { writeResistance(value) }.getOrDefault(false)
        if (!accepted) { fail("Resistance command failed"); return false }
        return true
    }
    private fun fail(reason: String) { isActive = false; onStandDown(reason) }
}
