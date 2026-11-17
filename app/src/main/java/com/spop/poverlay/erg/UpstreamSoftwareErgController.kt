package com.spop.poverlay.erg

import com.spop.poverlay.control.BikeSample
import com.spop.poverlay.control.CadenceHistory
import com.spop.poverlay.control.PowerModel
import kotlin.math.abs
import kotlin.math.roundToInt

/** Modeled software power regulation; the coordinator retains exclusive actuator ownership. */
class UpstreamSoftwareErgController(
    private val writeResistance: (Int) -> Boolean,
    private val clock: () -> Long,
    private val commandLock: Any,
    private val onStandDown: (String) -> Unit,
) {
    private var sample: BikeSample? = null
    private var powerTime = Long.MIN_VALUE
    private var lastPowerTime = Long.MIN_VALUE
    private var expiry = 1500L
    private var externalWatts: Float? = null
    private var rampRate = RAMP_RATE_DEFAULT
    private var gain = .007f
    var targetWatts = 0
        private set
    var isActive = false
        private set
    private var filteredPower: Float? = null
    private var requestedResistance = 0f
    private var expectedResistance: Int? = null
    private var commandTime = 0L
    private var ackTimeoutMs = ACK_TIMEOUT_MS
    private var previousTick = 0L
    // Feed-forward: jump toward the level the power model predicts for a new ERG target,
    // then hold off integral feedback until the motor and the reported power settle.
    val powerModel = PowerModel()
    private var feedForwardTarget: Int? = null
    private var feedForwardUntil = 0L
    private var feedForwardLevel = 0f
    // The settle window starts when the bike reports the modeled level, not when it was
    // commanded: on long jumps the motor runs slower than the command rate.
    private var feedForwardArrivalPending = false
    private var feedForwardDeadline = 0L
    // Smooth ramp toward the modeled level: one level per 100 ms tick, so the motor turns
    // continuously instead of moving in bursts between write/echo round trips.
    private var rampLevel: Float? = null
    private var rampUp = true
    private var rampExtreme: Int? = null
    // Readings one level against the ramp, counted per new sample: a single boundary blip is not a knob move.
    private var againstSamples = 0
    private var againstSampleTime = Long.MIN_VALUE
    private var rampTime = 0L
    private var rampOffSince = 0L
    private var steadyResistance = -1
    private var steadySince = 0L
    // Cadence smoothed over a few seconds: the rider's cadence wobbles right at an interval
    // change, and one rpm moves the modeled level by up to a whole step near 350 W.
    private var cadenceAverage: Float? = null
    private var cadenceAverageTime = 0L
    // Since when the rider has been continuously below the model's range (but still pedaling):
    // after LOW_CADENCE_FORGET_MS the old average no longer describes them.
    private var belowModelSince: Long? = null
    // Set when that average was forgotten: the next rise into the model's range is a fresh spin-up.
    private var cadenceForgotten = false
    private val cadenceHistory = CadenceHistory()
    // When the rider (re)started pedaling. While they spin up the cadence average is still low:
    // on Oct 31 a jump sized for a 55 rpm average put out ~285 W at 75 rpm. So a request for more
    // power waits at the current level until SPIN_UP_MS of pedaling, then jumps for the real cadence.
    // Pedaling starts at PEDALING_START_RPM and only stops below PEDALING_STOP_RPM, so a dip or
    // low-cadence riding does not restart the window.
    private var pedaling = false
    private var pedalingSince = 0L
    // The wait ends early once the rider's cadence has been steady (SPIN_UP_STEADY_RPM over
    // SPIN_UP_STEADY_MS, after at least SPIN_UP_MIN_MS of pedaling): on Nov 2 the rider was at a
    // steady 92 rpm 3 s after starting but the fixed 15 s wait held 165 W back another 9 s.
    private var spinUpDone = false
    private val spinUpSamples = ArrayDeque<Pair<Long, Float>>()
    private var waitingTarget: Int? = null
    private var waitSince = 0L
    private var targetChangedAt = 0L

    /**
     * Debug trace of what ERG control sees and decides: controller commands, target changes
     * (with the cadence and modeled level used), ramp/settle progress, every resistance write
     * with its reason, stops, and a 1 s snapshot while a mode is active. Null (the default)
     * costs nothing: Kotlin skips building the message on a null safe call.
     */
    @Volatile var trace: ((String) -> Unit)? = null
    private var lastSnapshot = 0L
    // Last feedback (trim) values, for the trace; cleared while a modeled jump owns the level.
    private var lastFiltered: Float? = null
    private var lastError: Float? = null
    private var lastBand: Float? = null

    fun setGain(value: Float) = synchronized(commandLock) {
        if (value.isFinite()) gain = value.coerceIn(.001f, .03f)
    }

    fun setRampRate(value: Int): Int = synchronized(commandLock) {
        rampRate = value.coerceIn(RAMP_RATE_MIN, RAMP_RATE_MAX)
        rampRate
    }

    fun sample(power: Float, cadence: Float, resistance: Int, timestamp: Long,
               staleAfterMs: Long, nativePower: Float = power, bikeTimestamp: Long = timestamp,
               external: Boolean = false) = synchronized(commandLock) {
        if (!power.isFinite() || power < 0 || !nativePower.isFinite() || nativePower < 0 ||
            !cadence.isFinite() || cadence < 0 || resistance !in 0..100) {
            sample = null
            if (isActive) stop("Invalid ERG feedback")
            return@synchronized
        }
        val previousTime = sample?.timestamp
        val reading = BikeSample(nativePower, cadence, resistance, bikeTimestamp)
        if (previousTime == null || bikeTimestamp - previousTime !in 0..1500) {
            pedaling = false
            cadenceAverage = null
            belowModelSince = null
            cadenceForgotten = false
            steadyResistance = -1
            cadenceHistory.clear()
        }
        sample = reading
        powerTime = timestamp
        expiry = staleAfterMs
        externalWatts = power.takeIf { external }
        if (previousTime != bikeTimestamp && clock() - bikeTimestamp in 0..1500) {
            learnPowerModel(reading)
            averageCadence(reading)
            cadenceHistory.record(bikeTimestamp, cadence)
        }
    }

    private fun fresh(reading: BikeSample) =
        clock() - reading.timestamp in 0..1500 && clock() - powerTime in 0..expiry

    fun enable(watts: Int): Boolean = synchronized(commandLock) {
        val reading = sample ?: return@synchronized false
        if (!fresh(reading)) return@synchronized false
        clearFeedForward()
        isActive = true
        targetWatts = watts.coerceIn(25, 1000)
        requestedResistance = reading.resistance.toFloat()
        expectedResistance = reading.resistance
        previousTick = clock()
        commandTime = previousTick
        ackTimeoutMs = ACK_TIMEOUT_MS
        filteredPower = null
        lastPowerTime = Long.MIN_VALUE
        true
    }

    fun setTarget(watts: Int): Boolean = synchronized(commandLock) {
        if (!isActive) return@synchronized false
        targetWatts = watts.coerceIn(25, 1000)
        true
    }

    fun disable() = synchronized(commandLock) {
        isActive = false
        clearFeedForward()
        expectedResistance = null
    }

    private fun writeLevel(level: Int): Boolean {
        val accepted = runCatching { writeResistance(level) }.getOrDefault(false)
        if (!accepted) stop("Resistance command failed")
        return accepted
    }

    private fun stop(reason: String) {
        disable()
        onStandDown(reason)
    }

    fun tick() = synchronized(commandLock) { regulate() }

    private fun regulate() {
        if (!isActive) return
        val reading = sample
        if (reading == null || !fresh(reading)) { stop("Power or cadence data lost"); return }
        val newSample = powerTime != lastPowerTime
        if (!newSample && rampLevel == null) return
        lastPowerTime = powerTime
        if (newSample && clock() - lastSnapshot >= SNAPSHOT_MS) {
            lastSnapshot = clock()
            trace?.invoke("snap mode=Erg target=$targetWatts power=${f1(reading.power)}" +
                " ext=$externalWatts filt=${lastFiltered?.let(::f1)} cad=${f1(reading.cadence)}" +
                " cadAvg=${cadenceAverage?.let(::f1)} level=${reading.resistance} req=${f1(requestedResistance)}" +
                " err=${lastError?.let(::f1)} band=${lastBand?.let(::f1)} ff=${feedForwardActive()} ramp=${rampLevel?.let(::f1)}")
        }
        if (rampLevel != null) { rampTick(reading); return }
        val dt = ((clock() - previousTick) / 1000f).coerceIn(0f, .4f)
        previousTick = clock()
        val expected = expectedResistance
        if (expected != null && reading.resistance != expected) {
            // Allow the binder target echo to catch up. Do not keep moving the motor
            // against a knob override or a mainboard that ignored a write.
            if (clock() - commandTime > ackTimeoutMs) stop("Resistance changed manually or command not acknowledged")
            return
        }
        if (reading.cadence < PEDALING_STOP_RPM) {
            filteredPower = null
            requestedResistance = reading.resistance.toFloat()
            // A stop mid-ramp drops the expectation (the stopped bike may never finish the last
            // write). Once the reading has been still for the acknowledgement window, expect it
            // again, so a knob turned while stopped is still caught as a manual override.
            if (expectedResistance == null && clock() - steadySince >= ACK_TIMEOUT_MS) {
                expectedResistance = reading.resistance
                commandTime = clock()
                ackTimeoutMs = ACK_TIMEOUT_MS
            }
            // A stop ends any jump or settle hold: on restart the target is judged afresh,
            // with spin-up protection, rather than resuming the old jump.
            if (feedForwardTarget != null || feedForwardActive() || waitingTarget != null) clearFeedForward()
            return
        }
        val measured = externalWatts ?: reading.power
        // The wait is bounded: a rider who never reaches PEDALING_START_RPM still gets
        // feedback after SPIN_UP_MS instead of holding forever.
        if (feedForwardTarget != targetWatts && externalWatts == null && spinningUp() &&
            targetWatts - measured >= FEED_FORWARD_MIN_WATTS &&
            (waitingTarget != targetWatts || clock() - waitSince < SPIN_UP_MS)) {
            if (waitingTarget != targetWatts) {
                waitingTarget = targetWatts
                waitSince = clock()
                trace?.invoke("wait spin-up target=$targetWatts measured=${f1(measured)} level=${reading.resistance}" +
                    " cad=${f1(reading.cadence)} cadAvg=${cadenceAverage?.let(::f1)}" +
                    " pedaling=${if (!pedaling) "no" else "${clock() - pedalingSince}ms"}")
            }
            filteredPower = null
            requestedResistance = reading.resistance.toFloat()
            return
        }
        waitingTarget = null
        if (feedForwardTarget != targetWatts) {
            val previousTarget = feedForwardTarget
            feedForwardTarget = targetWatts
            targetChangedAt = clock()
            // Drops use the cadence held at about the new target (or the rider's typical
            // cadence): riders settle back from a hard effort's higher cadence within seconds.
            // The remembered cadence is floored at the current average minus DROP_CADENCE_ALLOWANCE:
            // that covers the post-effort settle (102 -> 88 rpm) but a remembered slow cadence
            // (a 60 rpm warm-up) cannot size a heavy "drop" for a rider now at 95 rpm.
            // Only a real easing-off is a drop: a lower target that is still above the measured power
            // (320 -> 300 W while the 150 -> 320 W ramp is in flight) is still a rise.
            val drop = previousTarget != null && targetWatts < previousTarget && measured > targetWatts
            val held = if (drop) cadenceHistory.cadenceAt(targetWatts, clock()) else null
            val typical = if (drop && held == null) cadenceHistory.typicalCadence(clock()) else null
            val remembered = held ?: typical
            val current = cadenceAverage ?: reading.cadence
            val floor = current - DROP_CADENCE_ALLOWANCE
            val cadence = if (drop && remembered != null) maxOf(remembered, floor) else current
            val cadenceSource = when {
                drop && remembered != null && floor > remembered -> "floor"
                held != null -> "held"
                typical != null -> "median2m"
                cadenceAverage != null -> "avg5s"
                else -> "reading"
            }
            // After spin-up, a rider still below the model's range (50 rpm) gets plain feedback.
            val lowCadence = cadenceAverage.let { it == null || it < MODEL_MIN_CADENCE }
            // Only large requests jump; small ones keep the existing smooth trim.
            // The model predicts Bike+ native power, so an external meter keeps pure feedback.
            // Beyond the fitted range the model is unreliable: jump no higher than
            // FEED_FORWARD_MAX_LEVEL and let ordinary feedback climb the rest.
            // A drop, or any request while spinning up (only easing off gets here then), never adds resistance.
            val modeled = if (externalWatts == null && !lowCadence && abs(targetWatts - measured) >= FEED_FORWARD_MIN_WATTS)
                powerModel.nearestLevelFor(targetWatts.toFloat(), cadence)?.coerceAtMost(FEED_FORWARD_MAX_LEVEL)
                    ?.let { if (drop || spinningUp()) minOf(it, reading.resistance) else it } else null
            // A "jump" to the current level is no jump: no settle hold, ordinary feedback takes it.
            val level = modeled?.takeIf { it != reading.resistance }
            trace?.invoke("target from=$previousTarget to=$targetWatts measured=${f1(measured)}" +
                " ext=${externalWatts != null} level=${reading.resistance} cad=${f1(reading.cadence)}" +
                " cadAvg=${cadenceAverage?.let(::f1)} cadUsed=${f1(cadence)} cadSrc=$cadenceSource" +
                " action=" + when {
                    level != null -> "jump to=$level rate=$rampRate/s"
                    modeled != null -> "trim(at level)"
                    externalWatts != null -> "trim(external power)"
                    lowCadence -> "trim(low cadence)"
                    abs(targetWatts - measured) < FEED_FORWARD_MIN_WATTS -> "trim(small change)"
                    else -> "trim(no model)"
                })
            if (level != null) {
                feedForwardLevel = level.toFloat()
                feedForwardUntil = clock() + FEED_FORWARD_SETTLE_MS
                feedForwardArrivalPending = true
                feedForwardDeadline = clock() + FEED_FORWARD_MAX_MS
                filteredPower = null
                lastFiltered = null; lastError = null; lastBand = null
                rampLevel = reading.resistance.toFloat()
                rampUp = level >= reading.resistance
                rampExtreme = null
                againstSamples = 0
                rampTime = clock() - RAMP_TICK_MS
                rampOffSince = 0L
                rampTick(reading)
                return
            } else {
                feedForwardUntil = 0L
                feedForwardArrivalPending = false
                feedForwardDeadline = 0L
            }
        }
        if (feedForwardArrivalPending && reading.resistance == feedForwardLevel.roundToInt()) {
            trace?.invoke("arrive level=${reading.resistance} after=${clock() - targetChangedAt}ms power=${f1(measured)}" +
                " cad=${f1(reading.cadence)} settle=${FEED_FORWARD_SETTLE_MS}ms")
            feedForwardArrivalPending = false
            feedForwardUntil = maxOf(feedForwardUntil, clock() + FEED_FORWARD_SETTLE_MS)
        }
        if (!feedForwardActive() && clock() - targetChangedAt >= STEADY_AFTER_CHANGE_MS && abs(measured - targetWatts) <= 7f)
            cadenceHistory.recordSteady(reading.timestamp, reading.cadence, targetWatts)
        val target = if (feedForwardActive()) feedForwardLevel
        else {
            val power = filteredPower?.let { it + dt / (2f + dt) * (measured - it) } ?: measured
            filteredPower = power
            val error = targetWatts - power
            // Hold within half a resistance level (at least 3 W): one level is about
            // 6 W near 160 W but 13 W near 345 W, so a fixed 3 W band hunted at high power.
            val level = requestedResistance.roundToInt().coerceIn(0, 99)
            val cadence = cadenceAverage ?: reading.cadence
            val band = maxOf(MIN_DEADBAND_WATTS,
                DEADBAND_LEVEL_FRACTION * (powerModel.wattsAt(level + 1, cadence) - powerModel.wattsAt(level, cadence)))
            lastFiltered = power
            lastError = error
            lastBand = band
            requestedResistance + if (abs(error) <= band) 0f else gain * error * dt / .1f
        }
        val jump = feedForwardActive()
        requestedResistance = target.coerceIn(requestedResistance - RESISTANCE_RATE * dt, requestedResistance + RESISTANCE_RATE * dt).coerceIn(0f, 100f)
        val next = requestedResistance.roundToInt()
        if (next != reading.resistance) {
            trace?.invoke("write level=$next from=${reading.resistance} why=" +
                if (jump) "settle" else "trim err=$lastError band=$lastBand")
            if (!writeLevel(next)) return
            ackTimeoutMs = ACK_TIMEOUT_MS + if (jump) abs(next - reading.resistance) * ACK_MS_PER_LEVEL else 0L
            expectedResistance = next
            commandTime = clock()
        }
    }

    /**
     * Advance the ramp toward the modeled level at RAMP_RATE. The binder echo may trail the
     * ramp by a few levels; staying further off it for the acknowledgement window means a
     * knob override or ignored writes, so control stops as it does for single writes.
     */
    private fun rampTick(reading: BikeSample) {
        val ramp = rampLevel ?: return
        val goal = feedForwardLevel.roundToInt()
        if (targetWatts != feedForwardTarget || reading.cadence < PEDALING_STOP_RPM) {
            trace?.invoke("ramp cancelled at=${reading.resistance} goal=$goal mode=Erg" +
                " target=$targetWatts cad=${f1(reading.cadence)}")
            // Nothing of the cancelled jump may survive: no settle hold aimed at its goal.
            // A changed target keeps feedForwardTarget so the next tick still sees the drop/rise.
            val targetChanged = targetWatts != feedForwardTarget
            val keep = feedForwardTarget
            clearFeedForward()
            if (targetChanged) feedForwardTarget = keep
            // The last ramp write is usually ahead of the echo: keep expecting it (with time to arrive)
            // rather than the lagging reading, or the arriving echo would look like a knob override.
            // A real knob move never reaches the written level, so it still times out and stops.
            if (reading.cadence < PEDALING_STOP_RPM) {
                // Stopped mid-ramp: the bike may not finish the last write while nobody pedals, so
                // expect nothing; the next write after the rider restarts sets a fresh expectation.
                requestedResistance = reading.resistance.toFloat()
                expectedResistance = null
                commandTime = clock()
                previousTick = clock()
                return
            }
            val written = expectedResistance ?: reading.resistance
            requestedResistance = written.toFloat()
            expectedResistance = written
            ackTimeoutMs = ACK_TIMEOUT_MS + abs(written - reading.resistance) * ACK_MS_PER_LEVEL
            commandTime = clock()
            previousTick = clock()
            return
        }
        // The echo trails the ramp but never runs against it: a reading that falls back from the
        // furthest level reached (or rises, on a downward ramp) is the knob, so stop, whatever the size.
        rampExtreme = rampExtreme?.let { if (rampUp) maxOf(it, reading.resistance) else minOf(it, reading.resistance) }
            ?: reading.resistance
        val against = if (rampUp) rampExtreme!! - reading.resistance else reading.resistance - rampExtreme!!
        if (against <= 0) againstSamples = 0
        else if (reading.timestamp != againstSampleTime) { againstSamples++; againstSampleTime = reading.timestamp }
        // Two levels back at once, or one level back on two samples in a row (~400 ms): a single
        // one-level blip at a level boundary is not treated as a knob move.
        if (against >= KNOB_AGAINST_RAMP_LEVELS || (against >= 1 && againstSamples >= KNOB_AGAINST_RAMP_SAMPLES)) {
            trace?.invoke("ramp knob override level=${reading.resistance} extreme=$rampExtreme goal=$goal")
            stop("Resistance changed manually")
            return
        }
        if (against >= 1) {
            // Hold the ramp until the next reading: with no write in between, a knob turn stays
            // visible and is confirmed, while a boundary blip clears by itself.
            rampTime = clock()
            return
        }
        val expected = expectedResistance
        if (expected != null && abs(reading.resistance - expected) > RAMP_ECHO_LAG_LEVELS) {
            // Pause rather than keep writing against a knob that has been moved.
            if (rampOffSince == 0L) rampOffSince = clock()
            else if (clock() - rampOffSince > ACK_TIMEOUT_MS) stop("Resistance changed manually or command not acknowledged")
            rampTime = clock()
            return
        }
        rampOffSince = 0L
        val seconds = ((clock() - rampTime) / 1000f).coerceIn(0f, .2f)
        rampTime = clock()
        val step = rampRate * seconds
        val next = if (goal > ramp) minOf(ramp + step, goal.toFloat()) else maxOf(ramp - step, goal.toFloat())
        rampLevel = next
        val level = next.roundToInt()
        if (level != expectedResistance && level != reading.resistance) {
            trace?.invoke("write level=$level from=${reading.resistance} why=ramp goal=$goal")
            if (!writeLevel(level)) return
            expectedResistance = level
            commandTime = clock()
            ackTimeoutMs = ACK_TIMEOUT_MS + RAMP_ECHO_LAG_LEVELS * ACK_MS_PER_LEVEL
        }
        requestedResistance = level.toFloat()
        if (level == goal) {
            trace?.invoke("ramp done level=$level after=${clock() - targetChangedAt}ms")
            rampLevel = null
            rampExtreme = null
            previousTick = clock()
            // A slow ramp (3 levels/s) may use up the arrival deadline; give the bike its usual time.
            feedForwardDeadline = maxOf(feedForwardDeadline, clock() + ARRIVAL_AFTER_RAMP_MS)
        }
    }

    private fun spinningUp(): Boolean = !pedaling || (!spinUpDone && clock() - pedalingSince < SPIN_UP_MS)

    /**
     * End the spin-up wait once cadence is steady. The 5 s average still trails a rider who has just
     * settled, so it is re-seeded with the steady window's mean: the jump is sized for the cadence the
     * rider is actually holding, not one still climbing from 0.
     */
    private fun checkSpinUpSteady(reading: BikeSample) {
        if (!pedaling || spinUpDone) { if (!pedaling) spinUpSamples.clear(); return }
        val now = clock()
        spinUpSamples.addLast(now to reading.cadence)
        while (spinUpSamples.isNotEmpty() && now - spinUpSamples.first().first > SPIN_UP_STEADY_MS) spinUpSamples.removeFirst()
        if (now - pedalingSince < SPIN_UP_MIN_MS || now - pedalingSince >= SPIN_UP_MS) return
        val oldest = spinUpSamples.firstOrNull() ?: return
        if (spinUpSamples.size < SPIN_UP_STEADY_SAMPLES || now - oldest.first < SPIN_UP_STEADY_MS - SPIN_UP_STEADY_SLACK_MS) return
        val low = spinUpSamples.minOf { it.second }
        val high = spinUpSamples.maxOf { it.second }
        // The allowed range scales with the time the window actually spans, so sparse samples (a window of only
        // 2 s) cannot pass a climb that is too steep over SPIN_UP_STEADY_MS (independent review).
        val span = (now - oldest.first).coerceAtMost(SPIN_UP_STEADY_MS)
        if (low < MODEL_MIN_CADENCE || high - low > SPIN_UP_STEADY_RPM * span / SPIN_UP_STEADY_MS) return
        spinUpDone = true
        val mean = spinUpSamples.map { it.second }.average().toFloat()
        cadenceAverage = mean
        spinUpSamples.clear()
        trace?.invoke("spin-up steady cad=${f1(mean)} range=${f1(high - low)} pedaling=${now - pedalingSince}ms")
    }

    /** Forget any modeled jump: its ramp, settle hold and arrival wait. */
    private fun clearFeedForward() {
        feedForwardTarget = null
        feedForwardUntil = 0L
        feedForwardArrivalPending = false
        feedForwardDeadline = 0L
        rampLevel = null
        rampExtreme = null
        againstSamples = 0
        // A stop (or anything else that drops the jump) also ends a spin-up wait: the next restart waits afresh.
        waitingTarget = null
        waitSince = 0L
    }

    private fun feedForwardActive(): Boolean =
        clock() < feedForwardUntil || (feedForwardArrivalPending && clock() < feedForwardDeadline)

    private fun averageCadence(reading: BikeSample) {
        if (!pedaling && reading.cadence >= PEDALING_START_RPM) { pedaling = true; pedalingSince = clock(); spinUpDone = false; spinUpSamples.clear() }
        if (reading.cadence < PEDALING_STOP_RPM) pedaling = false
        val previous = cadenceAverage
        val seconds = ((reading.timestamp - cadenceAverageTime) / 1000f).coerceIn(0f, 1f)
        cadenceAverageTime = reading.timestamp
        belowModelSince = if (reading.cadence >= MODEL_MIN_CADENCE || reading.cadence < PEDALING_STOP_RPM) null
            else belowModelSince ?: clock()
        val forgotten = belowModelSince?.let { clock() - it >= LOW_CADENCE_FORGET_MS } == true
        if (forgotten) cadenceForgotten = true
        // After a long low-cadence stretch the rider is spinning up again: restart the spin-up window
        // so a jump is not sized from the first sample above 50 rpm (Fable review: 55 rpm -> level 75).
        if (cadenceForgotten && pedaling && reading.cadence >= MODEL_MIN_CADENCE) {
            cadenceForgotten = false
            pedalingSince = clock()
            spinUpDone = false
            spinUpSamples.clear()
        }
        if (reading.cadence < PEDALING_STOP_RPM) cadenceForgotten = false
        cadenceAverage = when {
            reading.cadence < PEDALING_STOP_RPM -> null
            forgotten -> null
            // The power model is only valid from 50 rpm: slower samples neither feed nor reset the average.
            reading.cadence < MODEL_MIN_CADENCE -> previous
            previous == null -> reading.cadence
            else -> previous + seconds / (CADENCE_AVERAGE_S + seconds) * (reading.cadence - previous)
        }
        checkSpinUpSteady(reading)
    }

    /** Learn watts per level from readings taken after the resistance has been still for a while. */
    private fun learnPowerModel(reading: BikeSample) {
        if (reading.resistance != steadyResistance) {
            steadyResistance = reading.resistance
            steadySince = clock()
            return
        }
        if (clock() - steadySince >= LEARN_SETTLE_MS) powerModel.learn(reading.resistance, reading.power, reading.cadence)
    }

    companion object {
        const val RESISTANCE_RATE = 3f
        // Highest level a modeled jump may target; the fit covers levels 18-63.
        const val FEED_FORWARD_MAX_LEVEL = 75
        // Modeled jumps ramp at 10 levels/s by default, one level per 100 ms tick (43% -> 58% in 1.5 s).
        // How abrupt a jump should feel is a rider preference, so the rate is adjustable (3-20 levels/s).
        const val RAMP_RATE_DEFAULT = 10
        const val RAMP_RATE_MIN = 3
        const val RAMP_RATE_MAX = 20
        const val RAMP_TICK_MS = 100L
        const val RAMP_ECHO_LAG_LEVELS = 5
        // ERG holds within half a resistance level's watts, never less than 3 W.
        const val DEADBAND_LEVEL_FRACTION = .5f
        const val MIN_DEADBAND_WATTS = 3f
        const val CADENCE_AVERAGE_S = 5f
        const val STEADY_AFTER_CHANGE_MS = 8000L
        const val ACK_TIMEOUT_MS = 1500L
        const val ACK_MS_PER_LEVEL = 100L
        const val FEED_FORWARD_MIN_WATTS = 15
        // Hold after arrival while the rider's cadence and the load-cell power settle.
        const val FEED_FORWARD_SETTLE_MS = 4500L
        const val FEED_FORWARD_MAX_MS = 10_000L
        const val LEARN_SETTLE_MS = 2500L
        const val SNAPSHOT_MS = 1000L
        // No modeled jump until the rider has pedaled (40+ rpm) this long: their cadence is still rising.
        const val SPIN_UP_MS = 15_000L
        // ...or less, once cadence has stayed within SPIN_UP_STEADY_RPM (all samples at 50+ rpm) for
        // SPIN_UP_STEADY_MS, after at least SPIN_UP_MIN_MS of pedaling. 4 rpm in 3 s excludes a rider
        // still climbing at more than ~1.3 rpm/s.
        const val SPIN_UP_MIN_MS = 4_000L
        const val SPIN_UP_STEADY_MS = 3_000L
        const val SPIN_UP_STEADY_SLACK_MS = 1_000L
        const val SPIN_UP_STEADY_SAMPLES = 3
        const val SPIN_UP_STEADY_RPM = 4f
        const val PEDALING_START_RPM = 40f
        const val PEDALING_STOP_RPM = 25f
        const val MODEL_MIN_CADENCE = 50f
        // A drop's remembered cadence may sit at most this far below the current average. 8 rpm left
        // drops after 99-102 rpm efforts a level light (riders settle to 88-89 rpm): 10-15 W dips on Nov 2-3.
        // Trade-off: a stale slow memory (a 60 rpm warm-up, then a 95 rpm effort) is now sized for 80 rpm
        // instead of 87, about two levels heavy for the settle hold (never more than the current level);
        // feedback trims it afterwards. The two cases cannot be told apart at the drop.
        const val DROP_CADENCE_ALLOWANCE = 15f
        // A reading this far back against the ramp direction is a knob move, not echo lag: the echo
        // trails but never reverses (0 reversals in 26 ramps on the Oct 31 rides). One level back
        // counts only when it persists for KNOB_AGAINST_RAMP_SAMPLES samples (a boundary blip does not).
        const val KNOB_AGAINST_RAMP_LEVELS = 2
        const val KNOB_AGAINST_RAMP_SAMPLES = 2
        // Continuously below 50 rpm (still pedaling) this long: forget the old cadence average.
        const val LOW_CADENCE_FORGET_MS = 30_000L
        // After a ramp ends, wait this long for the bike to report the goal before falling back to feedback.
        const val ARRIVAL_AFTER_RAMP_MS = 8_500L
        private fun f1(value: Float) = (value * 10f).roundToInt() / 10f
    }
}
