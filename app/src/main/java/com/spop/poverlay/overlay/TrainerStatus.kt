package com.spop.poverlay.overlay

import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.control.ControlMode
import com.spop.poverlay.control.ControlState
import com.spop.poverlay.controlNeedsResume
import com.spop.poverlay.ergPathLabel
import com.spop.poverlay.simulationStatus
import com.spop.poverlay.trainerTargetSummary

internal val TrainerStatusHeight = 88.dp

internal fun showTrainerStatus(state: ControlState): Boolean =
    state.connected && (state.mode != ControlMode.Manual || controlNeedsResume(state))

/** A bounded row keeps control recovery available above the foreground training app. */
@Composable
internal fun TrainerStatus(state: ControlState, onResume: () -> Unit, onStop: () -> Unit) {
    val width = minOf(640.dp, (LocalConfiguration.current.screenWidthDp - 24).coerceAtLeast(1).dp)
    val regulating = state.mode == ControlMode.Erg || state.mode == ControlMode.Simulation
    val summary = trainerTargetSummary(state) + if (regulating) " · ${ergPathLabel(state.ergPath)}" else ""
    val decision = state.simulationDecision
    val detail = if (state.mode == ControlMode.Simulation) simulationStatus(state) else state.capability
    val message = if (state.mode == ControlMode.Simulation) decision?.reason ?: state.message else state.message
    Row(Modifier.width(width).height(TrainerStatusHeight).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(summary, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                color = Color(0xFFB7F7DF), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrBlank()) Text(detail, fontSize = 12.sp, lineHeight = 15.sp,
                color = Color(0xFFCDD8E4), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(message, fontSize = 12.sp, lineHeight = 15.sp,
                color = if (controlNeedsResume(state)) Color(0xFFFFD48A) else Color(0xFF9EAEC0),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (controlNeedsResume(state)) {
            Button(onResume, Modifier.width(76.dp).heightIn(min = 48.dp).semantics {
                contentDescription = "Resume trainer control"
            }, contentPadding = PaddingValues(8.dp), colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(0xFFB7F7DF), contentColor = Color(0xFF10251F))) {
                Text("Resume", fontSize = 13.sp)
            }
        }
        OutlinedButton(onStop, Modifier.width(64.dp).heightIn(min = 48.dp).semantics {
            contentDescription = "Stop trainer control"
        }, contentPadding = PaddingValues(8.dp), colors = ButtonDefaults.outlinedButtonColors(
            backgroundColor = Color.Transparent, contentColor = Color(0xFFFFD48A))) {
            Text("Stop", fontSize = 13.sp)
        }
    }
}
