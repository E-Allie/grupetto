package com.spop.poverlay.sensor.v2

import android.os.Parcel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifySequence
import org.junit.Assert.*
import org.junit.Test

class BikeStatusParcelTest {
    @Test fun `reads only the firmware field and packet after core telemetry`() {
        val parcel = mockk<Parcel>()
        every { parcel.readString() } returns "5.11"
        every { parcel.createByteArray() } returns titanPacket(pzafSetpoint = 180)
        assertEquals(180, readBikeStatusPacket(parcel)!!.pzafSetpointWatts)
        verifySequence { parcel.readString(); parcel.createByteArray() }
    }

    @Test fun `missing or malformed native packet does not prevent core telemetry`() {
        val parcel = mockk<Parcel>()
        every { parcel.readString() } returns null
        every { parcel.createByteArray() } returns ByteArray(256)
        assertNull(readBikeStatusPacket(parcel))
        every { parcel.createByteArray() } throws IllegalStateException("Partial old parcel")
        assertNull(readBikeStatusPacket(parcel))
    }
}
