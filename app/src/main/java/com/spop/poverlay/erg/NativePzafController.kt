package com.spop.poverlay.erg

import com.spop.poverlay.sensor.v2.PzafStatus
import com.spop.poverlay.sensor.v2.TitanControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private val onStandDown: (status: Int) -> Unit
) : PowerController {

    @Volatile
    override var isActive = false
        private set

    @Volatile
    override var targetWatts = 0
        private set

    private var watchJob: Job? = null

    override fun enable(watts: Int) {
        val clamped = watts.coerceIn(TitanControl.PZAF_POWER_RANGE)
        targetWatts = clamped
        applyConfiguration()
        control.setPowerZoneAutoFollow(clamped)
        isActive = true
        Timber.i("PZAF enabled at %dW", clamped)
        startWatching()
    }

    override fun setTarget(watts: Int) {
        val clamped = watts.coerceIn(TitanControl.PZAF_POWER_RANGE)
        if (clamped == targetWatts && isActive) return
        targetWatts = clamped
        // The same transaction sets and enables, so this re-arms as well as
        // retargets. pzaf_mode_set_power_sp resets the controller's PID whenever
        // the setpoint moves by more than half a watt, so there is no windup to
        // clear from here.
        control.setPowerZoneAutoFollow(clamped)
        Timber.d("PZAF target %dW", clamped)
        if (!isActive) {
            isActive = true
            startWatching()
        }
    }

    override fun disable() {
        watchJob?.cancel()
        watchJob = null
        if (!isActive) {
            // Still send it. pzaf_disable_control returns immediately when the
            // mode is already off, and this path is also the teardown for a
            // process that may not know what it left running.
            control.disablePowerZoneAutoFollow()
            return
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
        control.setPzafRampUpRate(DEFAULT_RAMP_UP)
        control.setPzafRampDownRate(DEFAULT_RAMP_DOWN)
        control.setPzafMaxResistance(DEFAULT_MAX_RESISTANCE)
        control.setPzafMinUpdateRpm(DEFAULT_MIN_UPDATE_RPM)
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
        watchJob = scope.launch {
            var armed = false
            val armDeadline = System.currentTimeMillis() + ARM_GRACE_MS

            val ending = control.status.first { packet ->
                val status = packet.pzafStatus
                when {
                    PzafStatus.isEnabled(status) -> {
                        if (!armed) {
                            armed = true
                            Timber.i(
                                "PZAF armed: %s, controller settled on %s",
                                PzafStatus.name(status), packet.pzafSummary()
                            )
                        }
                        false
                    }
                    armed -> true
                    System.currentTimeMillis() >= armDeadline -> true
                    else -> false
                }
            }

            val status = ending.pzafStatus
            if (armed) {
                Timber.w("PZAF stood down on its own: %s", PzafStatus.name(status))
            } else {
                Timber.w(
                    "PZAF never armed within %dms, last status %s",
                    ARM_GRACE_MS, PzafStatus.name(status)
                )
            }
            isActive = false
            watchJob = null
            onStandDown(status)
        }
    }

    companion object {
        const val DEFAULT_RAMP_UP = 50
        const val DEFAULT_RAMP_DOWN = 8
        const val DEFAULT_MAX_RESISTANCE = 100
        const val DEFAULT_MIN_UPDATE_RPM = 40

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
