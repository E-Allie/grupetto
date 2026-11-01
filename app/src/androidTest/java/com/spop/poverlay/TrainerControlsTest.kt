package com.spop.poverlay

import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.spop.poverlay.control.BikeControl
import com.spop.poverlay.control.BikeSample
import com.spop.poverlay.ui.theme.PTONOverlayTheme
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File

class TrainerControlsTest {
    @get:Rule val compose = createComposeRule()
    private val writes = mutableListOf<Int>()
    private val control = BikeControl(true, { 1000L }) { writes.add(it); true }
    @After fun closeController() { control.close() }

    private fun setup() {
        control.acceptSample(BikeSample(150f, 80f, 40, 1000))
        control.setSimulationSettings(com.spop.poverlay.sim.SimulationSettings(enabled = true))
        compose.setContent { PTONOverlayTheme {
            val state = control.state.collectAsState().value
            SettingsDialog("Bike+ / CrossTrainer", {}) {
                TrainerControls(state, { control.localErg(it) }, { control.localSimulation(it) },
                    { control.localManual() }, { control.localResistance(it) }, control::tune,
                    control::setErgMode, control::shift, { control.resumeLocally() }, control::pauseLocally)
            }
        } }
    }
    private fun setSlider(label: String, value: Float) {
        compose.onNodeWithContentDescription(label).performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(value) }
    }
    private fun selected(label: String) {
        listOf("ERG", "Sim", "Manual").forEach {
            if (it == label) compose.onNodeWithText(it).assertIsSelected()
            else compose.onNodeWithText(it).assertIsNotSelected()
        }
    }
    private fun screenshot(name: String) {
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun modesShowMatchingControlsAndShiftsUpdateTheirLiveTargets() {
        setup()
        selected("Manual")
        compose.onNodeWithText("ERG").performClick()
        selected("ERG")
        compose.onNodeWithText("Software gain").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Resistance per shift").assertDoesNotExist()
        setSlider("ERG target", 200f); setSlider("Watts per shift", 15f)
        compose.runOnIdle { control.shift(1) }
        compose.onNodeWithText("215 W").performScrollTo().assertIsDisplayed()
        screenshot("trainer-erg.png")
        compose.onNodeWithText("Sim").performScrollTo().performClick()
        selected("Sim")
        compose.onNodeWithText("Watts per shift").assertDoesNotExist()
        compose.onNodeWithText("Incline sensitivity").assertDoesNotExist()
        compose.onNodeWithText("Resistance per shift").assertDoesNotExist()
        setSlider("Incline", 5f)
        compose.onNodeWithText("Higher gear").performScrollTo().performClick()
        assertEquals(13, control.state.value.gear)
        compose.onNodeWithText("Gear 13 / 24").assertIsDisplayed()
        screenshot("trainer-sim.png")
        compose.onNodeWithText("Manual").performScrollTo().performClick()
        selected("Manual")
        compose.onNodeWithText("Incline").assertDoesNotExist()
        setSlider("Resistance per shift", 4f)
        setSlider("Resistance", 50f)
        compose.runOnIdle { control.shift(-1) }
        compose.onNodeWithText("46 points").assertIsDisplayed()
        screenshot("trainer-manual.png")
    }
    @Test fun remoteCommandsReplaceOpenDialogTargetsAndExternalControlFlag() {
        setup()
        for (client in listOf("ble:a", "dircon:b")) {
            val transport = if (client.startsWith("ble")) "Bluetooth" else "DirCon"
            compose.runOnIdle {
                assertEquals(1, control.procedure(client, byteArrayOf(0)).result)
                control.procedure(client, byteArrayOf(5, 225.toByte(), 0))
            }
            selected("ERG")
            compose.onNodeWithText("225 W").assertIsDisplayed()
            compose.onNodeWithText("External control · $transport").performScrollTo().assertIsDisplayed()
            screenshot("trainer-external-${transport.lowercase()}.png")
            compose.runOnIdle { control.procedure(client, byteArrayOf(0x11, 0, 0, 12, 254.toByte(), 40, 51)) }
            selected("Sim")
            compose.onNodeWithText("-5.0%").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { control.procedure(client, byteArrayOf(4, 38, 2)) }
            selected("Manual")
            compose.onNodeWithText("55 points").assertIsDisplayed()
            compose.runOnIdle { control.disconnect(client) }
            compose.onNodeWithText("External control · $transport").assertDoesNotExist()
        }
    }
    @Test fun localStopRequiresExplicitResumeAndErgChoiceRemainsSelectable() {
        setup()
        compose.onNodeWithText("ERG").performClick()
        compose.onNodeWithText("Software").performClick()
        assertEquals(com.spop.poverlay.erg.ErgMode.Host, control.state.value.ergMode)
        compose.onNodeWithText("Stop control").performScrollTo().performClick()
        compose.onNodeWithText("Resume control").assertIsDisplayed()
        assertTrue(controlNeedsResume(control.state.value))
        compose.runOnIdle { control.localErg(200) }
        assertTrue(controlNeedsResume(control.state.value))
        compose.onNodeWithText("Resume control").performClick()
        compose.runOnIdle { assertFalse(controlNeedsResume(control.state.value)) }
        compose.onNodeWithText("Resume control").assertDoesNotExist()
    }

}
