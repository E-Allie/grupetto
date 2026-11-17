package com.spop.poverlay.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.spop.poverlay.dircon.DirConGattBridge
import com.spop.poverlay.dircon.DirConServer
import com.spop.poverlay.sensor.interfaces.SensorInterface
import io.mockk.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class BleTransportContinuityTest {
    private lateinit var context: Context
    private lateinit var manager: BluetoothManager
    private lateinit var adapter: BluetoothAdapter
    private lateinit var gatt: BluetoothGattServer
    private lateinit var tcp: DirConServer
    private lateinit var sensor: SensorInterface
    private lateinit var ble: BleServer
    private lateinit var fitnessService: BaseBleService
    private lateinit var newGattService: BaseBleService

    @Before
    fun setUp() {
        mockkStatic(ContextCompat::class)
        mockkConstructor(IntentFilter::class)
        context = mockk(relaxed = true)
        every { ContextCompat.checkSelfPermission(context, any()) } returns PackageManager.PERMISSION_GRANTED
        manager = mockk(relaxed = true)
        adapter = mockk(relaxed = true)
        every { manager.adapter } returns adapter
        every { adapter.state } returns BluetoothAdapter.STATE_ON
        every { adapter.name } returns "Grupetto"
        every { adapter.bluetoothLeAdvertiser } returns mockk(relaxed = true)
        gatt = mockk(relaxed = true)
        every { gatt.addService(any()) } returns true
        every { manager.openGattServer(context, any()) } returns gatt
        sensor = mockk(relaxed = true)
        every { sensor.bikeControl } returns null
        every { sensor.power } returns flowOf(0f)
        every { sensor.cadence } returns flowOf(0f)
        every { sensor.speed } returns flowOf(0f)
        every { sensor.resistance } returns flowOf(0f)
        every { sensor.incline } returns flowOf(0f)
        ble = BleServer(context, manager, sensor, FakeTimeProvider())
        mockkObject(ble, recordPrivateCalls = true)
        tcp = mockk(relaxed = true)
        set("dirConDelegate", lazyOf(tcp))
        set("isDirConOnlyStarted", true)
        fitnessService = logicalService(FitnessMachineConstants.ServiceUUID)
        newGattService = mockk(relaxed = true)
        every { newGattService.service.uuid } returns FitnessMachineConstants.ServiceUUID
        every { ble["baseServices"]() } returns listOf(newGattService)
    }

    @After
    fun tearDown() {
        ble.coroutineContext.cancel()
        unmockkAll()
    }

    @Test
    fun `missing Bluetooth adapter leaves existing DIRCON running`() {
        every { manager.adapter } returns null
        ble.start()
        assertDirConOnlyContinues()
    }

    @Test
    fun `missing Bluetooth permission leaves existing DIRCON running`() {
        every { ContextCompat.checkSelfPermission(context, any()) } returns PackageManager.PERMISSION_DENIED
        ble.start()
        assertDirConOnlyContinues()
        verify(exactly = 0) { manager.openGattServer(any(), any()) }
    }

    @Test
    fun `disabled Bluetooth leaves existing DIRCON running`() {
        every { adapter.state } returns BluetoothAdapter.STATE_OFF
        ble.start()
        assertDirConOnlyContinues()
    }

    @Test
    fun `unavailable advertiser leaves existing DIRCON running`() {
        every { adapter.bluetoothLeAdvertiser } returns null
        ble.start()
        assertDirConOnlyContinues()
    }

    @Test
    fun `null GATT registration leaves existing DIRCON running`() {
        every { manager.openGattServer(context, any()) } returns null
        ble.start()
        assertDirConOnlyContinues()
    }

    @Test
    fun `permission lost while opening GATT leaves existing DIRCON running`() {
        every { manager.openGattServer(context, any()) } throws SecurityException("Permission revoked")
        ble.start()
        assertDirConOnlyContinues()
    }

    @Test
    fun `successful BLE promotion preserves DIRCON during registration and publishes fresh services`() {
        ble.start()

        assertSame(gatt, get("gattServer"))
        assertEquals(true, get("isServerStarted"))
        assertEquals(false, get("isDirConOnlyStarted"))
        assertSame(tcp, dirConOwner())
        assertEquals(listOf(fitnessService), services())
        verify(exactly = 1) { gatt.addService(newGattService.service) }
        // Retiring and registering GATT never exposes an empty service list to DIRCON.
        // Avoid starting real Android advertising in this local JVM test.
        every { adapter.state } returns BluetoothAdapter.STATE_OFF
        ble.onServiceAdded(0, newGattService.service)
        assertEquals(listOf(newGattService), services())
        verify(exactly = 0) { tcp.stop() }
    }

    @Test
    fun `failure after opening GATT closes Bluetooth but preserves existing TCP sessions`() {
        every { context.registerReceiver(any(), any<IntentFilter>()) } throws
            IllegalStateException("Receiver registration failed")

        ble.start()

        assertDirConOnlyContinues()
        assertNull(get("gattServer"))
        verify(exactly = 1) { gatt.close() }
        assertTrue((get("sensorDataJob") as Job).isActive)
    }

    @Test
    fun `GATT service registration exception recovers DIRCON without replacing services`() {
        every { gatt.addService(any()) } throws SecurityException("Permission revoked")

        ble.start()

        assertDirConOnlyContinues()
        assertNull(get("gattServer"))
        verify(exactly = 1) { gatt.close() }
    }

    @Test
    fun `late unrelated service callback cannot cancel the current registration`() {
        ble.start()
        val timeout = get("serviceAddTimeoutJob") as Job
        val unrelated = mockk<android.bluetooth.BluetoothGattService>()
        every { unrelated.uuid } returns HeartRateConstants.ServiceUUID

        ble.onServiceAdded(0, unrelated)

        assertSame(newGattService, get("currentlyRegisteringService"))
        assertSame(timeout, get("serviceAddTimeoutJob"))
        assertTrue(timeout.isActive)
        assertEquals(listOf(fitnessService), services())
        every { adapter.state } returns BluetoothAdapter.STATE_OFF
        ble.onServiceAdded(0, newGattService.service)
        assertEquals(listOf(newGattService), services())
        verify(exactly = 0) { tcp.stop() }
    }

    @Test
    fun `failed GATT restart keeps live DIRCON telemetry flowing`() = runBlocking {
        val resistance = MutableStateFlow(20f)
        every { sensor.resistance } returns resistance
        val packets = Channel<Float>(Channel.UNLIMITED)
        every { fitnessService.onSensorDataUpdated(any(), any(), any(), any(), any()) } answers {
            packets.trySend(arg(3))
            Unit
        }
        set("isServerStarted", true)
        set("isDirConOnlyStarted", false)
        set("gattServer", gatt)
        invoke("startSensorDataUpdates")
        val telemetryJob = get("sensorDataJob") as Job
        try {
            assertEquals(20f, withTimeout(5_000) { packets.receive() })
            every { manager.openGattServer(context, any()) } returns null

            invoke("restartGattAndAdvertising", "test recovery")
            resistance.value = 30f

            assertEquals(30f, withTimeout(5_000) { packets.receive() })
            assertSame(telemetryJob, get("sensorDataJob"))
            assertTrue(telemetryJob.isActive)
            assertEquals(listOf(fitnessService), services())
            verify(exactly = 0) { tcp.stop() }
            verify(exactly = 1) { gatt.close() }
        } finally {
            packets.close()
        }
    }

    @Test
    fun `heart rate discovery updates preserve DIRCON sockets with BLE active`() {
        set("isServerStarted", true)
        set("isDirConOnlyStarted", false)
        set("gattServer", gatt)
        assertHeartRateDiscoveryChangesWithoutDisconnecting()
    }

    @Test
    fun `heart rate discovery also updates during DIRCON only operation`() {
        assertHeartRateDiscoveryChangesWithoutDisconnecting()
    }

    @Test
    @Suppress("DEPRECATION")
    fun `targeted DIRCON notifications preserve the intended controller identity`() {
        val characteristic = mockk<android.bluetooth.BluetoothGattCharacteristic>()
        val value = byteArrayOf(0xff.toByte())
        every { characteristic.uuid } returns FitnessMachineConstants.MachineStatusUUID
        every { characteristic.value } returns value

        ble.notifyDirConCharacteristicChanged(characteristic, "dircon:previous-owner")
        ble.notifyDirConCharacteristicChanged(characteristic)

        verify(exactly = 1) {
            tcp.notifyCharacteristicChanged(FitnessMachineConstants.MachineStatusUUID, value, "dircon:previous-owner")
        }
        verify(exactly = 1) {
            tcp.notifyCharacteristicChanged(FitnessMachineConstants.MachineStatusUUID, value, null)
        }
    }

    @Test
    fun `repeated Bluetooth disable leaves active DIRCON only sessions untouched`() {
        invoke("startSensorDataUpdates")
        invoke("startHeartRateServiceWatcher")
        val telemetry = get("sensorDataJob") as Job
        val heartRateWatcher = get("heartRateServiceWatcherJob") as Job
        val generation = get("gattServerGeneration")

        repeat(3) { ble.stopBluetoothTransport() }

        assertDirConOnlyContinues()
        assertSame(telemetry, get("sensorDataJob"))
        assertSame(heartRateWatcher, get("heartRateServiceWatcherJob"))
        assertTrue(telemetry.isActive)
        assertTrue(heartRateWatcher.isActive)
        assertEquals(generation, get("gattServerGeneration"))
        verify(exactly = 0) { context.unregisterReceiver(any()) }
    }

    @Test
    fun `disabling active Bluetooth preserves DIRCON services and running telemetry jobs`() {
        set("isServerStarted", true)
        set("isDirConOnlyStarted", false)
        set("gattServer", gatt)
        invoke("startSensorDataUpdates")
        invoke("startHeartRateServiceWatcher")
        val telemetry = get("sensorDataJob") as Job
        val heartRateWatcher = get("heartRateServiceWatcherJob") as Job
        val generation = get("gattServerGeneration") as Long

        ble.stopBluetoothTransport()

        assertDirConOnlyContinues()
        assertNull(get("gattServer"))
        assertEquals(generation + 1L, get("gattServerGeneration"))
        assertSame(telemetry, get("sensorDataJob"))
        assertSame(heartRateWatcher, get("heartRateServiceWatcherJob"))
        assertTrue(telemetry.isActive)
        assertTrue(heartRateWatcher.isActive)
        verify(exactly = 1) { gatt.close() }

        ble.stopBluetoothTransport()

        assertEquals(generation + 1L, get("gattServerGeneration"))
        verify(exactly = 1) { gatt.close() }
        verify(exactly = 0) { tcp.stop() }
    }

    @Test
    fun `disabling Bluetooth with DIRCON disabled performs full transport cleanup`() {
        set("isServerStarted", true)
        set("isDirConOnlyStarted", false)
        set("gattServer", gatt)
        set("dirConTransportEnabled", false)
        invoke("startSensorDataUpdates")
        invoke("startHeartRateServiceWatcher")
        val telemetry = get("sensorDataJob") as Job
        val heartRateWatcher = get("heartRateServiceWatcherJob") as Job

        ble.stopBluetoothTransport()

        assertNull(get("gattServer"))
        assertSame(tcp, dirConOwner())
        assertEquals(false, get("isServerStarted"))
        assertEquals(false, get("isDirConOnlyStarted"))
        assertTrue(services().isEmpty())
        assertTrue(telemetry.isCancelled)
        assertTrue(heartRateWatcher.isCancelled)
        verify(exactly = 1) { gatt.close() }
        verify(exactly = 1) { tcp.stop() }
    }

    @Test
    fun `full app stop still closes independent DIRCON sessions`() {
        invoke("startSensorDataUpdates")
        val telemetry = get("sensorDataJob") as Job

        ble.stop()

        assertSame(tcp, dirConOwner())
        assertEquals(false, get("isDirConOnlyStarted"))
        assertTrue(services().isEmpty())
        assertTrue(telemetry.isCancelled)
        verify(exactly = 1) { tcp.stop() }
    }

    private fun assertHeartRateDiscoveryChangesWithoutDisconnecting() {
        logicalService(HeartRateConstants.ServiceUUID)
        val bridge = get("dirConBridge") as DirConGattBridge
        assertEquals(listOf(FitnessMachineConstants.ServiceUUID), bridge.services().map { it.uuid })

        invoke("updateHeartRateServiceRegistration", true)
        assertTrue(bridge.services().any { it.uuid == HeartRateConstants.ServiceUUID })
        invoke("updateHeartRateServiceRegistration", false)
        assertFalse(bridge.services().any { it.uuid == HeartRateConstants.ServiceUUID })

        assertSame(tcp, dirConOwner())
        verify(exactly = 0) { tcp.stop() }
    }

    private fun dirConOwner() = (get("dirConDelegate") as Lazy<*>).value

    private fun assertDirConOnlyContinues() {
        assertSame(tcp, dirConOwner())
        assertEquals(true, get("isDirConOnlyStarted"))
        assertEquals(false, get("isServerStarted"))
        assertEquals(listOf(fitnessService), services())
        verify(exactly = 0) { tcp.stop() }
    }

    private fun logicalService(uuid: UUID): BaseBleService = mockk<BaseBleService>(relaxed = true).also {
        every { it.service.uuid } returns uuid
        every { it.service.characteristics } returns emptyList()
        services().add(it)
    }

    @Suppress("UNCHECKED_CAST")
    private fun services() = get("registeredServices") as MutableList<BaseBleService>

    private fun get(name: String): Any? = BleServer::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }.get(ble)

    private fun set(name: String, value: Any?) = BleServer::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }.set(ble, value)

    private fun invoke(name: String, vararg args: Any): Any? = BleServer::class.java.declaredMethods
        .single { it.name == name }.apply { isAccessible = true }.invoke(ble, *args)
}
