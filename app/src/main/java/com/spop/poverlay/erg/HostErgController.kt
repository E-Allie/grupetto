package com.spop.poverlay.erg

import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import timber.log.Timber
import kotlin.math.abs

/**
 * ERG the way it worked before the controller could be asked directly.
 *
 * Polls power over Binder, smooths it with a two-second EMA, seeds resistance
 * from [PowerTable] on a large target change, and trims from there with a PID at
 * 10 Hz. Every constant in it is shaped by the measurement lag that chain
 * introduces -- the loop runs twenty times per time constant of its own input
 * filter -- which is exactly the lag [NativePzafController] does not have.
 *
 * Kept as the fallback for bikes whose controller has no PZAF, and as the
 * comparison for bikes that do. It has a measured baseline; native has to beat
 * it rather than merely replace it.
 */
class HostErgController(private val sensorInterface: SensorInterface) : PowerController, CoroutineScope {

    override val coroutineContext = SupervisorJob() + Dispatchers.Default

    companion object {
        private const val CONTROL_LOOP_INTERVAL_MS = 100L
        private const val CONTROL_LOOP_INTERVAL_SEC = 0.1

        private const val DEFAULT_KP = 0.007
        private const val DEFAULT_KI = 0.0
        private const val DEFAULT_KD = 0.02

        private const val INTEGRAL_MIN = -30.0
        private const val INTEGRAL_MAX = 30.0

        private const val MIN_RESISTANCE = 0.0
        private const val MAX_RESISTANCE = 100.0
        private const val MAX_RESISTANCE_CHANGE_PER_ITERATION = 3.0

        private const val MIN_CADENCE_RPM = 25
        private const val POWER_DEADBAND_WATTS = 3.0

        // EMA alpha for ~2-second time constant at 10Hz: alpha = dt / (tau + dt) = 0.1 / (2.0 + 0.1)
        private const val POWER_EMA_ALPHA = 0.047619047619047616

        private const val MIN_TARGET_POWER = 25
        private const val MAX_TARGET_POWER = 1000

        // Target change large enough to be worth a feed-forward jump rather than
        // letting the proportional term walk there. Also the point at which the
        // integral is discarded, since it describes an operating point we left.
        private const val FEED_FORWARD_TARGET_CHANGE_WATTS = 20

        // Scales the gain scheduled from the power table's local slope. 1.0 would
        // apply the table's whole predicted correction in a single iteration,
        // which the measurement cannot support: smoothed power has a ~2s time
        // constant, or 20 iterations at 10Hz, so a full correction per iteration
        // is applied about twenty times before the reading catches up. Spreading
        // it across one time constant gives 1/20.
        //
        // Measured against the swept table this lands within 10% of the hand
        // tuned DEFAULT_KP at a typical operating point (0.0065 vs 0.0070 at
        // 80rpm/150W), while still varying 5.9x across the map the way the brake
        // actually does.
        private const val ERG_SENSITIVITY = 0.05

        // Bounds on the scheduled gain, so a bad patch of the fitted table cannot
        // produce a wild or inverted response.
        private const val MIN_SCHEDULED_KP = 0.002
        private const val MAX_SCHEDULED_KP = 0.050

        // How long to leave PID alone after a feed-forward jump. Smoothed power
        // has a ~2s time constant, so immediately after the brake moves the
        // reading still describes where it used to be. Acting on that stacks a
        // correction on top of a jump that already fixed the error, which is how
        // a 45% seek turned into 56% and a 51% power overshoot. Roughly one time
        // constant lets the measurement catch up first.
        private const val FEED_FORWARD_SETTLE_MS = 2000L
    }

    private var kp = DEFAULT_KP
    private var ki = DEFAULT_KI
    private var kd = DEFAULT_KD

    private var controlJob: Job? = null
    @Volatile
    private var targetPowerWatts = 100
    @Volatile
    private var active = false

    // PID state
    private var currentResistance = 0.0
    private var integralTerm = 0.0
    private var smoothedPower = 0.0
    private var previousSmoothedPower = 0.0
    private var isFirstIteration = true
    private var isSmoothedPowerInitialized = false

    /**
     * Set whenever the operating point moves far enough that the current
     * resistance is a poor starting guess. Consumed by the next control loop
     * iteration, which seeds resistance from [PowerTable] before running PID.
     */
    private var feedForwardPending = false

    /** Wall clock after which PID may act again following a feed-forward jump. */
    private var feedForwardSettleUntil = 0L

    override fun enable(watts: Int) {
        // FitnessMachineService already refuses SetTargetPower on bikes without a
        // motorised brake; this is the same guard at the other end of the call,
        // so ERG can never spin a control loop that cannot move anything.
        if (!sensorInterface.supportsResistanceControl) {
            Timber.w("ERG requested on a bike without resistance control; ignoring")
            return
        }
        val clamped = watts.coerceIn(MIN_TARGET_POWER, MAX_TARGET_POWER)
        targetPowerWatts = clamped
        resetPidState()
        active = true
        startControlLoop()
        Timber.d("ERG enabled: target=${clamped}W, PID gains: Kp=$kp, Ki=$ki, Kd=$kd")
    }

    override fun disable() {
        if (!active) return
        Timber.d("ERG disabled (was: target=${targetPowerWatts}W)")
        active = false
        stopControlLoop()
    }

    override fun setTarget(watts: Int) {
        val clamped = watts.coerceIn(MIN_TARGET_POWER, MAX_TARGET_POWER)
        if (clamped != targetPowerWatts) {
            val previousTarget = targetPowerWatts
            targetPowerWatts = clamped
            // Reset integral on large target changes to avoid windup overshoot,
            // and let the table place the knob rather than walking there.
            if (abs(clamped - previousTarget) > FEED_FORWARD_TARGET_CHANGE_WATTS) {
                integralTerm = 0.0
                feedForwardPending = true
            }
            Timber.d("Target power set: ${clamped}W")
        }
    }

    override val isActive: Boolean get() = active

    override val targetWatts: Int get() = targetPowerWatts

    private fun resetPidState() {
        feedForwardPending = true
        feedForwardSettleUntil = 0L
        integralTerm = 0.0
        previousSmoothedPower = 0.0
        isFirstIteration = true
        isSmoothedPowerInitialized = false
        smoothedPower = 0.0
    }

    private fun startControlLoop() {
        if (controlJob?.isActive == true) return
        controlJob = launch {
            // Initialize currentResistance from the sensor's current reading
            try {
                currentResistance = sensorInterface.resistance.first().toDouble()
            } catch (_: Exception) {
                currentResistance = 50.0
            }
            Timber.d("PID control loop started, initial resistance: ${currentResistance.toInt()}")

            while (coroutineContext.isActive && active) {
                try {
                    executeControlLoop()
                } catch (e: Exception) {
                    Timber.e(e, "Error in PID control loop iteration")
                }
                delay(CONTROL_LOOP_INTERVAL_MS)
            }
        }
    }

    private fun stopControlLoop() {
        controlJob?.cancel()
        controlJob = null
    }

    private suspend fun executeControlLoop() {
        // Read current power and cadence from sensor flows
        val rawPower = try {
            sensorInterface.power.first().toDouble()
        } catch (_: Exception) {
            return
        }
        val cadence = try {
            sensorInterface.cadence.first().toDouble()
        } catch (_: Exception) {
            return
        }

        // Smooth power with EMA
        if (!isSmoothedPowerInitialized) {
            isSmoothedPowerInitialized = true
            smoothedPower = rawPower
        } else {
            smoothedPower = (rawPower * POWER_EMA_ALPHA) + ((1 - POWER_EMA_ALPHA) * smoothedPower)
        }

        // Low cadence guard: pause PID below 25 RPM, reset integral
        if (cadence < MIN_CADENCE_RPM) {
            integralTerm = 0.0
            Timber.v("Cadence too low (${cadence.toInt()} RPM), PID paused")
            return
        }

        // Feed-forward: jump straight to the resistance the table predicts for
        // this target and cadence, then let PID trim from there. Without a
        // calibrated table this does nothing and the loop behaves as it always has.
        if (feedForwardPending) {
            val predicted = PowerTable.resistanceFor(targetPowerWatts, cadence)
            if (predicted == null) {
                // No answer at this cadence. Usually that means the brake cannot
                // reach the target here at all -- 150W at 35rpm is off the top of
                // the map -- which is exactly what happens while a rider is still
                // spinning up. Staying armed lets the jump land once they reach a
                // cadence the table covers, instead of being spent on the first
                // iteration past the cadence guard and never retried.
                //
                // Give it up only if PID has since arrived on its own; jumping
                // then would disturb a loop that is already holding target.
                if (abs(targetPowerWatts - smoothedPower) < POWER_DEADBAND_WATTS) {
                    feedForwardPending = false
                }
            } else {
                feedForwardPending = false
                val seeded = predicted.coerceIn(MIN_RESISTANCE, MAX_RESISTANCE)
                Timber.d(
                    "ERG feed-forward: target=${targetPowerWatts}W cadence=${cadence.toInt()} " +
                        "-> resistance ${seeded.toInt()}% (was ${currentResistance.toInt()}%)"
                )
                currentResistance = seeded
                sensorInterface.setResistance(seeded.toInt())
                feedForwardSettleUntil = System.currentTimeMillis() + FEED_FORWARD_SETTLE_MS
                return
            }
        }

        // Still waiting for the power reading to describe the post-jump brake.
        // The EMA above keeps converging while this holds; only PID is paused,
        // and the derivative reference is carried along so resuming does not look
        // like a step change to the D term.
        if (System.currentTimeMillis() < feedForwardSettleUntil) {
            previousSmoothedPower = smoothedPower
            Timber.v("ERG settling after feed-forward, PID held")
            return
        }

        val error = targetPowerWatts - smoothedPower
        val inDeadband = abs(error) < POWER_DEADBAND_WATTS

        // P-term. Where the table can supply a local slope, the gain follows what
        // a resistance point is actually worth in watts at this operating point;
        // the eddy brake yields far less near the loose end than the tight end.
        val scheduledKp = PowerTable.resistancePerWatt(targetPowerWatts, cadence)
            ?.let { (it * ERG_SENSITIVITY).coerceIn(MIN_SCHEDULED_KP, MAX_SCHEDULED_KP) }
        val effectiveKp = scheduledKp ?: kp
        val pTerm = effectiveKp * error

        // I-term (only accumulate outside deadband)
        if (!inDeadband) {
            integralTerm += ki * error * CONTROL_LOOP_INTERVAL_SEC
            integralTerm = integralTerm.coerceIn(INTEGRAL_MIN, INTEGRAL_MAX)
        }

        // D-term on measurement (prevents derivative kick on setpoint changes)
        val dTerm = if (isFirstIteration) {
            isFirstIteration = false
            0.0
        } else {
            (-kd * (smoothedPower - previousSmoothedPower)) / CONTROL_LOOP_INTERVAL_SEC
        }
        previousSmoothedPower = smoothedPower

        // Within deadband, only D-term acts (prevents oscillation)
        val output = if (inDeadband) dTerm else pTerm + integralTerm + dTerm

        // Rate-limit resistance change and clamp to valid range
        val resistanceChange = output.coerceIn(
            -MAX_RESISTANCE_CHANGE_PER_ITERATION,
            MAX_RESISTANCE_CHANGE_PER_ITERATION
        )
        val newResistance = (currentResistance + resistanceChange).coerceIn(
            MIN_RESISTANCE,
            MAX_RESISTANCE
        )

        val newResistanceInt = newResistance.toInt()
        if (newResistanceInt != currentResistance.toInt()) {
            sensorInterface.setResistance(newResistanceInt)
            Timber.d(
                "PID: target=$targetPowerWatts, power=${smoothedPower.toInt()} (raw=${rawPower.toInt()}), " +
                    "error=${error.toInt()}, Kp=${"%.4f".format(effectiveKp)}" +
                    (if (scheduledKp != null) "(sched)" else "(fixed)") + ", " +
                    "P=${"%.2f".format(pTerm)}, I=${"%.2f".format(integralTerm)}, " +
                    "D=${"%.2f".format(dTerm)}, out=${"%.2f".format(output)}, resistance=$newResistanceInt%"
            )
        }

        currentResistance = newResistance
    }
}
