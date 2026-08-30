package com.spop.poverlay.erg

import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.sensor.v2.PzafStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** Which loop is holding the target. */
enum class ErgPath { None, Native, Host }

/**
 * Everything the overlay and the configuration page need to know about ERG.
 *
 * [targetWatts] survives a stand-down on purpose. It is what the resume control
 * re-arms, and it is the whole reason a rider who bumps the knob mid-interval
 * does not have to wait for the next block for their app to send a new target.
 */
data class ErgState(
    val active: Boolean = false,
    val targetWatts: Int = 0,
    val path: ErgPath = ErgPath.None,
    /** Non-null when the controller dropped the mode without being asked to. */
    val standDownReason: String? = null,
    /** What the probe concluded, for the configuration page. */
    val capability: String? = null
)

/**
 * Picks an ERG implementation and keeps the rider's target across its failures.
 *
 * Native PZAF is the default where the bike can do it, because the controller's
 * loop sees power without the 33ms service poll, the 200ms Binder poll and the
 * two-second EMA that the host loop has to compensate for. [PzafProbe] decides
 * whether that is this bike, by writing a PZAF configuration value and watching
 * for it to come back in the status packet.
 *
 * The awkward part is that the controller can stop on its own -- knob, homing,
 * calibration, low power, error, or sixty seconds without pedalling -- and
 * `pzaf_no_usage_timeout` measures the *rider*, not us, so there is no watchdog
 * that hands the brake back if this process dies. Two consequences shape this
 * class:
 *
 *  - [disable] is synchronous and unconditional. It runs on the caller's thread
 *    and sends the PZAF disable whether or not we believe PZAF was running, so
 *    every teardown path really does release the brake.
 *  - A stand-down is never re-armed automatically. The knob is the rider's only
 *    control once software has stopped, and anything that grabs the brake back
 *    fights that. [resume] is how the target comes back, and it is deliberately a
 *    thing a person does.
 */
class ErgCoordinator(
    private val sensorInterface: SensorInterface,
    private val modeProvider: () -> ErgMode,
    private val hostController: HostErgController = HostErgController(sensorInterface)
) : PowerController, CoroutineScope {

    override val coroutineContext = SupervisorJob() + Dispatchers.Default

    private val titanControl = sensorInterface.titanControl

    private val nativeController = titanControl?.let {
        NativePzafController(it, this, ::onNativeStandDown)
    }

    private val mutableState = MutableStateFlow(ErgState())
    val state: StateFlow<ErgState> = mutableState.asStateFlow()

    /**
     * Told about a stand-down so FTMS can drop its training status and notify.
     * Set by [com.spop.poverlay.ble.FitnessMachineService].
     */
    @Volatile
    var onStandDown: ((status: Int) -> Unit)? = null

    /** Serialises path resolution and target changes against each other. */
    private val lock = Mutex()

    private var probe: Deferred<PzafCapability>? = null
    private var resolved: PowerController? = null

    override val isActive: Boolean get() = mutableState.value.active

    override val targetWatts: Int get() = mutableState.value.targetWatts

    init {
        // Answer the capability question at startup rather than at the moment a
        // rider is waiting for a target to take effect. Costs one configuration
        // write and no brake movement.
        launch { lock.withLock { capability() } }
    }

    override fun enable(watts: Int) = request(watts, fresh = true)

    override fun setTarget(watts: Int) = request(watts, fresh = false)

    /**
     * Re-arm at the last target. The overlay's resume control, and the only way
     * back after a stand-down short of the controller app sending a new target.
     */
    fun resume() {
        val watts = mutableState.value.targetWatts
        if (watts <= 0) {
            Timber.d("ERG resume ignored: no target has been set this session")
            return
        }
        Timber.i("ERG resumed by rider at %dW", watts)
        enable(watts)
    }

    /** What the overlay button does: stop if holding, otherwise pick the target back up. */
    fun toggle() {
        if (isActive) disable() else resume()
    }

    private fun request(watts: Int, fresh: Boolean) {
        // Recorded before the controller is even chosen: a target the rider asked
        // for is worth remembering whether or not it can be honoured right now.
        mutableState.update {
            it.copy(targetWatts = watts, standDownReason = null)
        }
        launch {
            lock.withLock {
                val controller = select()
                if (fresh || !controller.isActive) {
                    controller.enable(watts)
                } else {
                    controller.setTarget(watts)
                }
                mutableState.update {
                    it.copy(
                        active = controller.isActive,
                        targetWatts = controller.targetWatts,
                        path = if (controller === nativeController) ErgPath.Native else ErgPath.Host
                    )
                }
            }
        }
    }

    /**
     * Synchronous, unconditional, and safe to call from anywhere.
     *
     * Both loops are stopped, and the PZAF disable goes out on any bike that has
     * a controller regardless of which loop we thought was running. Native PZAF
     * and host ERG have separate state and separate disable paths; disabling one
     * has never disabled the other. This is also the last thing that runs on the
     * way out of the process, so it does not get to be asynchronous.
     */
    override fun disable() {
        hostController.disable()
        nativeController?.disable()
        titanControl?.disablePowerZoneAutoFollow()
        mutableState.update { it.copy(active = false) }
    }

    private fun onNativeStandDown(status: Int) {
        val reason = PzafStatus.name(status)
        Timber.w("ERG standing down: %s. Target %dW kept for resume.", reason, targetWatts)
        mutableState.update { it.copy(active = false, standDownReason = reason) }
        onStandDown?.invoke(status)
    }

    /** Must be called under [lock]. */
    private suspend fun select(): PowerController {
        val mode = modeProvider()

        val controller = when (mode) {
            ErgMode.Host -> hostController
            ErgMode.Native -> nativeController ?: hostController.also {
                Timber.w("ERG mode is Native but this bike has no Titan controller; using host")
            }
            ErgMode.Auto -> when (capability()) {
                is PzafCapability.Supported -> nativeController ?: hostController
                is PzafCapability.Unsupported -> hostController
            }
        }

        if (controller !== resolved) {
            // Switching paths mid-session would otherwise leave the old one
            // holding the brake, and only one of them can have it.
            resolved?.disable()
            resolved = controller
            Timber.i(
                "ERG using %s loop (mode %s)",
                if (controller === nativeController) "native PZAF" else "host PID", mode
            )
        }
        return controller
    }

    /** Must be called under [lock]. */
    private suspend fun capability(): PzafCapability {
        probe?.let { pending ->
            val result = pending.await()
            if (result !is PzafCapability.Unsupported || !result.transient) return result
            Timber.i("PZAF probe retrying: %s", result.reason)
        }
        val started = async { PzafProbe(titanControl).run() }
        probe = started
        val result = started.await()
        val description = when (result) {
            is PzafCapability.Supported -> "native PZAF, controller firmware ${result.firmware}"
            is PzafCapability.Unsupported -> "host PID: ${result.reason}"
        }
        Timber.i("ERG capability: %s", description)
        mutableState.update { it.copy(capability = description) }
        return result
    }
}
