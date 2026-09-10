package com.spop.poverlay.sim

import com.spop.poverlay.erg.ErgCoordinator
import com.spop.poverlay.erg.ErgPath
import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import kotlin.coroutines.CoroutineContext

data class TrainerState(
    val mode: TrainerMode = TrainerMode.Idle,
    val owner: String? = null,
    val paused: Boolean = false,
    val suspension: String? = null,
    val simulation: SimulationDecision? = null,
    val controlEngaged: Boolean = false
)

/** Shared FTMS command handler and mode owner for both transports and the overlay. */
class TrainerController(
    private val sensor: SensorInterface,
    private val power: ErgCoordinator,
    private val settingsProvider: () -> SimulationSettings,
    private val gearProvider: () -> Int = { VirtualGears.gear.value },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default
) : CoroutineScope {
    private val session = ControlSession()
    private val lock = power.commandLock
    private var engine = SimulationEngine()
    private var enginePath: ErgPath? = null
    private var terrain: SimulationParameters? = null
    private var cadence: Pair<Double, Long>? = null
    private var settings = settingsProvider()
    private var ergTarget: Int? = null
    private var resistanceTarget: Int? = null
    private var decision: SimulationDecision? = null
    private var releaseRequestedAt: Long? = null
    private var minimumConfirmed = false
    private var resistanceAt: Long? = null
    private val mutableState = MutableStateFlow(TrainerState())
    val state: StateFlow<TrainerState> = mutableState.asStateFlow()

    // Stable common range while the rider can switch ERG backends without reconnecting.
    val supportedPowerRange = 25..800
    val supportsSimulation: Boolean get() = sensor.supportsResistanceControl && settingsProvider().enabled

    init {
        VirtualGears.setProfile(settings.gearing)
        launch {
            sensor.requestedResistance.catch {
                synchronized(lock) {
                    if (session.mode == TrainerMode.Simulation) suspendControl("Resistance feedback lost")
                }
            }.collect { target ->
                synchronized(lock) {
                    val now = nowMs()
                    resistanceAt = now
                    val releasedAt = releaseRequestedAt
                    if (releasedAt != null && session.mode == TrainerMode.Simulation &&
                        session.suspension == null && !session.paused) {
                        if (!target.isFinite() || target !in 0f..100f) suspendControl("Invalid resistance feedback")
                        else if (target == 0f) minimumConfirmed = true
                        else if (minimumConfirmed || now - releasedAt >= 1500)
                            suspendControl("Resistance changed while SIM released")
                    }
                }
            }
        }
        launch {
            sensor.cadence.collect { rpm ->
                synchronized(lock) {
                    val now = nowMs()
                    cadence = rpm.toDouble() to now
                    engine.sampleCadence(rpm.toDouble(), now)
                }
            }
        }
        launch {
            while (isActive) {
                synchronized(lock) {
                    try { tick() } catch (error: Exception) {
                        Timber.e(error, "Trainer control failed")
                        suspendControl("Trainer control failed")
                    }
                }
                delay(100)
            }
        }
    }

    /** Results are FTMS values: success 1, unsupported 2, invalid 3, failed 4, denied 5. */
    fun command(client: String, bytes: ByteArray?): Int = synchronized(lock) {
        val opcode = bytes?.firstOrNull()?.toInt()?.and(255) ?: return@synchronized INVALID
        val length = when (opcode) { 0, 1, 7 -> 1; 4, 5 -> 3; 8 -> 2; 17 -> 7; else -> return@synchronized UNSUPPORTED }
        if (bytes.size != length) return@synchronized INVALID
        if (opcode == 0) {
            val previous = session.owner
            val accepted = session.requestControl(client)
            if (accepted && previous != client) {
                // Discard any initialization-only SIM work belonging to the old
                // reservation. This cannot run while a real target is engaged.
                clearSessionState(clearTargets = true)
            }
            publish()
            return@synchronized if (accepted) SUCCESS else DENIED
        }
        if (session.owner != client) return@synchronized DENIED
        if (power.state.value.suspended && session.suspension == null) {
            session.suspend(power.state.value.standDownReason ?: "Controller stopped")
        }
        // Stop/Reset remain usable while suspended; routine remote traffic cannot clear it.
        if (session.suspension != null && opcode != 1 && opcode != 8) return@synchronized DENIED
        val raw = if (length == 3) ((bytes[1].toInt() and 255) or ((bytes[2].toInt() and 255) shl 8)).toShort().toInt() else 0
        if (opcode in listOf(4, 5, 17) && !sensor.supportsResistanceControl) return@synchronized UNSUPPORTED
        val result = when (opcode) {
            1 -> { stop(clearTargets = true); SUCCESS }
            7 -> { resumeRemote(); SUCCESS }
            8 -> when (bytes[1].toInt()) {
                1 -> { stop(clearTargets = true); SUCCESS }
                2 -> { power.disable(); engine.stop(); session.pause(); decision = null; SUCCESS }
                else -> INVALID
            }
            4 -> if (raw !in 0..1000) INVALID else {
                select(TrainerMode.Resistance)
                resistanceTarget = raw / 10
                if (power.setResistance(resistanceTarget!!)) SUCCESS else DENIED
            }
            5 -> if (raw !in supportedPowerRange) INVALID else {
                select(TrainerMode.Erg)
                ergTarget = raw
                power.enable(raw)
                if (power.state.value.suspended) DENIED else SUCCESS
            }
            17 -> {
                val parameters = SimulationParameters.parse(bytes) ?: return@synchronized INVALID
                if (!supportsSimulation) UNSUPPORTED else {
                    val initializationOnly = parameters.windSpeedMps == 0.0 && parameters.gradePercent == 0.0 &&
                        parameters.crr == 0.0 && parameters.cw == 0.0
                    select(TrainerMode.Simulation, engage = !initializationOnly)
                    terrain = parameters
                    engine.updateTerrain(parameters)
                    tick()
                    if (session.suspension != null) FAILED else SUCCESS
                }
            }
            else -> UNSUPPORTED
        }
        publish()
        result
    }

    private fun select(mode: TrainerMode, engage: Boolean = true) {
        if (session.mode != mode || session.paused) {
            if (session.engaged || engage) power.disable()
            engine.stop()
            enginePath = null
            decision = null
            releaseRequestedAt = null
        }
        session.select(mode, engage)
    }

    private fun stop(clearTargets: Boolean) {
        power.disable()
        clearSessionState(clearTargets)
    }

    private fun clearSessionState(clearTargets: Boolean) {
        engine.stop()
        session.reset()
        enginePath = null
        decision = null
        releaseRequestedAt = null
        if (clearTargets) { terrain = null; ergTarget = null; resistanceTarget = null }
    }

    private fun resumeRemote() {
        if (session.paused && session.resume()) resumeMode()
    }

    fun toggle() = synchronized(lock) {
        if (session.suspension != null || session.paused || decision?.resumeRequired == true) {
            power.clearSuspension()
            if (session.resumeLocally()) resumeMode()
        } else if (session.mode != TrainerMode.Idle) suspendControl("Stopped by rider")
        publish()
    }

    private fun resumeMode() {
        when (session.mode) {
            TrainerMode.Erg -> ergTarget?.let { power.enable(it) }
            TrainerMode.Resistance -> resistanceTarget?.let { power.setResistance(it) }
            TrainerMode.Simulation -> {
                releaseRequestedAt = null
                enginePath = null
                engine.stop()
                tick()
            }
            TrainerMode.Idle -> Unit
        }
    }

    fun disconnected(client: String) = synchronized(lock) {
        if (session.disconnected(client)) stop(clearTargets = true)
        publish()
    }

    /** Application/transport shutdown; old connections and derived work lose control. */
    fun closeSession() = synchronized(lock) {
        session.close()
        stop(clearTargets = true)
        publish()
    }

    private fun suspendControl(reason: String) {
        session.suspend(reason)
        power.suspendControl(reason)
        if (session.mode == TrainerMode.Simulation) decision = engine.suspend(reason)
        publish()
    }

    private fun tick() {
        val latestSettings = settingsProvider()
        if (latestSettings != settings) {
            settings = latestSettings
            VirtualGears.setProfile(settings.gearing)
            if (!settings.enabled && session.mode == TrainerMode.Simulation) stop(clearTargets = true)
        }
        if (power.state.value.suspended && session.suspension == null) {
            session.suspend(power.state.value.standDownReason ?: "Controller stopped")
            if (session.mode == TrainerMode.Simulation) decision = engine.suspend(session.suspension!!)
        }
        if (session.mode != TrainerMode.Simulation || !session.engaged || session.paused || session.suspension != null) {
            publish()
            return
        }
        val parameters = terrain ?: return
        val path = power.preferredPath() ?: return
        val now = nowMs()
        if (path != enginePath) {
            power.disable()
            engine = SimulationEngine(if (path == ErgPath.Host) SimulationPolicy(
                minWatts = 25, maxWatts = 1000, reenterWatts = 30, stopRpm = 25.0, restartRpm = 30.0
            ) else SimulationPolicy())
            cadence?.let { engine.sampleCadence(it.first, it.second) }
            engine.begin(parameters, now)
            enginePath = path
        }
        engine.updateTerrain(parameters)
        val feedbackFresh = resistanceAt?.let { now >= it && now - it < engine.policy.staleAfterMs } == true
        decision = engine.evaluate(now, gearProvider(), settings,
            automaticResumeAllowed = minimumConfirmed && feedbackFresh)
        when (val action = decision!!.action) {
            SimulationAction.Release -> {
                minimumConfirmed = false
                releaseRequestedAt = now
                power.setResistance(0)
            }
            SimulationAction.Disable -> suspendControl(decision!!.reason)
            is SimulationAction.Target -> {
                releaseRequestedAt = null
                if (!power.simulationTarget(action.watts)) suspendControl("Control unavailable")
            }
            SimulationAction.None -> Unit
        }
        publish()
    }

    private fun publish() {
        val value = TrainerState(session.mode, session.owner, session.paused, session.suspension,
            decision, session.engaged)
        if (value != mutableState.value) {
            mutableState.value = value
        }
    }

    companion object {
        const val SUCCESS = 1
        const val UNSUPPORTED = 2
        const val INVALID = 3
        const val FAILED = 4
        const val DENIED = 5
    }
}
