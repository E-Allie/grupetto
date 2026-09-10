package com.spop.poverlay.sim

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Experimental defaults for scheduling terrain resistance. */
data class SimulationPolicy(
    val minWatts: Int = 15,
    val maxWatts: Int = 800,
    val reenterWatts: Int = 20,
    val stopRpm: Double = 30.0,
    val restartRpm: Double = 35.0,
    val stableCadenceMs: Long = 400,
    val staleAfterMs: Long = 1000,
    val writeIntervalMs: Long = 500,
    val deadbandWatts: Int = 3,
    val riseWattsPerSecond: Double = 100.0,
    val restartRiseWattsPerSecond: Double = 20.0,
    val restartRampMs: Long = 5000,
    // Until knob intent while PZAF is disabled has been validated on this bike.
    val automaticReentry: Boolean = false
) {
    init {
        require(minWatts > 0 && maxWatts >= reenterWatts && reenterWatts > minWatts)
        require(stopRpm.isFinite() && restartRpm.isFinite() && stopRpm > 0 && restartRpm > stopRpm)
        require(stableCadenceMs >= 0 && staleAfterMs > stableCadenceMs)
        require(writeIntervalMs > 0 && deadbandWatts > 0)
        require(riseWattsPerSecond.isFinite() && riseWattsPerSecond > 0)
        require(restartRiseWattsPerSecond.isFinite() && restartRiseWattsPerSecond > 0 && restartRampMs >= 0)
    }
}

enum class SimulationPhase { Inactive, WaitingForCadence, Holding, LowLoad, Suspended }

sealed interface SimulationAction {
    object None : SimulationAction
    /** Disable regulation before commanding minimum resistance, once on entry. */
    object Release : SimulationAction
    /** Suspend regulation without overriding a rider's chosen resistance. */
    object Disable : SimulationAction
    data class Target(val watts: Int) : SimulationAction
}

data class SimulationDecision(
    val phase: SimulationPhase,
    val action: SimulationAction = SimulationAction.None,
    val load: RoadLoad? = null,
    val requestedWatts: Int? = null,
    val capped: Boolean = false,
    val resumeRequired: Boolean = false,
    val reason: String,
    val powerLimitWatts: Int? = null,
    val resumeTargetWatts: Int? = null,
    val previewCadenceRpm: Double? = null
)

/**
 * Deterministic SIM policy. The caller serializes methods and supplies monotonic
 * milliseconds. Evaluating the newest inputs never creates a queue of targets.
 * No Android, coroutines, global shifter, transport or actuator dependencies.
 */
class SimulationEngine(val policy: SimulationPolicy = SimulationPolicy()) {
    var phase = SimulationPhase.Inactive
        private set
    private var terrain: SimulationParameters? = null
    private var cadence: Double? = null
    private var cadenceAt: Long? = null
    private var stableSince: Long? = null
    private var beganAt = 0L
    private var lastRequested: Int? = null
    private var lastWriteAt: Long? = null
    private var urgentDecrease = false
    private var hasHeld = false
    private var released = false
    private var suspension: String? = null
    private var canAutoReenter = false
    private var holdingSince = 0L
    private var riseBlockedUntil = 0L

    fun begin(parameters: SimulationParameters, nowMs: Long) {
        require(parameters.isValid)
        terrain = parameters
        if (phase != SimulationPhase.Inactive) return
        beganAt = nowMs
        phase = SimulationPhase.WaitingForCadence
        lastRequested = null
        lastWriteAt = null
        released = false
        hasHeld = false
    }

    fun updateTerrain(parameters: SimulationParameters) {
        require(parameters.isValid)
        terrain = parameters
    }

    fun sampleCadence(rpm: Double, nowMs: Long) {
        if (cadenceAt?.let { nowMs - it >= policy.staleAfterMs || nowMs < it } == true) stableSince = null
        val previous = cadence
        if (previous != null && previous - rpm >= 3.0) riseBlockedUntil = nowMs + 1000
        urgentDecrease = urgentDecrease || (previous != null && rpm < previous - 5.0)
        cadence = rpm
        cadenceAt = nowMs
        if (rpm.isFinite() && rpm >= policy.restartRpm && rpm <= 300.0) {
            if (stableSince == null) stableSince = nowMs
        } else stableSince = null
    }

    fun stop() {
        phase = SimulationPhase.Inactive
        terrain = null
        suspension = null
        lastRequested = null
        lastWriteAt = null
        released = false
        hasHeld = false
        urgentDecrease = false
        riseBlockedUntil = 0L
    }

    fun suspend(reason: String): SimulationDecision {
        val needsDisable = phase != SimulationPhase.Suspended
        phase = SimulationPhase.Suspended
        suspension = reason
        lastRequested = null
        lastWriteAt = null
        return SimulationDecision(phase, if (needsDisable) SimulationAction.Disable else SimulationAction.None,
            resumeRequired = true, reason = reason)
    }

    /** Deliberate rider action. Freshness and RPM are still checked on evaluation. */
    fun resume(nowMs: Long) {
        if (terrain == null) return
        phase = SimulationPhase.WaitingForCadence
        suspension = null
        hasHeld = false
        released = false
        lastRequested = null
        lastWriteAt = null
        beganAt = nowMs
    }

    fun evaluate(nowMs: Long, gear: Int, settings: SimulationSettings,
                 automaticResumeAllowed: Boolean = true): SimulationDecision {
        canAutoReenter = (settings.autoResumeAfterPause || policy.automaticReentry) && automaticResumeAllowed
        val limit = min(policy.maxWatts, settings.maxSimWatts)
        val decision = evaluateControl(nowMs, gear, settings, limit)
        val load = decision.load ?: return decision.copy(powerLimitWatts = limit)
        val previewRpm = if (decision.phase == SimulationPhase.WaitingForCadence || decision.phase == SimulationPhase.LowLoad)
            cadence!!.takeIf { it >= policy.restartRpm } ?: 80.0 else null
        val preview = previewRpm?.let { RoadLoadModel.calculate(it, gear, terrain!!, settings).watts }
        return decision.copy(powerLimitWatts = limit, capped = load.watts > limit,
            resumeTargetWatts = preview?.let { if (it < policy.minWatts) 0 else it.coerceAtMost(limit.toDouble()).roundToInt() },
            previewCadenceRpm = previewRpm)
    }

    private fun evaluateControl(nowMs: Long, gear: Int, settings: SimulationSettings, limit: Int): SimulationDecision {
        if (phase == SimulationPhase.Inactive) return SimulationDecision(phase, reason = "inactive")
        suspension?.let { return SimulationDecision(phase, resumeRequired = true, reason = it) }
        val sampleAt = cadenceAt
        if (sampleAt == null) {
            return if (nowMs - beganAt >= policy.staleAfterMs) suspend("No cadence data")
            else release(SimulationPhase.WaitingForCadence, null, "Waiting for cadence")
        }
        if (nowMs < sampleAt || nowMs - sampleAt >= policy.staleAfterMs) return suspend("Cadence data lost")
        val rpm = cadence!!
        if (!rpm.isFinite() || rpm !in 0.0..300.0) return suspend("Invalid cadence")
        val load = RoadLoadModel.calculate(rpm, gear, terrain!!, settings)
        if (rpm < policy.stopRpm ||
            (phase != SimulationPhase.Holding && (rpm < policy.restartRpm ||
                stableSince == null || nowMs - stableSince!! < policy.stableCadenceMs))) {
            return release(SimulationPhase.WaitingForCadence, load, "Waiting for steady pedaling")
        }
        if (load.watts < policy.minWatts ||
            (phase == SimulationPhase.LowLoad && load.watts < policy.reenterWatts)) {
            return release(SimulationPhase.LowLoad, load, "Minimum resistance")
        }
        if (released && hasHeld && !canAutoReenter) {
            return SimulationDecision(phase, load = load, resumeRequired = true,
                reason = "Tap to resume terrain resistance")
        }

        val entering = phase != SimulationPhase.Holding
        if (entering) holdingSince = nowMs
        phase = SimulationPhase.Holding
        hasHeld = true
        released = false
        val target = load.watts.coerceIn(policy.minWatts.toDouble(), limit.toDouble()).roundToInt()
        val previous = lastRequested
        val elapsed = lastWriteAt?.let { (nowMs - it).coerceAtLeast(0) }
        var reason = "target"
        var next: Int? = null
        when {
            previous == null || entering -> next = min(target, policy.reenterWatts)
            // Lowering the personal ceiling takes effect even for a 1 W change,
            // without waiting for the ordinary deadband or scheduling interval.
            previous > limit -> next = target
            abs(target - previous) < policy.deadbandWatts -> reason = "deadband"
            elapsed!! < policy.writeIntervalMs && !(urgentDecrease && target < previous) -> reason = "rate limit"
            target > previous && nowMs < riseBlockedUntil -> reason = "cadence falling"
            target > previous -> {
                val budgetMs = min(elapsed, policy.writeIntervalMs)
                val restartMs = (holdingSince + policy.restartRampMs - (nowMs - budgetMs))
                    .coerceIn(0, budgetMs)
                val rise = ((policy.restartRiseWattsPerSecond * restartMs +
                    policy.riseWattsPerSecond * (budgetMs - restartMs)) / 1000.0).toInt()
                next = min(target, previous + rise)
                if (next == previous) { next = null; reason = "rise limit" }
            }
            else -> next = target
        }
        if (next != null) {
            lastRequested = next
            lastWriteAt = nowMs
            urgentDecrease = false
        }
        return SimulationDecision(phase, next?.let { SimulationAction.Target(it) } ?: SimulationAction.None,
            load, lastRequested, load.watts > policy.maxWatts, reason = reason)
    }

    private fun release(phase: SimulationPhase, load: RoadLoad?, reason: String): SimulationDecision {
        this.phase = phase
        val action = if (released) SimulationAction.None else SimulationAction.Release
        released = true
        lastRequested = null
        lastWriteAt = null
        return SimulationDecision(phase, action, load, resumeRequired = hasHeld && !canAutoReenter,
            reason = reason)
    }
}
