package com.spop.poverlay.control

import org.junit.Assert.*
import org.junit.Test

class ResistanceRecoveryTest {
    @Test fun nearTargetAcknowledgesWithoutMaskingLaterOnePointKnobTurn() {
        for (echo in listOf(48, 49, 50, 51, 52)) {
            val a = ResistanceAcknowledgement()
            a.command(50, 0, 30)
            assertNull(a.sample(30, 500, 500))
            assertNull(a.sample(echo, 1100, 1100))
            assertNull(a.sample(echo, 1300, 1300))
            assertNull(a.sample(echo, 5000, 5000))
            assertEquals(ControlFault.RiderOverride, a.sample(echo - 1, 5200, 5200))
        }
    }
    @Test fun duplicateOrStalePacketsCannotAcknowledgeCommand() {
        val a = ResistanceAcknowledgement()
        a.command(50, 0, 30)
        assertNull(a.sample(49, 1000, 1000))
        assertNull(a.sample(49, 1000, 1200))
        assertNull(a.sample(49, 1000, 3001))
        assertEquals(ControlFault.ResistanceTimeout, a.sample(30, 3100, 3100))
    }
    @Test fun repeatedOldSetpointTimesOutWithoutRetryingAndRecoveryRequiresExactGenerationAndOwner() {
        var now = 0L
        val writes = mutableListOf<Int>()
        val c = BikeControl(true, { now }) { writes.add(it); true }
        fun sample(time: Long, level: Int) { now = time; c.acceptSample(BikeSample(100f, 85f, level, now)) }
        fun command(client: String, vararg bytes: Int) = c.procedure(client, bytes.map { it.toByte() }.toByteArray()).result
        try {
            sample(0, 30)
            assertEquals(1, command("dircon:a", 0))
            assertEquals(1, command("dircon:a", 4, 244, 1))
            sample(3100, 30)
            val fault = c.state.value
            assertEquals(ControlFault.ResistanceTimeout, fault.fault)
            assertTrue(fault.suspended)
            assertEquals(listOf(50), writes)
            assertEquals(1, command("dircon:a", 1))
            assertEquals(ControlFault.ResistanceTimeout, c.state.value.fault)
            assertTrue(c.state.value.message.contains("did not settle"))
            assertEquals(5, command("dircon:a", 4, 44, 1))
            assertFalse(c.recoverRemotely("dircon:b", fault.faultGeneration))
            assertFalse(c.recoverRemotely("dircon:a", fault.faultGeneration - 1))
            assertTrue(c.recoverRemotely("dircon:a", fault.faultGeneration))
            assertEquals(listOf(50), writes) // Recovery does not move anything.
            assertEquals(1, command("dircon:a", 4, 44, 1))
            assertEquals(listOf(50, 30), writes)
            sample(6400, 49)
            assertFalse(c.recoverRemotely("dircon:a", fault.faultGeneration))
        } finally { c.close() }
    }
    @Test fun riderStopCannotBeClearedByStaleRecoveryEvenAfterReset() {
        var now = 0L
        val c = BikeControl(true, { now }) { true }
        try {
            c.acceptSample(BikeSample(100f, 85f, 30, 0))
            c.procedure("a", byteArrayOf(0)); c.procedure("a", byteArrayOf(4, 244.toByte(), 1))
            now = 3100; c.acceptSample(BikeSample(100f, 85f, 30, now))
            val generation = c.state.value.faultGeneration
            c.procedure("a", byteArrayOf(1))
            c.pauseLocally()
            assertEquals(ControlFault.RiderStop, c.state.value.fault)
            assertFalse(c.recoverRemotely("a", generation))
            assertFalse(c.recoverRemotely("a", c.state.value.faultGeneration))
            assertTrue(c.resumeLocally())
            assertNull(c.state.value.fault)
        } finally { c.close() }
    }
    @Test fun protocolDeclaresFilteringAndRecoverySeparatelyFromConnection() {
        val state = ControlState(connected = true, suspended = true, fault = ControlFault.ResistanceTimeout, faultGeneration = 42)
        val bytes = TrainerProtocol.encode(state)
        assertEquals(12, bytes.size)
        assertEquals(1, bytes[3].toInt())
        assertEquals(70, bytes[4].toInt())
        assertEquals(7, bytes[6].toInt())
        assertEquals(42, TrainerProtocol.recoveryGeneration(byteArrayOf(1, 0, 0, 0, 42)))
        assertNull(TrainerProtocol.recoveryGeneration(byteArrayOf(1, 0)))
        assertNull(TrainerProtocol.recoveryGeneration(byteArrayOf(2, 0, 0, 0, 42)))
    }
    @Test fun localStopWinsAfterRecoveryButBeforeAnyNewTarget() {
        var now = 0L
        val writes = mutableListOf<Int>()
        val c = BikeControl(true, { now }) { writes += it; true }
        try {
            c.acceptSample(BikeSample(100f,85f,30,now))
            c.procedure("a",byteArrayOf(0));c.procedure("a",byteArrayOf(4,244.toByte(),1))
            now=3100;c.acceptSample(BikeSample(100f,85f,30,now))
            assertTrue(c.recoverRemotely("a",c.state.value.faultGeneration))
            c.pauseLocally()
            assertEquals(ControlFault.RiderStop,c.state.value.fault)
            assertEquals(5,c.procedure("a",byteArrayOf(4,244.toByte(),1)).result)
            assertEquals(listOf(50),writes)
        } finally {c.close()}
    }
}
