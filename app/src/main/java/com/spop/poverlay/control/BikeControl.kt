package com.spop.poverlay.control

import com.spop.poverlay.erg.*
import com.spop.poverlay.sensor.power.ExternalPowerReading
import com.spop.poverlay.sensor.power.freshExternalPower
import com.spop.poverlay.sensor.v2.TitanControl
import com.spop.poverlay.sim.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Topaz identifies the shared tablet; the mainboard platform gates physical control. */
fun supportsBikeControl(isPeloton: Boolean, platform: String?, model: String = ""): Boolean {
    if (!isPeloton) return false
    val machine = platform?.trim()?.lowercase()
    if (machine == "caesar" || machine == "aurora" || machine == "v1" ||
        com.spop.poverlay.util.isTreadPlatform(machine)) return false
    return machine == "titan" || com.spop.poverlay.util.isG700CrossTrainerModel(model)
}

data class BikeSample(val power: Float, val cadence: Float, val resistance: Int, val timestamp: Long)
enum class ControlMode { Manual, Erg, Simulation, Resistance }
data class ControlState(
    val connected: Boolean = false,
    val mode: ControlMode = ControlMode.Manual,
    val targetWatts: Int = 150,
    val targetResistance: Int = 0,
    val targetIncline: Float = 0f,
    val wattsPerShift: Int = 10,
    val externalControl: String? = null,
    val shiftSize: Int = 2,
    val gain: Float = .007f,
    val permissionLostCount: Int = 0,
    val permissionLostClient: String? = null,
    val message: String = "Waiting for Bike+",
    val ergMode: ErgMode = ErgMode.Auto,
    val ergPath: ErgPath = ErgPath.None,
    val nativeAvailable: Boolean = false,
    val capability: String? = null,
    val simulationSettings: SimulationSettings = SimulationSettings(),
    val gear: Int = GearRatios.DEFAULT,
    val simulationDecision: SimulationDecision? = null,
    val suspended: Boolean = false,
    val paused: Boolean = false,
    val externalPowerActive: Boolean = false,
    val owner: String? = null,
    val controlEngaged: Boolean = false
)

/** One FTMS owner and actuator lock shared by local controls, native ERG and software control. */
class BikeControl(
    val supported: Boolean,
    private val clock: () -> Long,
    private val writeResistance: (Int) -> Boolean,
    nativeControl: TitanControl?,
    scope: CoroutineScope? = null
) {
    constructor(supported: Boolean, clock: () -> Long, writeResistance: (Int) -> Boolean) :
        this(supported, clock, writeResistance, null, null)
    private val mutableState = MutableStateFlow(ControlState())
    val state = mutableState.asStateFlow()
    private val ownsScope = scope == null
    private val backendScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val power = ErgCoordinator(nativeControl, writeResistance, clock, this, backendScope, ::suspendControl)
    private var sample: BikeSample? = null
    private var externalPower: ExternalPowerReading? = null
    private var owner: String? = null
    private var engaged = false
    private var paused = false
    private var suspension: String? = null
    private var suspending = false
    private var closed = false
    private var terrain: SimulationParameters? = null
    private var engine = SimulationEngine()
    private var enginePath: ErgPath? = null
    private var decision: SimulationDecision? = null
    private var releaseAt: Long? = null
    private var minimumConfirmed = false
    private var expectedResistance: Int? = null
    private var resistanceCommandAt = 0L
    private var lastCadenceTimestamp: Long? = null

    /** Stable advertised range when changing between native and software regulation. */
    val supportedPowerRange = 25..800
    val supportsSimulation: Boolean get() = supported && state.value.simulationSettings.enabled

    @Synchronized fun setErgMode(mode: ErgMode) {
        if (mode == state.value.ergMode) return
        mutableState.value = state.value.copy(ergMode = mode)
        if (engaged && isPowerMode()) suspendControl("ERG backend changed · tap Resume")
        publish()
    }
    @Synchronized fun setSimulationSettings(settings: SimulationSettings) {
        if (settings == state.value.simulationSettings) return
        mutableState.value = state.value.copy(simulationSettings = settings)
        if (!settings.enabled && state.value.mode == ControlMode.Simulation) stop("Terrain control disabled", false)
        else if (state.value.mode == ControlMode.Simulation && !paused && suspension == null) tickSimulation()
        publish()
    }
    @Synchronized fun useExternalPower(reading: ExternalPowerReading?) {
        val next = reading?.takeIf { freshExternalPower(it, clock()) != null }
        val changed = externalPower?.address != next?.address
        externalPower = next
        if (changed && engaged && isPowerMode()) suspendControl("Power source changed · tap Resume")
        feedPowerSample()
        publish()
    }
    @Synchronized fun tune(shiftSize: Int, gain: Float, wattsPerShift: Int = state.value.wattsPerShift) {
        val bounded = if (gain.isFinite()) gain.coerceIn(.001f, .03f) else .007f
        mutableState.value = state.value.copy(shiftSize = shiftSize.coerceIn(1, 10),
            wattsPerShift = wattsPerShift.coerceIn(1, 50), gain = bounded)
        power.setGain(bounded)
    }

    @Synchronized fun acceptSample(value: BikeSample?) {
        if (!supported || closed) return
        if (value == null || !value.power.isFinite() || value.power < 0 ||
            !value.cadence.isFinite() || value.cadence !in 0f..300f || value.resistance !in 0..100) {
            sample = null
            unavailable("Bike+ disconnected")
            return
        }
        sample = value
        if (!fresh()) { unavailable("Bike+ data is stale"); return }
        if (lastCadenceTimestamp != value.timestamp) {
            engine.sampleCadence(value.cadence.toDouble(), value.timestamp)
            lastCadenceTimestamp = value.timestamp
        }
        mutableState.value = state.value.copy(connected = true,
            targetResistance = if (state.value.mode == ControlMode.Manual) value.resistance else state.value.targetResistance,
            message = if (!state.value.connected && suspension == null) "Ready" else state.value.message)
        if (engaged && !paused && suspension == null) {
            val released = releaseAt
            if (state.value.mode == ControlMode.Simulation && released != null) {
                if (value.resistance == 0) minimumConfirmed = true
                else if (minimumConfirmed || clock() - released >= 1500)
                    suspendControl("Resistance changed while SIM released")
            } else if (state.value.mode == ControlMode.Resistance && expectedResistance != null &&
                value.resistance != expectedResistance && clock() - resistanceCommandAt > 1500) {
                suspendControl("Resistance changed manually or command not acknowledged")
            }
        }
        feedPowerSample()
        publish()
    }
    private fun fresh() = !closed && supported && sample?.let { clock() - it.timestamp in 0..1500 } == true
    private fun isPowerMode() = state.value.mode == ControlMode.Erg || state.value.mode == ControlMode.Simulation
    private fun externalActive() = freshExternalPower(externalPower, clock()) != null
    private fun feedPowerSample() {
        val reading = sample ?: return
        val external = externalPower?.takeIf { freshExternalPower(it, clock()) != null }
        // Bike polls must not turn one external-meter packet into fresh power samples.
        power.sample(external?.watts?.coerceAtLeast(0)?.toFloat() ?: reading.power,
            reading.cadence, reading.resistance,
            external?.let { minOf(reading.timestamp, it.timestamp) } ?: reading.timestamp,
            staleAfterMs = if (external == null) 1500 else 3000)
    }
    private fun unavailable(reason: String) {
        if (engaged) suspendControl(reason) else stop(reason)
        mutableState.value = state.value.copy(connected = false)
    }

    /** Stop/Reset discard targets, but never erase an existing rider override latch. */
    @Synchronized fun stop(reason: String = "Manual control", releaseControl: Boolean = true): Boolean {
        val released = power.disable()
        if (releaseControl && owner != null) {
            mutableState.value = state.value.copy(permissionLostCount = state.value.permissionLostCount + 1,
                permissionLostClient = owner)
            owner = null
        }
        if (!released) {
            if (suspension == null) suspendControl("Could not release native Auto Follow")
            publish()
            return false
        }
        clearSimulation()
        expectedResistance = null
        engaged = false
        paused = false
        mutableState.value = state.value.copy(mode = ControlMode.Manual, externalControl = null,
            targetResistance = sample?.resistance ?: state.value.targetResistance, message = reason)
        publish()
        return true
    }
    @Synchronized fun disconnect(client: String) { if (owner == client) stop("Controller disconnected") }
    @Synchronized fun disconnectTransport(prefix: String) { if (owner?.startsWith(prefix) == true) stop("Controller disconnected") }
    @Synchronized fun close() {
        if (closed) return
        stop("Controller stopped")
        closed = true
        power.close()
        if (ownsScope) backendScope.cancel()
    }
    private fun clearSimulation() {
        engine.stop()
        enginePath = null
        terrain = null
        decision = null
        releaseAt = null
        minimumConfirmed = false
    }
    private fun suspendControl(reason: String) {
        if (suspending || closed) return
        val wasSuspended = suspension != null
        suspension = reason
        paused = true
        suspending = true
        try { power.disable() } finally { suspending = false }
        if (state.value.mode == ControlMode.Simulation) decision = engine.suspend(reason)
        if (!wasSuspended && owner != null)
            mutableState.value = state.value.copy(permissionLostCount = state.value.permissionLostCount + 1,
                permissionLostClient = owner)
        mutableState.value = state.value.copy(message = reason, externalControl = null)
        publish()
    }
    @Synchronized fun pauseLocally() { if (engaged) suspendControl("Stopped by rider") }
    /** Remote Start/Reset never call this local-only latch release. */
    @Synchronized fun resumeLocally(): Boolean {
        if (!fresh()) return false
        if (!power.disable()) return false
        suspension = null
        paused = false
        val accepted = resumeMode()
        publish()
        return accepted && suspension == null
    }
    private fun resumeMode(): Boolean = when (state.value.mode) {
        ControlMode.Erg -> startPower(state.value.targetWatts)
        ControlMode.Simulation -> {
            engine.stop(); enginePath = null; releaseAt = null; minimumConfirmed = false
            tickSimulation()
            suspension == null
        }
        ControlMode.Resistance -> issueResistance(state.value.targetResistance)
        ControlMode.Manual -> true
    }
    private fun selectMode(mode: ControlMode, engage: Boolean = true): Boolean {
        if (state.value.mode != mode || paused) {
            if ((engaged || engage) && !power.disable()) return false
            clearSimulation()
            expectedResistance = null
        }
        if (suspension != null) return false
        paused = false
        engaged = engaged || engage
        mutableState.value = state.value.copy(mode = mode, message = "${mode.name} active")
        return true
    }

    @Synchronized fun localErg(watts: Int): Boolean {
        if (!fresh() || suspension != null || watts !in supportedPowerRange) return false
        if (!selectMode(ControlMode.Erg)) return false
        mutableState.value = state.value.copy(targetWatts = watts, externalControl = null)
        val accepted = startPower(watts)
        publish()
        return accepted
    }
    @Synchronized fun localSimulation(grade: Float = state.value.targetIncline): Boolean {
        if (!fresh() || suspension != null || !supportsSimulation || !grade.isFinite() || grade !in -327.68f..327.67f) return false
        if (!selectMode(ControlMode.Simulation)) return false
        terrain = SimulationParameters(0.0, grade.toDouble(), .004, .51)
        mutableState.value = state.value.copy(targetIncline = grade, externalControl = null)
        tickSimulation()
        publish()
        return suspension == null
    }
    @Synchronized fun localManual(): Boolean {
        if (!fresh()) return false
        if (suspension != null) { stop("Manual · tap Resume to allow control", false); return true }
        return localResistance(sample!!.resistance)
    }
    @Synchronized fun localResistance(level: Int): Boolean {
        if (!fresh() || suspension != null || level !in 0..100) return false
        if (!selectMode(ControlMode.Resistance)) return false
        mutableState.value = state.value.copy(targetResistance = level, message = "Manual active", externalControl = null)
        val accepted = issueResistance(level)
        publish()
        return accepted
    }
    @Synchronized fun shift(direction: Int) {
        if (!fresh()) return
        val step = direction.coerceIn(-1, 1)
        if (step == 0) return
        when (state.value.mode) {
            ControlMode.Simulation -> {
                mutableState.value = state.value.copy(gear = (state.value.gear + step).coerceIn(1, GearRatios.COUNT), externalControl = null)
                if (!paused && suspension == null) tickSimulation()
            }
            ControlMode.Erg -> {
                val target = (state.value.targetWatts + step * state.value.wattsPerShift).coerceIn(supportedPowerRange)
                mutableState.value = state.value.copy(targetWatts = target, externalControl = null)
                if (!paused && suspension == null) startPower(target)
            }
            ControlMode.Manual, ControlMode.Resistance -> {
                if (paused || suspension != null) return
                val base = if (state.value.mode == ControlMode.Manual) sample!!.resistance else state.value.targetResistance
                localResistance((base + step * state.value.shiftSize).coerceIn(0, 100))
            }
        }
        publish()
    }
    private fun startPower(watts: Int): Boolean {
        feedPowerSample()
        // Feeding feedback can synchronously detect a knob change or a failed command echo.
        if (suspension != null || paused || !fresh()) return false
        val accepted = power.enable(watts, state.value.ergMode, externalActive())
        if (!accepted && suspension == null) suspendControl("Power control unavailable")
        return accepted && suspension == null
    }
    /** Explicit targets, including recovery while stopped, have no app resistance slew limit. */
    private fun issueResistance(level: Int): Boolean {
        // A real new target deliberately takes over from any previously running native loop.
        if (!power.disable(forceNative = true) || suspension != null) return false
        val accepted = runCatching { writeResistance(level.coerceIn(0, 100)) }.getOrDefault(false)
        if (!accepted) { suspendControl("Resistance command failed"); return false }
        expectedResistance = level
        resistanceCommandAt = clock()
        return true
    }

    @Synchronized fun tick() {
        if (closed || !supported) return
        if (externalPower != null && !externalActive()) useExternalPower(null)
        if (!fresh()) { if (state.value.connected) unavailable("Bike+ data is stale"); return }
        feedPowerSample()
        if (suspension == null && !paused) {
            if (engaged && state.value.mode == ControlMode.Simulation) {
                if (enginePath == null && !power.state.value.active) power.tick()
                tickSimulation()
            }
            if (suspension == null) power.tick()
        }
        publish()
    }
    private fun tickSimulation() {
        if (!engaged || paused || suspension != null || state.value.mode != ControlMode.Simulation) return
        val parameters = terrain ?: return
        val path = power.preferredPath(state.value.ergMode, externalActive()) ?: run {
            mutableState.value = state.value.copy(message = "Checking native Auto Follow")
            return
        }
        if (path == ErgPath.None) { suspendControl("Native Auto Follow unavailable · choose Auto or Software"); return }
        val now = clock()
        if (path != enginePath) {
            if (!power.disable() || suspension != null) return
            engine = SimulationEngine(if (path == ErgPath.Host) SimulationPolicy(
                minWatts = 25, maxWatts = 1000, reenterWatts = 30, stopRpm = 25.0, restartRpm = 30.0
            ) else SimulationPolicy())
            sample?.let { engine.sampleCadence(it.cadence.toDouble(), it.timestamp) }
            engine.begin(parameters, now)
            enginePath = path
        }
        engine.updateTerrain(parameters)
        val feedbackFresh = sample?.let { now - it.timestamp in 0 until engine.policy.staleAfterMs } == true
        decision = engine.evaluate(now, state.value.gear, state.value.simulationSettings,
            automaticResumeAllowed = minimumConfirmed && feedbackFresh)
        mutableState.value = state.value.copy(message = decision!!.reason)
        when (val action = decision!!.action) {
            SimulationAction.Release -> { minimumConfirmed = false; releaseAt = now; issueResistance(0) }
            SimulationAction.Disable -> suspendControl(decision!!.reason)
            is SimulationAction.Target -> { releaseAt = null; startPower(action.watts) }
            SimulationAction.None -> Unit
        }
    }

    data class Reply(val result: Int, val status: ByteArray? = null)
    @Synchronized fun procedure(client: String, bytes: ByteArray): Reply {
        val op = bytes.firstOrNull()?.toInt()?.and(255) ?: return Reply(3)
        val size = when (op) { 0, 1, 7 -> 1; 8 -> 2; 4, 5 -> 3; 0x11 -> 7; else -> return Reply(2) }
        if (bytes.size != size) return Reply(3)
        if (!supported) return Reply(2)
        if (!fresh()) return Reply(4)
        if (op == 0) {
            if (owner != null && owner != client && engaged) return Reply(5)
            if (owner != client && !engaged) {
                clearSimulation()
                mutableState.value = state.value.copy(mode = ControlMode.Manual, externalControl = null)
            }
            owner = client
            publish()
            return Reply(1)
        }
        if (owner != client || (suspension != null && op != 1 && op != 8)) return Reply(5)
        fun signed(index: Int) = ((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8)).toShort().toInt()
        val reply = when (op) {
            1 -> if (stop("Reset", false)) Reply(1, byteArrayOf(1)) else Reply(4)
            7 -> {
                if (paused) { paused = false; if (!resumeMode()) return Reply(4) }
                Reply(1, byteArrayOf(4))
            }
            8 -> {
                when (bytes[1].toInt()) {
                    1 -> if (!stop("Stopped", false)) return Reply(4)
                    2 -> {
                        if (!power.disable()) return Reply(4)
                        engine.stop(); enginePath = null; decision = null; paused = true
                        mutableState.value = state.value.copy(message = "Paused", externalControl = null)
                    }
                    else -> return Reply(3)
                }
                Reply(1, byteArrayOf(2, bytes[1]))
            }
            4 -> {
                val level = signed(1)
                if (level !in 0..1000 || level % 10 != 0) Reply(3)
                else if (!localResistance(level / 10)) Reply(4)
                else Reply(1, byteArrayOf(7) + bytes.copyOfRange(1, 3))
            }
            5 -> {
                val watts = signed(1)
                if (watts !in supportedPowerRange) Reply(3)
                else if (!localErg(watts)) Reply(4)
                else Reply(1, byteArrayOf(8) + bytes.copyOfRange(1, 3))
            }
            else -> {
                if (!supportsSimulation) return Reply(2)
                val parameters = SimulationParameters.parse(bytes) ?: return Reply(3)
                val initializationOnly = !engaged && parameters.windSpeedMps == 0.0 && parameters.gradePercent == 0.0 &&
                    parameters.crr == 0.0 && parameters.cw == 0.0
                if (!selectMode(ControlMode.Simulation, !initializationOnly)) return Reply(4)
                terrain = parameters
                mutableState.value = state.value.copy(targetIncline = parameters.gradePercent.toFloat())
                if (!initializationOnly) tickSimulation()
                if (suspension != null) Reply(4) else Reply(1, byteArrayOf(0x12) + bytes.copyOfRange(1, 7))
            }
        }
        if (reply.result == 1 && engaged && (op in listOf(4, 5, 0x11) || (op == 7 && !paused)))
            mutableState.value = state.value.copy(externalControl = if (client.startsWith("dircon:")) "DirCon" else "Bluetooth")
        publish()
        return reply
    }
    private fun publish() {
        val backend = power.state.value
        mutableState.value = state.value.copy(ergPath = backend.path, nativeAvailable = backend.nativeAvailable,
            capability = backend.capability, simulationDecision = decision, suspended = suspension != null,
            paused = paused, owner = owner, controlEngaged = engaged, externalPowerActive = externalActive())
    }
}
