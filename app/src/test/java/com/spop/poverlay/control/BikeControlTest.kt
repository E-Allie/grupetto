package com.spop.poverlay.control

import com.spop.poverlay.erg.ErgMode
import com.spop.poverlay.erg.ErgPath
import com.spop.poverlay.erg.PowerTable
import com.spop.poverlay.sim.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real controller with a monotonic clock and immediate, fake actuator feedback. */
internal class ControlRig {
    var now = 1000L
    var resistance = 40
    val writes = mutableListOf<Int>()
    var writeSucceeds = true
    val control = BikeControl(supported = true, clock = { now }, writeResistance = {
        writes.add(it)
        if (writeSucceeds) resistance = it
        writeSucceeds
    })
    fun sample(power: Float = 100f, cadence: Float = 80f, elapsed: Long = 200) {
        now += elapsed
        control.acceptSample(BikeSample(power, cadence, resistance, now))
        control.tick()
    }
    fun command(vararg bytes: Int, client: String = "ble:a") =
        control.procedure(client, bytes.map { it.toByte() }.toByteArray())
    fun acquire() { sample(); assertEquals(1, command(0).result) }
    fun enableSim(autoResume: Boolean = false, maxWatts: Int = 140) {
        control.setSimulationSettings(SimulationSettings(enabled = true,
            autoResumeAfterPause = autoResume, maxSimWatts = maxWatts))
    }
}

class BikeControlTest {
    private val r = ControlRig()
    private val control get() = r.control
    @After fun cleanup() { control.close() }

    @Test fun requiresFreshConnectedHardware() {
        assertEquals(4, r.command(0).result)
        assertFalse(control.localErg(150))
        r.sample()
        assertTrue(control.state.value.connected)
        r.now += 1501; control.tick()
        assertFalse(control.state.value.connected)
        assertEquals(4, r.command(0).result)
    }
    @Test fun unsupportedHardwareNeverWrites() {
        val disabled = BikeControl(false, { r.now }, { fail("Must not write"); true })
        try {
            disabled.acceptSample(BikeSample(100f, 80f, 40, r.now))
            assertFalse(disabled.localErg(200))
            assertFalse(disabled.localSimulation())
            assertEquals(2, disabled.procedure("a", byteArrayOf(0)).result)
            disabled.tick()
            assertFalse(disabled.state.value.connected)
        } finally { disabled.close() }
    }
    @Test fun engagedOwnerIsExclusiveAcrossBothTransports() {
        r.acquire(); assertEquals(1, r.command(5, 200, 0).result)
        assertEquals(5, r.command(0, client = "dircon:b").result)
        assertEquals(5, r.command(5, 200, 0, client = "dircon:b").result)
        control.disconnect("dircon:b")
        assertEquals(ControlMode.Erg, control.state.value.mode)
        control.disconnect("ble:a")
        assertEquals(ControlMode.Manual, control.state.value.mode)
        assertEquals(1, r.command(0, client = "dircon:b").result)
    }
    @Test fun idleAcquisitionAndResetYieldToAnotherConnectedClient() {
        r.acquire()
        assertEquals(1, r.command(0, client = "dircon:b").result)
        assertEquals(5, r.command(5, 150, 0).result)
        assertEquals(1, r.command(5, 150, 0, client = "dircon:b").result)
        assertEquals(1, r.command(1, client = "dircon:b").result)
        assertEquals(1, r.command(0).result)
        assertEquals("ble:a", control.state.value.owner)
    }
    @Test fun onlyOwningTransportStopDisarms() {
        r.acquire(); r.command(5, 200, 0)
        control.disconnectTransport("dircon:")
        assertEquals(ControlMode.Erg, control.state.value.mode)
        control.disconnectTransport("ble:")
        assertEquals(ControlMode.Manual, control.state.value.mode)
    }
    @Test fun validatesAllLengthsBeforeActing() {
        r.acquire()
        listOf(intArrayOf(), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(4),
            intArrayOf(4, 40), intArrayOf(4, 40, 0, 0), intArrayOf(5, 100), intArrayOf(5, 100, 0, 0),
            intArrayOf(7, 0), intArrayOf(8), intArrayOf(0x11, 0, 0, 0, 0, 0),
            intArrayOf(0x11, 0, 0, 0, 0, 0, 0, 0)).forEach {
            assertEquals(it.contentToString(), 3, r.command(*it).result)
        }
        assertEquals(ControlMode.Manual, control.state.value.mode)
        assertTrue(r.writes.isEmpty())
    }
    @Test fun signedPowerAndCommonAdvertisedLimits() {
        r.acquire()
        assertEquals(25..800, control.supportedPowerRange)
        for (watts in listOf(-1, 0, 24, 801, 1000, 32767))
            assertEquals(3, r.command(5, watts and 255, watts shr 8).result)
        for (watts in listOf(25, 150, 800)) {
            val reply = r.command(5, watts and 255, watts shr 8)
            assertEquals(1, reply.result)
            assertEquals(watts, control.state.value.targetWatts)
            assertArrayEquals(byteArrayOf(8, watts.toByte(), (watts shr 8).toByte()), reply.status)
        }
    }
    @Test fun unsupportedOpcodeDoesNotChangeMode() {
        r.acquire(); r.command(5, 150, 0)
        assertEquals(2, r.command(0x12, 0, 0).result)
        assertEquals(ControlMode.Erg, control.state.value.mode)
    }
    @Test fun resistanceUsesSignedTenthsRejectsFractionalStepsAndWritesImmediately() {
        r.acquire()
        assertEquals(1, r.command(4, 232, 3).result)
        assertEquals(ControlMode.Resistance, control.state.value.mode)
        assertEquals(listOf(100), r.writes)
        for (bytes in listOf(intArrayOf(4, 233, 3), intArrayOf(4, 255, 255), intArrayOf(4, 1, 0)))
            assertEquals(3, r.command(*bytes).result)
        assertEquals(listOf(100), r.writes)
    }
    @Test fun explicitResistanceRecoveryWorksAtZeroCadenceWithoutASlewLimit() {
        r.sample(cadence = 0f)
        assertTrue(control.localResistance(80))
        assertTrue(control.localResistance(20))
        assertEquals(listOf(80, 20), r.writes)
        assertEquals(20, r.resistance)
        r.command(0)
        assertEquals(1, r.command(4, 132, 3).result) // 900 tenths = 90.
        assertEquals(90, r.resistance)
    }
    @Test fun resetAndStopDisarmWithoutMotorJump() {
        r.acquire(); r.command(5, 250, 0)
        assertEquals(3, r.command(8, 3).result)
        assertEquals(ControlMode.Erg, control.state.value.mode)
        assertEquals(1, r.command(8, 1).result)
        val before = r.writes.toList()
        repeat(5) { r.sample() }
        assertEquals(before, r.writes)
        assertEquals(1, r.command(7).result)
        assertEquals(ControlMode.Manual, control.state.value.mode)
        r.command(5, 200, 0)
        assertEquals(1, r.command(1).result)
        assertEquals(ControlMode.Manual, control.state.value.mode)
    }
    @Test fun hostErgUsesTablePredictionThenFeedbackForOppositeErrors() {
        r.sample(); assertTrue(control.localErg(200)); control.tick()
        assertEquals(ErgPath.Host, control.state.value.ergPath)
        assertEquals(PowerTable.resistanceFor(200, 80.0)!!.toInt(), r.resistance)
        val predicted = r.resistance
        repeat(25) { r.sample(100f) }
        assertTrue(r.resistance > predicted)
        control.localErg(25)
        repeat(30) { r.sample(200f) }
        assertTrue(r.resistance < predicted)
        assertTrue(r.writes.all { it in 0..100 })
    }
    @Test fun noMotionBelowMinimumCadenceAndNoCachedWindup() {
        r.sample(cadence = 0f); control.localErg(200)
        repeat(100) { r.sample(0f, 0f) }
        assertTrue(r.writes.isEmpty())
        r.sample(0f, 24f)
        assertTrue(r.writes.isEmpty())
        r.sample(100f, 80f)
        assertEquals(listOf(PowerTable.resistanceFor(200, 80.0)!!.toInt()), r.writes)
    }
    @Test fun powerDeadbandDoesNotHuntAfterFeedForward() {
        r.sample(150f); control.localErg(150); r.sample(150f)
        val afterPrediction = r.writes.toList()
        repeat(50) { r.sample(152f) }
        assertEquals(afterPrediction, r.writes)
    }
    @Test fun staleTelemetrySuspendsAndFreshDataCannotResumeAutomatically() {
        r.sample(); control.localErg(250); control.tick()
        val before = r.writes.toList()
        r.now += 1501; control.tick()
        assertFalse(control.state.value.connected)
        repeat(10) { r.sample() }
        assertTrue(control.state.value.suspended)
        assertEquals(ControlMode.Erg, control.state.value.mode)
        assertEquals(before, r.writes)
        assertTrue(control.resumeLocally())
        r.sample()
        assertTrue(r.writes.size > before.size)
    }
    @Test fun targetUpdateCannotRearmAfterCachedFeedbackSynchronouslyLatchesOverride() {
        r.sample()
        assertTrue(control.localErg(200))
        control.tick()
        assertEquals(ErgPath.Host, control.state.value.ergPath)
        assertNotEquals(40, r.resistance)
        // An old resistance echo is legitimate while the new command is in flight.
        r.resistance = 40
        r.sample(elapsed = 1000)
        assertFalse(control.state.value.suspended)
        val writesBeforeUpdate = r.writes.toList()
        // The command's 1.5 s echo window expires before this sample goes stale.
        // localErg feeds cached feedback synchronously, without an intervening tick.
        r.now += 501
        assertFalse(control.localErg(250))
        assertTrue(control.state.value.connected)
        assertTrue(control.state.value.suspended)
        assertEquals("Resistance changed outside app control", control.state.value.message)
        assertEquals(ErgPath.None, control.state.value.ergPath)
        assertEquals(writesBeforeUpdate, r.writes)
    }
    @Test fun repeatedTelemetryCannotContinueDrivingMotor() {
        r.sample(); control.localErg(250); control.tick()
        val before = r.writes.toList()
        repeat(10) { r.now += 100; control.tick() }
        assertEquals(before, r.writes)
    }
    @Test fun invalidSampleDisarmsAndLatchesUntilDeliberateResume() {
        r.sample(); control.localErg(250)
        control.acceptSample(BikeSample(Float.NaN, 80f, 40, r.now))
        assertFalse(control.state.value.connected)
        assertTrue(control.state.value.suspended)
        assertTrue(r.writes.isEmpty())
        r.sample(); assertFalse(control.localErg(250)); assertTrue(control.resumeLocally())
    }
    @Test fun futureTelemetryCannotAuthorizeWrites() {
        control.acceptSample(BikeSample(100f, 80f, 40, r.now + 1))
        assertFalse(control.state.value.connected)
        assertFalse(control.localResistance(80))
        assertEquals(4, r.command(0).result)
        assertTrue(r.writes.isEmpty())
    }
    @Test fun binderFailureStopsControlWithoutRetrying() {
        r.sample(); control.localErg(250); r.writeSucceeds = false
        repeat(10) { r.sample() }
        assertEquals(1, r.writes.size)
        assertTrue(control.state.value.suspended)
        assertEquals("Resistance command failed", control.state.value.message)
    }
    @Test fun knobOverrideSurvivesRemoteResetStopAndReconnect() {
        r.acquire(); r.command(5, 250, 0)
        r.resistance = 20; r.sample()
        assertTrue(control.state.value.suspended)
        assertTrue(r.writes.isEmpty())
        assertEquals(5, r.command(5, 200, 0).result)
        assertEquals(1, r.command(1).result)
        assertEquals(1, r.command(8, 1).result)
        control.disconnect("ble:a")
        assertEquals(1, r.command(0, client = "dircon:b").result)
        assertEquals(5, r.command(7, client = "dircon:b").result)
        assertEquals(5, r.command(4, 32, 3, client = "dircon:b").result)
        assertFalse(control.localResistance(80))
        assertTrue(r.writes.isEmpty())
        assertTrue(control.resumeLocally())
        assertEquals(1, r.command(4, 32, 3, client = "dircon:b").result)
        assertEquals(listOf(80), r.writes)
    }
    @Test fun localStopCannotBeUndoneByRemoteStartOrTarget() {
        r.acquire(); r.command(5, 200, 0); control.pauseLocally()
        assertTrue(control.state.value.suspended)
        assertEquals(5, r.command(7).result)
        assertEquals(5, r.command(5, 250, 0).result)
        repeat(10) { r.sample() }
        assertTrue(r.writes.isEmpty())
        assertTrue(control.resumeLocally())
        r.sample(); assertTrue(r.writes.isNotEmpty())
    }
    @Test fun simulationRequiresExplicitOptIn() {
        r.acquire()
        assertFalse(control.localSimulation(5f))
        assertEquals(2, r.command(0x11, 0, 0, 244, 1, 40, 51).result)
        assertTrue(r.writes.isEmpty())
        r.enableSim()
        assertTrue(control.supportsSimulation)
    }
    @Test fun zeroSimulationInitializationDoesNotActuateAndLeavesOwnershipYieldable() {
        r.enableSim(); r.acquire()
        assertEquals(1, r.command(0x11, 0, 0, 0, 0, 0, 0).result)
        repeat(5) { r.sample() }
        assertFalse(control.state.value.controlEngaged)
        assertTrue(r.writes.isEmpty())
        assertEquals(1, r.command(0, client = "dircon:b").result)
        assertEquals(1, r.command(5, 200, 0, client = "dircon:b").result)
    }
    @Test fun simulationShiftsPersistAcrossGradePacketsAndExplicitErgDoesNotUseGear() {
        r.enableSim(); r.acquire()
        assertEquals(1, r.command(0x11, 0, 0, 0, 0, 40, 51).result)
        control.shift(1)
        assertEquals(13, control.state.value.gear)
        r.command(0x11, 0, 0, 244, 1, 40, 51)
        assertEquals(13, control.state.value.gear)
        repeat(5) { r.sample() }
        val highGearWatts = control.state.value.simulationDecision!!.load!!.watts
        control.shift(-1)
        assertEquals(12, control.state.value.gear)
        assertTrue(control.state.value.simulationDecision!!.load!!.watts < highGearWatts)
        r.command(5, 200, 0); control.shift(1)
        assertEquals(12, control.state.value.gear)
        assertEquals(210, control.state.value.targetWatts)
    }
    @Test fun downhillReleasesToMinimumAndHonorsSignedGrade() {
        r.enableSim(); r.acquire()
        assertEquals(1, r.command(0x11, 0, 0, 12, 254, 40, 51).result)
        repeat(5) { r.sample() }
        assertEquals(SimulationPhase.LowLoad, control.state.value.simulationDecision!!.phase)
        assertTrue(control.state.value.simulationDecision!!.load!!.watts < 0)
        assertEquals(listOf(0), r.writes)
    }
    @Test fun simulationPhysicsRespondsToCadenceWindRollingResistanceAndGear() {
        r.enableSim(maxWatts = 800); r.acquire()
        r.command(0x11, 0, 0, 244, 1, 40, 51)
        repeat(5) { r.sample(cadence = 60f) }
        val slow = control.state.value.simulationDecision!!.load!!.watts
        repeat(5) { r.sample(cadence = 100f) }
        val fast = control.state.value.simulationDecision!!.load!!.watts
        assertTrue(fast > slow)
        r.command(0x11, 136, 19, 244, 1, 40, 51) // +5 m/s headwind.
        val headwind = control.state.value.simulationDecision!!.load!!.watts
        assertTrue(headwind > fast)
        r.command(0x11, 136, 19, 244, 1, 100, 51)
        assertTrue(control.state.value.simulationDecision!!.load!!.watts > headwind)
    }
    @Test fun simulationCapAndRestartRampSurviveControllerIntegration() {
        r.enableSim(); r.sample(); assertTrue(control.localSimulation(20f))
        repeat(50) {
            r.sample()
            control.state.value.simulationDecision!!.requestedWatts?.let { watts -> assertTrue(watts <= 140) }
        }
        assertEquals(140, control.state.value.simulationDecision!!.requestedWatts)
        r.sample(cadence = 0f)
        assertEquals(0, r.resistance)
        assertTrue(control.state.value.simulationDecision!!.resumeRequired)
        val before = r.writes.toList()
        repeat(10) { r.sample() }
        assertEquals(before, r.writes)
        assertTrue(control.resumeLocally())
        repeat(2) { r.sample() }
        assertEquals(30, control.state.value.simulationDecision!!.requestedWatts)
        repeat(3) { r.sample() }
        assertEquals(40, control.state.value.simulationDecision!!.requestedWatts)
    }
    @Test fun automaticSimulationResumeRequiresMinimumFeedbackAndNeverClearsKnobLatch() {
        r.enableSim(autoResume = true); r.sample(); control.localSimulation(10f)
        repeat(10) { r.sample() }
        r.sample(cadence = 0f)
        r.sample(cadence = 0f) // Confirm the actual minimum target before reentry.
        repeat(3) { r.sample() }
        assertFalse(control.state.value.simulationDecision!!.resumeRequired)
        assertEquals(SimulationPhase.Holding, control.state.value.simulationDecision!!.phase)
        r.sample(cadence = 0f); r.sample(cadence = 0f)
        r.resistance = 20; r.sample(cadence = 0f)
        assertTrue(control.state.value.suspended)
        repeat(10) { r.sample() }
        assertEquals(20, r.resistance)
    }
    @Test fun tuningClampsValuesAndPreservesThemAcrossModeChanges() {
        control.tune(100, Float.NaN, 100)
        assertEquals(10, control.state.value.shiftSize)
        assertEquals(.007f, control.state.value.gain, 0f)
        assertEquals(50, control.state.value.wattsPerShift)
        control.tune(-10, 100f, -1)
        assertEquals(1, control.state.value.shiftSize)
        assertEquals(.03f, control.state.value.gain, 0f)
        assertEquals(1, control.state.value.wattsPerShift)
        r.enableSim(); r.sample(); control.localSimulation(); control.localErg(150); control.localManual()
        assertEquals(1, control.state.value.shiftSize)
        assertEquals(.03f, control.state.value.gain, 0f)
        assertEquals(1, control.state.value.wattsPerShift)
    }
    @Test fun virtualGearSaturationDoesNotAccumulateHiddenShifts() {
        r.enableSim(); r.sample(); control.localSimulation()
        repeat(100) { control.shift(1) }; assertEquals(24, control.state.value.gear)
        control.shift(-1); assertEquals(23, control.state.value.gear)
        repeat(100) { control.shift(-1) }; assertEquals(1, control.state.value.gear)
        control.shift(1); assertEquals(2, control.state.value.gear)
    }
    @Test fun disconnectedSampleStopsImmediatelyAndReconnectCannotRearm() {
        r.sample(); control.localErg(200); control.acceptSample(null)
        assertTrue(control.state.value.suspended)
        assertFalse(control.state.value.connected)
        repeat(5) { r.sample() }
        assertTrue(r.writes.isEmpty())
    }
    @Test fun remotePauseAndResumePreserveTargetAndOwnership() {
        r.acquire(); r.command(5, 200, 0)
        val revision = control.state.value.permissionLostCount
        assertEquals(1, r.command(8, 2).result)
        repeat(10) { r.sample() }
        assertTrue(r.writes.isEmpty())
        assertTrue(control.state.value.paused)
        assertFalse(control.state.value.suspended)
        assertEquals(ControlMode.Erg, control.state.value.mode)
        assertEquals(revision, control.state.value.permissionLostCount)
        assertEquals(1, r.command(7).result)
        assertEquals(200, control.state.value.targetWatts)
        r.sample(); assertTrue(r.writes.isNotEmpty())
    }
    @Test fun deliberateManualTakeoverRevokesRemotePermission() {
        r.enableSim(); r.acquire(); r.command(5, 200, 0); control.stop()
        assertEquals(1, control.state.value.permissionLostCount)
        assertEquals(5, r.command(5, 200, 0).result)
        assertTrue(control.localSimulation())
    }
    @Test fun remoteCommandsOverrideUnlatchedLocalModesOnBothTransports() {
        r.enableSim(); r.sample()
        for (client in listOf("ble:a", "dircon:b")) {
            control.localErg(150)
            assertEquals(1, r.command(0, client = client).result)
            control.localManual()
            assertEquals(1, r.command(5, 210, 0, client = client).result)
            assertEquals(210, control.state.value.targetWatts)
            control.localResistance(30)
            assertEquals(1, r.command(0x11, 0, 0, 12, 254, 40, 51, client = client).result)
            assertEquals(ControlMode.Simulation, control.state.value.mode)
            assertEquals(-5f, control.state.value.targetIncline, .001f)
            control.localErg(180)
            assertEquals(1, r.command(4, 38, 2, client = client).result)
            assertEquals(55, control.state.value.targetResistance)
            control.disconnect(client)
        }
    }
    @Test fun ergShiftsUseWattsAndClampWithoutAccumulating() {
        r.sample(); control.tune(3, .007f, 20); control.localErg(150); control.shift(1)
        assertEquals(170, control.state.value.targetWatts)
        assertTrue(r.writes.isEmpty())
        repeat(100) { control.shift(1) }; assertEquals(800, control.state.value.targetWatts)
        control.shift(-1); assertEquals(780, control.state.value.targetWatts)
        repeat(100) { control.shift(-1) }; assertEquals(25, control.state.value.targetWatts)
        control.shift(1); assertEquals(45, control.state.value.targetWatts)
    }
    @Test fun manualShiftsAccumulateImmediatelyEvenWhileStopped() {
        r.sample(cadence = 0f); control.tune(3, .007f); control.shift(1); control.shift(1)
        assertEquals(listOf(43, 46), r.writes)
        assertEquals(46, control.state.value.targetResistance)
        repeat(100) { control.shift(1) }; assertEquals(100, r.resistance)
        control.shift(-1); assertEquals(97, r.resistance)
        repeat(100) { control.shift(-1) }; assertEquals(0, r.resistance)
    }
    @Test fun externalFlagRequiresAcceptedTargetAndClearsOnLocalOrSafetyEvents() {
        r.acquire(); assertNull(control.state.value.externalControl)
        r.command(5, 200, 0); assertEquals("Bluetooth", control.state.value.externalControl)
        val before = control.state.value
        r.command(5, 0, 0); r.command(5, 230, 0, client = "dircon:b")
        assertEquals(before, control.state.value)
        control.shift(1); assertNull(control.state.value.externalControl)
        r.command(5, 220, 0); assertEquals("Bluetooth", control.state.value.externalControl)
        r.now += 1501; control.tick(); assertNull(control.state.value.externalControl)
        r.sample(); r.command(1); control.resumeLocally()
        r.command(0, client = "dircon:b"); r.command(5, 240, 0, client = "dircon:b")
        assertEquals("DirCon", control.state.value.externalControl)
        control.disconnect("dircon:b"); assertNull(control.state.value.externalControl)
    }
    @Test fun pauseResumeRetainsResistanceTargetDespiteLiveTelemetry() {
        r.acquire(); r.command(4, 88, 2); r.command(8, 2)
        r.resistance = 40; repeat(5) { r.sample() }
        assertNull(control.state.value.externalControl)
        assertEquals(60, control.state.value.targetResistance)
        r.command(7)
        assertEquals(60, r.resistance)
        assertEquals("Bluetooth", control.state.value.externalControl)
    }
    @Test fun localTargetsValidateInputsWithoutMutatingState() {
        assertFalse(control.localManual()); assertFalse(control.localResistance(40))
        r.enableSim(); r.sample()
        val before = control.state.value
        assertFalse(control.localResistance(101)); assertFalse(control.localResistance(-1))
        assertFalse(control.localSimulation(Float.NaN)); assertFalse(control.localSimulation(Float.POSITIVE_INFINITY))
        assertFalse(control.localErg(801)); assertFalse(control.localErg(24))
        assertEquals(before, control.state.value)
    }
    @Test fun backendChangeRequiresLocalResumeWhileActive() {
        r.sample(); control.localErg(200); control.setErgMode(ErgMode.Host)
        assertTrue(control.state.value.suspended)
        assertFalse(control.localErg(200))
        assertTrue(control.resumeLocally()); r.sample()
        assertEquals(ErgPath.Host, control.state.value.ergPath)
    }
}
