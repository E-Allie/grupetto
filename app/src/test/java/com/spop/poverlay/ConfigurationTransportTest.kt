package com.spop.poverlay

import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.spop.poverlay.ble.BleServer
import com.spop.poverlay.erg.ErgCoordinator
import com.spop.poverlay.erg.ErgState
import com.spop.poverlay.releases.ReleaseChecker
import com.spop.poverlay.sensor.heartrate.HeartRateManager
import com.spop.poverlay.sim.SimulationSettings
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ConfigurationTransportTest {
    private val application = mockk<GrupettoApplication>(relaxed = true)
    private val repository = mockk<ConfigurationRepository>(relaxed = true)
    private val bleServer = mockk<BleServer>(relaxed = true)
    private val erg = mockk<ErgCoordinator>(relaxed = true)
    private val simulation = MutableStateFlow(SimulationSettings())
    private val bleEnabled = MutableStateFlow(true)
    private val dirConEnabled = MutableStateFlow(true)

    @Before
    fun setup() {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED
        mockkObject(HeartRateManager)
        every { HeartRateManager.start(any()) } just Runs
        every { application.bleServer } returns bleServer
        every { application.ergController } returns erg
        every { erg.state } returns MutableStateFlow(ErgState())
        every { repository.bleTxEnabled } returns bleEnabled
        every { repository.dirConEnabled } returns dirConEnabled
        every { repository.simulationSettings } returns simulation
        every { repository.setSimulationSettings(any()) } answers { simulation.value = firstArg() }
    }

    @After
    fun teardown() {
        unmockkObject(HeartRateManager)
        unmockkStatic(ContextCompat::class)
    }

    private fun model() = ConfigurationViewModel(application, repository, mockk<ReleaseChecker>(relaxed = true))

    @Test
    fun `opening configuration does not tear down a running BLE session`() {
        model()

        verify(exactly = 0) { bleServer.stop() }
        verify(exactly = 1) { bleServer.setDirConTransportEnabled(true) }
        verify(exactly = 1) { bleServer.start() }
    }

    @Test
    fun `SIM capability toggles rebuild services using the newly saved setting`() {
        val model = model()
        val advertisedSupport = mutableListOf<Boolean>()
        every { bleServer.start() } answers { advertisedSupport += simulation.value.enabled }

        for (enabled in listOf(true, false)) {
            clearMocks(bleServer, answers = false)
            model.onSimulationSettingsChanged(simulation.value.copy(enabled = enabled))
            verifySequence {
                bleServer.stop()
                bleServer.setDirConTransportEnabled(true)
                bleServer.start()
            }
        }
        assertEquals(listOf(true, false), advertisedSupport)
    }

    @Test
    fun `rider weight and SIM power edits preserve the active transport`() {
        val model = model()
        clearMocks(bleServer, answers = false)
        val changed = simulation.value.copy(riderMassKg = 82.0, maxSimWatts = 180)

        model.onSimulationSettingsChanged(changed)

        assertEquals(changed, simulation.value)
        verify { bleServer wasNot Called }
    }

    @Test
    fun `DIRCON only mode refreshes SIM capabilities without starting Bluetooth`() {
        bleEnabled.value = false
        val model = model()
        clearMocks(bleServer, answers = false)
        val advertisedSupport = mutableListOf<Boolean>()
        every { bleServer.setDirConTransportEnabled(true) } answers {
            advertisedSupport += simulation.value.enabled
        }

        model.onSimulationSettingsChanged(simulation.value.copy(enabled = true))

        verifySequence {
            bleServer.stop()
            bleServer.setDirConTransportEnabled(true)
        }
        assertEquals(listOf(true), advertisedSupport)
        verify(exactly = 0) { bleServer.start() }
    }
}
