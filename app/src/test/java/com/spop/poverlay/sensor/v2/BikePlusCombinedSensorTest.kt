package com.spop.poverlay.sensor.v2

import android.os.IBinder
import android.os.Parcel
import com.spop.poverlay.control.BikeSample
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class BikePlusCombinedSensorTest {
    private val binder = mockk<IBinder>()
    private val data = mockk<Parcel>(relaxed = true)
    private val reply = mockk<Parcel>(relaxed = true)
    private val recycled = CountDownLatch(1)

    @Before fun setup() {
        mockkStatic(Parcel::class)
        val parcelIndex = ThreadLocal.withInitial { 0 }
        every { Parcel.obtain() } answers {
            val index = parcelIndex.get()
            parcelIndex.set(index + 1)
            if (index % 2 == 0) data else reply
        }
        every { binder.transact(14, data, reply, 0) } returns true
        every { reply.readInt() } returnsMany listOf(1, 42, 43)
        every { reply.readLong() } returnsMany listOf(85L, 18000L, 0L, 0L)
        every { reply.readString() } returns "5.11"
        every { reply.createByteArray() } returns titanPacket(pzafSetpoint = 180)
        every { reply.recycle() } answers { recycled.countDown() }
    }
    @After fun cleanup() { unmockkStatic(Parcel::class) }

    @Test fun `publishes a timestamped packet and clears it on stop`() {
        val samples = Collections.synchronizedList(mutableListOf<BikeSample?>())
        val received = CountDownLatch(1)
        val sensor = BikePlusCombinedSensor(binder, {
            samples.add(it)
            if (it != null) received.countDown()
        }, { 1234L })
        try {
            sensor.start()
            assertTrue(received.await(2, TimeUnit.SECONDS))
            assertEquals(TitanStatusReading(sensor.latestStatus!!, 1234L), sensor.latestStatusReading)
            assertEquals(1234L, sensor.latestStatusTimestamp)
            assertEquals(BikeSample(180f, 85f, 43, 1234L), samples.first())
        } finally {
            sensor.stop()
            assertTrue(recycled.await(2, TimeUnit.SECONDS))
        }
        assertNull(sensor.latestStatus)
        assertNull(sensor.latestStatusTimestamp)
        assertNull(samples.last())
        assertFalse(sensor.setPowerZoneAutoFollow(180))
    }

    @Test fun `in flight reply after stop cannot reconnect or republish native state`() {
        val transactionStarted = CountDownLatch(1)
        val releaseReply = CountDownLatch(1)
        val samples = Collections.synchronizedList(mutableListOf<BikeSample?>())
        every { binder.transact(14, data, reply, 0) } answers {
            transactionStarted.countDown()
            // A Binder call need not be interruptible. Simulate it completing
            // after stop() has revoked access to this connection.
            while (releaseReply.count > 0) {
                try { releaseReply.await() } catch (_: InterruptedException) { }
            }
            true
        }
        val sensor = BikePlusCombinedSensor(binder, { samples.add(it) }, { 1234L })
        try {
            sensor.start()
            assertTrue(transactionStarted.await(2, TimeUnit.SECONDS))
            sensor.stop()
            assertFalse(sensor.setResistance(20))
        } finally {
            releaseReply.countDown()
            assertTrue(recycled.await(2, TimeUnit.SECONDS))
            sensor.stop()
        }
        assertTrue(samples.isNotEmpty())
        assertTrue(samples.all { it == null })
        assertNull(sensor.latestStatusReading)
    }

    @Test fun `five failed reads release owned native control before reporting disconnect`() {
        val reading = CountDownLatch(1)
        val failReads = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        every { binder.transact(14, data, reply, 0) } answers {
            reading.countDown()
            failReads.await()
            false
        }
        every { binder.transact(51, data, reply, 0) } answers { events.add("enable"); true }
        every { binder.transact(52, data, reply, 0) } answers { events.add("disable"); true }
        val sensor = BikePlusCombinedSensor(binder, {
            if (it == null) { events.add("disconnected"); disconnected.countDown() }
        }, { 1234L })
        try {
            sensor.start()
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            assertTrue(sensor.setPowerZoneAutoFollow(180))
            failReads.countDown()
            assertTrue(disconnected.await(4, TimeUnit.SECONDS))
            assertEquals(listOf("enable", "disable", "disconnected"), events.toList())
            verify(exactly = 5) { binder.transact(14, data, reply, 0) }
            assertFalse(sensor.setPowerZoneAutoFollow(200))
            assertFalse(sensor.setResistance(40))
            assertFalse(sensor.setPzafRampUpRate(50))
            assertTrue(sensor.disablePowerZoneAutoFollow())
            // The disconnected callback's subsequent disable is already satisfied.
            verify(exactly = 1) { binder.transact(52, data, reply, 0) }
        } finally {
            failReads.countDown()
            sensor.stop()
        }
    }

    @Test fun `stopping a read only monitor does not disable another app native loop`() {
        val received = CountDownLatch(1)
        every { reply.createByteArray() } returns titanPacket(
            pzafMode = 1, pzafStatus = PzafStatus.ENABLED_ACTIVE, pzafSetpoint = 180
        )
        val sensor = BikePlusCombinedSensor(binder, { if (it != null) received.countDown() }, { 1234L })
        try {
            sensor.start()
            assertTrue(received.await(2, TimeUnit.SECONDS))
            assertTrue(sensor.latestStatus!!.pzafActive)
        } finally {
            sensor.stop()
            assertTrue(recycled.await(2, TimeUnit.SECONDS))
        }
        verify(exactly = 0) { binder.transact(52, any(), any(), any()) }
        assertFalse(sensor.disablePowerZoneAutoFollow())
        sensor.start() // A stopped binding cannot be revived by an old owner.
        assertFalse(sensor.setPowerZoneAutoFollow(200))
    }

    @Test fun `a rejected enable creates no native teardown obligation`() {
        val reading = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        every { binder.transact(14, data, reply, 0) } answers {
            reading.countDown(); finishRead.await(); false
        }
        every { binder.transact(51, data, reply, 0) } returns false
        val sensor = BikePlusCombinedSensor(binder, clock = { 1234L })
        try {
            sensor.start()
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            assertFalse(sensor.setPowerZoneAutoFollow(180))
        } finally {
            sensor.stop()
            finishRead.countDown()
            assertTrue(recycled.await(2, TimeUnit.SECONDS))
        }
        verify(exactly = 0) { binder.transact(52, any(), any(), any()) }
    }

    @Test fun `failed native release can retry only disable after stop`() {
        val reading = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        every { binder.transact(14, data, reply, 0) } answers {
            reading.countDown(); finishRead.await(); false
        }
        every { binder.transact(51, data, reply, 0) } returns true
        every { binder.transact(52, data, reply, 0) } returnsMany listOf(false, true)
        val sensor = BikePlusCombinedSensor(binder, clock = { 1234L })
        try {
            sensor.start()
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            assertTrue(sensor.setPowerZoneAutoFollow(180))
            sensor.stop()
            assertFalse(sensor.setPowerZoneAutoFollow(200))
            assertFalse(sensor.setResistance(40))
            assertFalse(sensor.setPzafRampUpRate(50))
            assertTrue(sensor.disablePowerZoneAutoFollow())
            assertTrue(sensor.disablePowerZoneAutoFollow())
            verify(exactly = 2) { binder.transact(52, data, reply, 0) }
            verify(exactly = 1) { binder.transact(51, data, reply, 0) }
        } finally {
            sensor.stop()
            finishRead.countDown()
            assertTrue(recycled.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun `stop releases in flight enable and rejects a queued late enable`() {
        val reading = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val enabling = CountDownLatch(1)
        val finishEnable = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<Int>())
        every { binder.transact(14, data, reply, 0) } answers {
            reading.countDown(); finishRead.await(); false
        }
        every { binder.transact(51, data, reply, 0) } answers {
            events.add(51)
            enabling.countDown()
            finishEnable.await()
            true
        }
        every { binder.transact(52, data, reply, 0) } answers { events.add(52); true }
        val sensor = BikePlusCombinedSensor(binder, clock = { 1234L })
        val firstResult = AtomicReference<Boolean>()
        val lateResult = AtomicReference<Boolean>()
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = mutableListOf<Thread>()
        fun worker(block: () -> Unit) = thread {
            try { block() } catch (error: Throwable) { failures.add(error) }
        }.also { workers.add(it) }
        try {
            sensor.start()
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            worker { firstResult.set(sensor.setPowerZoneAutoFollow(180)) }
            assertTrue(enabling.await(2, TimeUnit.SECONDS))
            val stopping = worker { sensor.stop() }
            awaitBlocked(stopping)
            val late = worker { lateResult.set(sensor.setPowerZoneAutoFollow(250)) }
            awaitBlocked(late)
            finishEnable.countDown()
            workers.forEach { it.join(2000); assertFalse("Worker did not finish", it.isAlive) }
            assertTrue(failures.toString(), failures.isEmpty())
            assertEquals(true, firstResult.get())
            assertEquals(false, lateResult.get())
            assertEquals(listOf(51, 52), events.toList())
            assertFalse(sensor.setResistance(40))
        } finally {
            finishEnable.countDown()
            finishRead.countDown()
            workers.forEach { it.join(2000) }
            sensor.stop()
            assertTrue(recycled.await(2, TimeUnit.SECONDS))
        }
    }

    private fun awaitBlocked(worker: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (worker.state != Thread.State.BLOCKED && worker.isAlive && System.nanoTime() < deadline) Thread.yield()
        assertEquals("Worker must have reached the held command monitor", Thread.State.BLOCKED, worker.state)
    }
}
