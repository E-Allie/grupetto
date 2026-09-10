package com.spop.poverlay.erg

import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.sensor.v2.PzafStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import kotlin.coroutines.CoroutineContext

enum class ErgPath { None, Native, Host }

data class ErgState(
    val active: Boolean = false,
    val targetWatts: Int = 0,
    val path: ErgPath = ErgPath.None,
    val standDownReason: String? = null,
    val capability: String? = null,
    val nativeAvailable: Boolean = false,
    val suspended: Boolean = false
)

/**
 * The sole power/resistance actuator boundary. Slow capability resolution happens
 * outside [commandLock]; the final eligibility check and hardware writes share
 * that lock with disable. A completed disable therefore cannot be undone by an
 * older request, even when its probe or Binder write was in flight.
 */
class ErgCoordinator(
    private val sensorInterface: SensorInterface,
    private val modeProvider: () -> ErgMode,
    hostController: PowerController? = null,
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default,
    private val capabilityProbe: suspend () -> PzafCapability = { PzafProbe(sensorInterface.titanControl).run() }
) : PowerController, CoroutineScope {
    /** Shared with the trainer mode coordinator so Stop and SIM ticks are ordered. */
    val commandLock = Any()
    private val hostController: PowerController = hostController ?: HostErgController(
        sensorInterface, commandLock, ::onHostStandDown, coroutineContext)
    private val titanControl = sensorInterface.titanControl
    private val nativeController = titanControl?.let {
        NativePzafController(it, this, ::onNativeStandDown, commandLock)
    }
    private val mutableState = MutableStateFlow(ErgState())
    val state: StateFlow<ErgState> = mutableState.asStateFlow()

    @Volatile var onStandDown: ((status: Int) -> Unit)? = null
    @Volatile var onPathChanged: ((path: ErgPath) -> Unit)? = null

    private val probeLock = Mutex()
    private var probe: Deferred<PzafCapability>? = null
    private var resolved: PowerController? = null
    private var generation = 0L
    private var targetJob: Job? = null

    override val isActive: Boolean get() = state.value.active
    override val targetWatts: Int get() = state.value.targetWatts

    init { launch { probeLock.withLock { capability() } } }

    override fun enable(watts: Int) { request(watts, fresh = true) }
    override fun setTarget(watts: Int) { request(watts, fresh = false) }

    /** SIM uses the same selected backend and automatic fallback as ERG. */
    fun simulationTarget(watts: Int): Boolean = request(watts, fresh = true)

    /** Null while Auto is probing; used to choose SIM's backend-specific limits. */
    fun preferredPath(): ErgPath? = when (modeProvider()) {
        ErgMode.Host -> ErgPath.Host
        ErgMode.Native -> if (nativeController != null) ErgPath.Native else ErgPath.Host
        ErgMode.Auto -> if (state.value.capability == null) null else
            if (state.value.nativeAvailable) ErgPath.Native else ErgPath.Host
    }

    fun resume() {
        synchronized(commandLock) {
            if (targetWatts <= 0) return
            clearSuspension()
            enable(targetWatts)
        }
    }

    fun toggle() {
        if (isActive) suspendControl("Stopped by rider") else resume()
    }

    /** Only a deliberate rider action should call this, never periodic targets. */
    fun clearSuspension() = synchronized(commandLock) {
        mutableState.update { it.copy(suspended = false, standDownReason = null) }
    }

    private fun request(watts: Int, fresh: Boolean): Boolean {
        val job: Job
        synchronized(commandLock) {
            if (state.value.suspended || !sensorInterface.supportsResistanceControl) return false
            // Updating a target is not permission to arm an inactive loop.
            if (!fresh && !isActive && targetJob?.isActive != true) return false
            val ticket = ++generation
            targetJob?.cancel()
            mutableState.update { it.copy(targetWatts = watts, standDownReason = null) }
            job = launch(start = CoroutineStart.LAZY) {
                try {
                    val mode = modeProvider()
                    val capability = if (mode == ErgMode.Auto) {
                        probeLock.withLock { capability() }
                    } else null
                    val currentJob = currentCoroutineContext()[Job]!!
                    synchronized(commandLock) write@ {
                        if (ticket != generation || state.value.suspended || !currentJob.isActive) return@write
                        val controller = when {
                            mode == ErgMode.Host -> hostController
                            mode == ErgMode.Native -> nativeController ?: hostController
                            capability is PzafCapability.Supported -> nativeController ?: hostController
                            else -> hostController
                        }
                        if (controller !== resolved) {
                            resolved?.disable()
                            resolved = controller
                        }
                        // A target in the same path should not reapply configuration/reset the watcher.
                        if (controller.isActive) controller.setTarget(watts) else controller.enable(watts)
                        val path = if (controller === nativeController) ErgPath.Native else ErgPath.Host
                        val changed = state.value.path != path
                        mutableState.update { it.copy(active = controller.isActive,
                            targetWatts = controller.targetWatts, path = path) }
                        if (changed) launch { onPathChanged?.invoke(path) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    synchronized(commandLock) {
                        if (ticket == generation) {
                            Timber.e(error, "Power command failed")
                            suspendControl(error.message ?: "Power command failed")
                            launch { onStandDown?.invoke(PzafStatus.DISABLED_BY_ERROR) }
                        }
                    }
                }
            }
            targetJob = job
        }
        job.start()
        return true
    }

    private fun invalidate() {
        ++generation
        targetJob?.cancel()
        targetJob = null
    }

    override fun disable() = synchronized(commandLock) {
        invalidate()
        disableLoops()
        mutableState.update { it.copy(active = false) }
    }

    /** Disable first, then issue direct resistance in the same ordering boundary. */
    fun setResistance(percent: Int): Boolean = synchronized(commandLock) {
        if (state.value.suspended || !sensorInterface.supportsResistanceControl) return@synchronized false
        disable()
        sensorInterface.setResistance(percent.coerceIn(0, 100))
        true
    }

    fun suspendControl(reason: String) = synchronized(commandLock) {
        disable()
        mutableState.update { it.copy(suspended = true, standDownReason = reason) }
    }

    private fun disableLoops() {
        // An exception in one backend must not prevent trying the other disable.
        runCatching { hostController.disable() }.onFailure { Timber.e(it, "Host disable failed") }
        runCatching { nativeController?.disable() }.onFailure { Timber.e(it, "Native disable failed") }
        runCatching { titanControl?.disablePowerZoneAutoFollow() }.onFailure { Timber.e(it, "Titan disable failed") }
    }

    private fun onNativeStandDown(status: Int) = synchronized(commandLock) {
        invalidate()
        mutableState.update { it.copy(active = false, suspended = true, standDownReason = PzafStatus.name(status)) }
        launch { onStandDown?.invoke(status) }
    }

    private fun onHostStandDown(reason: String) = synchronized(commandLock) {
        suspendControl(reason)
        launch { onStandDown?.invoke(if (reason == "Resistance changed outside app control")
            PzafStatus.DISABLED_BY_KNOB else PzafStatus.DISABLED_BY_ERROR) }
    }

    /** Protected by probeLock. Probing cannot arm the brake. */
    private suspend fun capability(): PzafCapability {
        // Cache for this application session, including missing native status.
        // Retrying on every SIM target would repeatedly stall a working host
        // fallback for the probe timeout. Restarting the app permits a new probe.
        probe?.let { return it.await() }
        val started = async {
            try { capabilityProbe() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "Native probe failed; host control remains available")
                PzafCapability.Unsupported("native probe failed", transient = true)
            }
        }
        probe = started
        val result = started.await()
        val description = when (result) {
            is PzafCapability.Supported -> "native PZAF, controller firmware ${result.firmware}"
            is PzafCapability.Unsupported -> "host PID: ${result.reason}"
        }
        mutableState.update { it.copy(capability = description, nativeAvailable = result is PzafCapability.Supported) }
        return result
    }
}
