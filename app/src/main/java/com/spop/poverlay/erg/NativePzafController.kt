package com.spop.poverlay.erg

import com.spop.poverlay.sensor.v2.PzafStatus
import com.spop.poverlay.sensor.v2.TitanControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * ERG by handing the target to the controller instead of chasing it from here.
 *
 * `pzaf_mode_handler` runs beside the power calculation, in the task that
 * produced the number. Each tick it reads resistance from motor position, holds
 * without correcting while measured power is inside the target's +/-2 W band,
 * and otherwise generates a torque-versus-resistance curve for the *current*
 * cadence out of a 609-point surface baked into the firmware, inverts it for both
 * the target and the measured power, and PIDs on the difference in resistance
 * space. None of that is reachable from Android; all of it is better placed than
 * anything the host can do, because the host's own copy of the power reading has
 * been through a 33ms service poll, a 200ms Binder poll and a two-second EMA
 * before the loop sees it.
 *
 * The practical difference is where the writes go. [HostErgController] issues up
 * to ten set-resistance transactions a second. This issues one per target change.
 *
 * What it costs: no access to the gains, a hard 800 W ceiling, and six ways for
 * the controller to drop the mode underneath us. The last is what [watchJob] is
 * for.
 */
class NativePzafController(
    private val control: TitanControl,
    private val scope: CoroutineScope,
    /**
     * Called when PZAF stops holding without us asking it to. The rider turned
     * the knob, the bike started homing, the controller errored, or nobody
     * pedalled for a minute. Never called from [disable].
     */
    private val onStandDown: (status: Int) -> Unit,
    private val commandLock: Any = Any()
) : PowerController {

    @Volatile
    override var isActive = false
        private set

    @Volatile
    override var targetWatts = 0
        private set

    private var watchJob: Job? = null
    private var watchGeneration = 0L

    override fun enable(watts: Int) = synchronized(commandLock) {
        val clamped = watts.coerceIn(TitanControl.PZAF_POWER_RANGE)
        targetWatts = clamped
        applyConfiguration()
        control.setPowerZoneAutoFollow(clamped)
        isActive = true
        Timber.i("PZAF enabled at %dW", clamped)
        startWatching()
    }

    override fun setTarget(watts: Int) = synchronized(commandLock) {
        if (!isActive) return@synchronized
        val clamped = watts.coerceIn(TitanControl.PZAF_POWER_RANGE)
        if (clamped == targetWatts) return@synchronized
        targetWatts = clamped
        // The transaction also enables: eligibility must be checked before it.
        // pzaf_mode_set_power_sp resets the controller's PID whenever
        // the setpoint moves by more than half a watt, so there is no windup to
        // clear from here.
        control.setPowerZoneAutoFollow(clamped)
        Timber.d("PZAF target %dW", clamped)
    }

    override fun disable() = synchronized(commandLock) {
        ++watchGeneration
        watchJob?.cancel()
        watchJob = null
        if (!isActive) {
            // Still send it. pzaf_disable_control returns immediately when the
            // mode is already off, and this path is also the teardown for a
            // process that may not know what it left running.
            control.disablePowerZoneAutoFollow()
            return@synchronized
        }
        isActive = false
        control.disablePowerZoneAutoFollow()
        Timber.i("PZAF disabled (was holding %dW)", targetWatts)
    }

    /**
     * Peloton's app sends these once at workout start and then only target
     * changes, so this mirrors that rather than resending per target.
     *
     * The values are the firmware's own initialised defaults, not something tuned
     * here. Ramp down is the one worth knowing about: AffernetService accepts
     * 5..100 but `pzaf_set_ramp_down_rate` in 5.08 clamps to 5..10, so anything
     * above 10 quietly becomes 10. Eight is inside both. Whatever the controller
     * actually settled on comes back at packet offsets 248..251.
     */
    private fun applyConfiguration() {
        check(control.setPzafRampUpRate(DEFAULT_RAMP_UP)) { "PZAF ramp-up configuration failed" }
        check(control.setPzafRampDownRate(DEFAULT_RAMP_DOWN)) { "PZAF ramp-down configuration failed" }
        check(control.setPzafMaxResistance(DEFAULT_MAX_RESISTANCE)) { "PZAF resistance configuration failed" }
        check(control.setPzafMinUpdateRpm(DEFAULT_MIN_UPDATE_RPM)) { "PZAF cadence configuration failed" }
    }

    /**
     * Watches offset 247 for a disable we did not ask for.
     *
     * Statuses are ignored until either an enabled one arrives or the grace
     * period expires, because the packet in flight when the enable was sent still
     * carries the old status. After that, any status below 20 ends the watch --
     * the six named disable reasons, plus the unnamed 7 that
     * `pzaf_no_usage_timeout` passes, so nothing here switches on the individual
     * values.
     */
    private fun startWatching() {
        watchJob?.cancel()
        val ticket = ++watchGeneration
        watchJob = scope.launch {
            val armed = withTimeoutOrNull(ARM_GRACE_MS) {
                control.status.first { PzafStatus.isEnabled(it.pzafStatus) }
            }
            val status = if (armed != null) {
                Timber.i("PZAF armed: %s", armed.pzafSummary())
                control.status.first { !PzafStatus.isEnabled(it.pzafStatus) }.pzafStatus
            } else {
                Timber.w("PZAF never armed within %dms", ARM_GRACE_MS)
                PzafStatus.DISABLED_BY_NO_COMMS
            }
            synchronized(commandLock) {
                if (ticket != watchGeneration || !isActive) return@synchronized
                isActive = false
                watchJob = null
                // An arm timeout is a failure, even if firmware enabled without a status echo.
                runCatching { control.disablePowerZoneAutoFollow() }
                    .onFailure { Timber.e(it, "Disable after native stand-down failed") }
                onStandDown(status)
            }
        }
    }

    companion object {
        const val DEFAULT_RAMP_UP = 50
        const val DEFAULT_RAMP_DOWN = 8
        const val DEFAULT_MAX_RESISTANCE = 100

        /**
         * The cadence below which the controller stops correcting and holds its
         * last resistance. 30 is a floor rather than a preference:
         * `BikePZAFUtil` accepts 30..120 and `BikeHWCommunicator` drops anything
         * outside that without sending, so a lower number is not a lower
         * threshold -- it is no write at all, leaving whatever the previous
         * caller set. Measured: a write of 25 logged in `PelotonServiceHelper`
         * and never reached `BikeHWCommunicator`, and the controller stayed on
         * the 40 the probe had left there.
         *
         * The controller has a second, hardcoded floor at 10 RPM where it resets
         * its PID; 10..30 is not reachable through this transaction.
         */
        const val DEFAULT_MIN_UPDATE_RPM = 30

        /**
         * How long to allow between the enable going out and the first enabled
         * status coming back. Two Binder polls plus the service's own 33ms cycle
         * would be under half a second; this is loose enough to survive a slow
         * frame and tight enough that a failed arm is reported while the rider is
         * still wondering why nothing happened.
         */
        const val ARM_GRACE_MS = 3_000L
    }
}
