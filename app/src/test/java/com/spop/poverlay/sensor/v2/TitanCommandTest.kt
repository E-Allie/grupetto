package com.spop.poverlay.sensor.v2

import android.os.IBinder
import android.os.Parcel
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TitanCommandTest {
    private val binder = mockk<IBinder>()
    private val data = mockk<Parcel>(relaxed = true)
    private val reply = mockk<Parcel>(relaxed = true)

    @Before fun setup() {
        mockkStatic(Parcel::class)
        every { Parcel.obtain() } returnsMany listOf(data, reply)
        every { binder.transact(any(), data, reply, 0) } returns true
    }
    @After fun cleanup() { unmockkStatic(Parcel::class) }

    @Test fun `enable uses checked two way native command`() {
        assertTrue(writeTitanCommand(binder, TitanCommand.Enable, 150))
        verifyOrder {
            data.writeInterfaceToken(SERVICE_ACTION)
            data.writeInt(150)
            binder.transact(51, data, reply, 0)
            reply.readException()
            data.recycle()
            reply.recycle()
        }
        verify(exactly = 0) { reply.readInt() }
    }

    @Test fun `disable has no integer argument`() {
        assertTrue(writeTitanCommand(binder, TitanCommand.Disable))
        verifyOrder {
            data.writeInterfaceToken(SERVICE_ACTION)
            binder.transact(52, data, reply, 0)
            reply.readException()
            data.recycle()
            reply.recycle()
        }
        verify(exactly = 0) { data.writeInt(any()); reply.readInt() }
    }

    @Test fun `configuration uses service boolean result`() {
        for ((command, value) in listOf(TitanCommand.RampUp to 50, TitanCommand.RampDown to 8,
            TitanCommand.MaxResistance to 90, TitanCommand.MinRpm to 40)) {
            every { Parcel.obtain() } returnsMany listOf(data, reply)
            every { reply.readInt() } returns 1
            assertTrue(command.name, writeTitanCommand(binder, command, value))
            verify { binder.transact(command.transaction, data, reply, 0) }
            every { Parcel.obtain() } returnsMany listOf(data, reply)
            every { reply.readInt() } returns 0
            assertFalse(command.name, writeTitanCommand(binder, command, value))
        }
    }

    @Test fun `rejected transaction does not read a reply`() {
        every { binder.transact(any(), data, reply, 0) } returns false
        assertFalse(writeTitanCommand(binder, TitanCommand.Enable, 150))
        verify(exactly = 0) { reply.readException(); reply.readInt() }
        verify { data.recycle(); reply.recycle() }
    }

    @Test fun `service security exception is a failed command`() {
        every { reply.readException() } throws SecurityException("Unsupported platform")
        assertFalse(writeTitanCommand(binder, TitanCommand.Disable))
        verify { data.recycle(); reply.recycle() }
    }

    @Test fun `binder death is a failed command`() {
        every { binder.transact(any(), data, reply, 0) } throws IllegalStateException("Disconnected")
        assertFalse(writeTitanCommand(binder, TitanCommand.Enable, 150))
        verify { data.recycle(); reply.recycle() }
    }

    @Test fun `out of range and wrong shape arguments do not transact`() {
        for (command in TitanCommand.values()) {
            if (command.range == null) assertFalse(writeTitanCommand(binder, command, 1)) else {
                assertFalse(writeTitanCommand(binder, command))
                assertFalse(writeTitanCommand(binder, command, command.range.first - 1))
                assertFalse(writeTitanCommand(binder, command, command.range.last + 1))
            }
        }
        verify(exactly = 0) { Parcel.obtain(); binder.transact(any(), any(), any(), any()) }
    }

    @Test fun `inactive sensor rejects every motor command without allocating parcels`() {
        val sensor = BikePlusCombinedSensor(binder, clock = { 1000L })
        assertFalse(sensor.setResistance(40))
        assertFalse(sensor.setPowerZoneAutoFollow(150))
        assertFalse(sensor.disablePowerZoneAutoFollow())
        assertFalse(sensor.setPzafRampUpRate(50))
        assertFalse(sensor.setPzafRampDownRate(8))
        assertFalse(sensor.setPzafMaxResistance(90))
        assertFalse(sensor.setPzafMinUpdateRpm(40))
        verify(exactly = 0) { Parcel.obtain(); binder.transact(any(), any(), any(), any()) }
    }
}
