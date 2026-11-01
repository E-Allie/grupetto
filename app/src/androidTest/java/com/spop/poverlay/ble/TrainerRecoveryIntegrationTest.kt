package com.spop.poverlay.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.spop.poverlay.control.*
import com.spop.poverlay.dircon.DirConGattBridge
import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Android GATT and FTMS adapters with a fake actuator; never starts a radio or real motor. */
class TrainerRecoveryIntegrationTest {
    @Test fun timeoutRecoveryIsExplicitOwnedAndTokenedAndDoesNotWriteAnOldTarget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var now = 0L
        val writes = mutableListOf<Int>()
        val control = BikeControl(true, { now }) { writes += it; true }
        val sensor = object : SensorInterface {
            override val power = flowOf(100f)
            override val cadence = flowOf(85f)
            override val resistance = flowOf(30f)
        }
        val server = BleServer(context, context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager, sensor)
        try {
            val ftms = FitnessMachineService(server, control = control)
            @Suppress("UNCHECKED_CAST")
            val services = BleServer::class.java.getDeclaredField("registeredServices").apply { isAccessible = true }
                .get(server) as MutableList<BaseBleService>
            services += ftms
            val bridge = BleServer::class.java.getDeclaredField("dirConBridge").apply { isAccessible = true }
                .get(server) as DirConGattBridge
            control.acceptSample(BikeSample(100f,85f,30,0))
            assertEquals(1,ftms.handleControl("dircon:a",byteArrayOf(0))[2].toInt())
            assertEquals(1,ftms.handleControl("dircon:a",byteArrayOf(4,244.toByte(),1))[2].toInt())
            now=3100;control.acceptSample(BikeSample(100f,85f,30,now))
            ftms.onControlStateChanged(control.state.value)
            val status = ftms.service.getCharacteristic(TrainerProtocol.STATUS_UUID).value
            assertEquals(12,status.size); assertEquals(1,status[5].toInt()); assertEquals(7,status[6].toInt())
            val generation=ByteBuffer.wrap(status,7,4).int
            val recovery=ByteBuffer.allocate(5).put(1).putInt(generation).array()
            assertFalse(bridge.writeCharacteristic(TrainerProtocol.RECOVER_UUID,recovery))
            assertFalse(bridge.writeCharacteristic("dircon:b",TrainerProtocol.RECOVER_UUID,recovery))
            assertEquals(1,ftms.handleControl("dircon:a",byteArrayOf(1))[2].toInt())
            assertEquals(5,ftms.handleControl("dircon:a",byteArrayOf(4,44,1))[2].toInt())
            assertTrue(bridge.writeCharacteristic("dircon:a",TrainerProtocol.RECOVER_UUID,recovery))
            assertEquals(listOf(50),writes)
            assertFalse(bridge.writeCharacteristic("dircon:a",TrainerProtocol.RECOVER_UUID,recovery))
            assertEquals(1,ftms.handleControl("dircon:a",byteArrayOf(4,44,1))[2].toInt())
            assertEquals(listOf(50,30),writes)
        } finally { control.close(); server.coroutineContext.cancel() }
    }
    @Test fun nearTargetDoesNotSuspendButANewKnobTargetDoes() {
        var now=0L
        val c=BikeControl(true,{now}) {true}
        try {
            c.acceptSample(BikeSample(100f,85f,30,0,30));c.procedure("a",byteArrayOf(0))
            c.procedure("a",byteArrayOf(4,244.toByte(),1))
            for(t in listOf(1000L,1200L,3200L)) {now=t;c.acceptSample(BikeSample(150f,90f,49,t,40))}
            assertFalse(c.state.value.suspended) // Physical travel is not a target override.
            now=3400;c.acceptSample(BikeSample(150f,90f,48,now,48))
            assertEquals(ControlFault.RiderOverride,c.state.value.fault)
            assertFalse(c.recoverRemotely("a",c.state.value.faultGeneration))
        } finally {c.close()}
    }
}
