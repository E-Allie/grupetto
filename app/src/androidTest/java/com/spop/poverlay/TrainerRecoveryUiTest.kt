package com.spop.poverlay

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.spop.poverlay.control.*
import com.spop.poverlay.erg.SoftwareErgAlgorithm
import com.spop.poverlay.overlay.TrainerFaultNotice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TrainerRecoveryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactFaultNoticeExplainsWhichAppCanResumeAndOnlyOpensControls() {
        var opened = false
        val state = mutableStateOf(ControlState(connected = true, suspended = true, fault = ControlFault.ResistanceTimeout))
        compose.setContent { MaterialTheme { TrainerFaultNotice(state.value) { opened = true } } }
        compose.onNodeWithText("Resistance adjustment failed · Resume in Scatto or Grupetto").assertIsDisplayed()
        compose.onNodeWithText("Controls").performClick()
        assertTrue(opened)
        assertTrue(state.value.suspended)
        compose.runOnIdle { state.value = state.value.copy(fault = ControlFault.RiderOverride) }
        compose.onNodeWithText("Control stopped · Resume in Grupetto").assertIsDisplayed()
    }

    @Test fun softwareAlgorithmSelectionIsIndependentOfNativeAutoSelection() {
        val writes = mutableListOf<Int>()
        val c = BikeControl(true, { 1000L }) { writes += it; true }
        c.acceptSample(BikeSample(150f,85f,40,1000))
        try {
            compose.setContent { MaterialTheme {
                val state = c.state.collectAsState().value
                SettingsDialog("Trainer", {}) {
                    TrainerControls(state, {}, {}, {}, {}, { _,_,_,_ -> },
                        onSoftwareAlgorithm = c::setSoftwareAlgorithm)
                }
            } }
            compose.onNodeWithText("Firmware table").performScrollTo().assertIsSelected()
            compose.onNodeWithText("Upstream learned model").performScrollTo().performClick().assertIsSelected()
            assertEquals(SoftwareErgAlgorithm.UpstreamModel,c.state.value.softwareAlgorithm)
            assertEquals(com.spop.poverlay.erg.ErgMode.Auto,c.state.value.ergMode)
            assertTrue(writes.isEmpty())
        } finally {c.close()}
    }
}
