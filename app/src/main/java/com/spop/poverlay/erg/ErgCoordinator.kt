package com.spop.poverlay.erg

import com.spop.poverlay.sensor.v2.TitanControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The backend is not an FTMS owner; BikeControl owns sessions and rider latches. */
enum class ErgPath { None, Native, Host }

data class ErgState(
    val active: Boolean = false,
    val targetWatts: Int = 0,
    val path: ErgPath = ErgPath.None,
    val nativeAvailable: Boolean = false,
    val capability: String? = null,
    val suspended: Boolean = false,
    val standDownReason: String? = null
)

/**
 * Serializes software and native actuation with the trainer's command lock.
 * Capability resolution and arm acknowledgement run from [tick], so an obsolete
 * async probe can never re-enable a brake after [disable] returns.
 */
class ErgCoordinator(
    private val nativeControl: TitanControl?,
    writeResistance: (Int) -> Boolean,
    private val clock: () -> Long,
    private val commandLock: Any,
    scope: CoroutineScope,
    private val onStandDown: (String) -> Unit
) {
    private val mutableState = MutableStateFlow(ErgState())
    val state: StateFlow<ErgState> = mutableState.asStateFlow()
    private val host = HostErgController(writeResistance, clock, commandLock, ::standDown)
    private val upstream = UpstreamSoftwareErgController(writeResistance, clock, commandLock, ::standDown)
    private var algorithm = SoftwareErgAlgorithm.FirmwareTable
    fun setSoftwareAlgorithm(value: SoftwareErgAlgorithm) = synchronized(commandLock) {
        host.disable(); upstream.disable(); algorithm = value
    }
    private val native = nativeControl?.let { NativePzafController(it, clock, ::standDown) }
    private val probe = PzafProbe(nativeControl, clock)
    private var pending: Pair<ErgMode, Boolean>? = null
    private var closed = false
    private var reportingStandDown = false
    private val completion: kotlinx.coroutines.DisposableHandle? = scope.coroutineContext[Job]?.invokeOnCompletion { close() }

    fun sample(power: Float, cadence: Float, resistance: Int, timestamp: Long,
               staleAfterMs: Long = HostErgController.FRESHNESS_MS, nativePower: Float = power,
               bikeTimestamp: Long = timestamp, external: Boolean = false) = synchronized(commandLock) {
        if (!closed) {
            host.sample(power, cadence, resistance, timestamp, staleAfterMs)
            upstream.sample(power, cadence, resistance, timestamp, staleAfterMs, nativePower, bikeTimestamp, external)
        }
    }

    fun setGain(gain: Float) = synchronized(commandLock) { host.setGain(gain); upstream.setGain(gain) }

    /** null means that native capability is still being checked. */
    fun preferredPath(mode: ErgMode, forceHost: Boolean = false): ErgPath? = synchronized(commandLock) {
        if (closed) return@synchronized null
        if (forceHost || mode == ErgMode.Host) return@synchronized ErgPath.Host
        probe.start()
        publishCapability()
        when (probe.result) {
            null -> null
            is PzafCapability.Supported -> ErgPath.Native
            is PzafCapability.Unsupported -> if (mode == ErgMode.Native) ErgPath.None else ErgPath.Host
        }
    }

    /** Accepted while capability is pending; [state] reports the eventual result. */
    fun enable(watts: Int, mode: ErgMode, forceHost: Boolean = false): Boolean = synchronized(commandLock) {
        if (closed) return@synchronized false
        val previousPath = state.value.path
        if (state.value.active) {
            val desired = preferredPath(mode, forceHost)
            if (desired == previousPath) return@synchronized setTarget(watts)
        }
        host.disable(); upstream.disable()
        // Release native ownership even when switching directly to software.
        if (!releaseNative(force = true)) {
            standDown("Could not release native Auto Follow")
            return@synchronized false
        }
        pending = mode to forceHost
        mutableState.value = state.value.copy(active = false, targetWatts = watts, path = ErgPath.None,
            suspended = false, standDownReason = null)
        activateIfReady()
    }

    fun setTarget(watts: Int): Boolean = synchronized(commandLock) {
        if (closed || state.value.suspended || (!state.value.active && pending == null)) return@synchronized false
        if (pending != null) {
            mutableState.value = state.value.copy(targetWatts = watts)
            return@synchronized true
        }
        val accepted = when (state.value.path) {
            ErgPath.Host -> if (algorithm == SoftwareErgAlgorithm.FirmwareTable) host.setTarget(watts) else upstream.setTarget(watts)
            ErgPath.Native -> native?.setTarget(watts) == true
            ErgPath.None -> false
        }
        if (accepted) publishActive()
        accepted
    }

    fun tick() = synchronized(commandLock) {
        if (closed) return@synchronized
        probe.tick()
        publishCapability()
        if (pending != null) activateIfReady()
        when (state.value.path) {
            ErgPath.Host -> if (algorithm == SoftwareErgAlgorithm.FirmwareTable) host.tick() else upstream.tick()
            ErgPath.Native -> native?.tick()
            ErgPath.None -> Unit
        }
        if (!state.value.suspended) publishActive()
    }

    fun disable(forceNative: Boolean = false): Boolean = synchronized(commandLock) {
        pending = null
        probe.cancel()
        host.disable(); upstream.disable()
        val released = releaseNative(forceNative)
        mutableState.value = state.value.copy(active = false, path = ErgPath.None)
        if (!released) standDown("Could not release native Auto Follow")
        released
    }

    fun close(): Unit = synchronized(commandLock) {
        if (closed) return@synchronized
        closed = true
        disable()
        completion?.dispose()
    }

    private fun activateIfReady(): Boolean {
        val request = pending ?: return false
        val path = preferredPath(request.first, request.second) ?: return true
        pending = null
        if (path == ErgPath.None) {
            standDown("Native Auto Follow unavailable: ${(probe.result as? PzafCapability.Unsupported)?.reason}")
            return false
        }
        if (path == ErgPath.Host) probe.cancel()
        val accepted = when (path) {
            ErgPath.Host -> if (algorithm == SoftwareErgAlgorithm.FirmwareTable) host.enable(state.value.targetWatts) else upstream.enable(state.value.targetWatts)
            ErgPath.Native -> native?.enable(state.value.targetWatts) == true
            ErgPath.None -> false
        }
        if (accepted) {
            mutableState.value = state.value.copy(path = path)
            publishActive()
        }
        return accepted
    }

    private fun publishActive() {
        val active = when (state.value.path) {
            ErgPath.Native -> native?.isActive == true
            ErgPath.Host -> if (algorithm == SoftwareErgAlgorithm.FirmwareTable) host.isActive else upstream.isActive
            ErgPath.None -> false
        }
        val target = when (state.value.path) {
            ErgPath.Native -> native?.targetWatts ?: state.value.targetWatts
            ErgPath.Host -> if (algorithm == SoftwareErgAlgorithm.FirmwareTable) host.targetWatts else upstream.targetWatts
            ErgPath.None -> state.value.targetWatts
        }
        mutableState.value = state.value.copy(active = active, targetWatts = target)
    }

    private fun publishCapability() {
        val capability = probe.result ?: return
        mutableState.value = state.value.copy(nativeAvailable = capability is PzafCapability.Supported,
            capability = when (capability) {
                is PzafCapability.Supported -> "Native Auto Follow, controller ${capability.firmware}"
                is PzafCapability.Unsupported -> "Software ERG: ${capability.reason}"
            })
    }

    private fun releaseNative(force: Boolean = false): Boolean {
        if (native == null) return true
        val packet = nativeControl?.latestStatusReading?.packet
        // Legacy controllers have no native watt loop to release; their service
        // may correctly reject the unsupported disable transaction.
        if (!native.ownsControl && packet != null &&
            (packet.isLegacyPzafMarker || packet.firmwareMajor < PzafProbe.MIN_FIRMWARE_MAJOR)) return true
        return native.disable(force)
    }

    private fun standDown(reason: String) {
        pending = null
        probe.cancel()
        host.disable(); upstream.disable()
        releaseNative()
        mutableState.value = state.value.copy(active = false, path = ErgPath.None, suspended = true, standDownReason = reason)
        // BikeControl may synchronously stop/disable in this callback. A rejected
        // release must remain visible without recursively reporting itself.
        if (!reportingStandDown) {
            reportingStandDown = true
            try { onStandDown(reason) } finally { reportingStandDown = false }
        }
    }
}
