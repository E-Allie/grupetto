package com.spop.poverlay.overlay.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.GrupettoApplication
import com.spop.poverlay.erg.ErgPath
import com.spop.poverlay.erg.ErgState

/** Same footprint as the shifter: a label, a number, and a line of state. */
val ErgButtonWidth = 130.dp

private val HoldingColor = Color(0xFF4CAF50)
private val ResumeColor = Color(0xFFFF9800)
private val IdleColor = Color(0x66FFFFFF)
private val ButtonBackground = Color(0x33FFFFFF)

/**
 * Stop and resume ERG from the bike, without going through the controller app.
 *
 * This exists because of how the controller gives up. PZAF drops out on its own
 * for six reasons -- the knob most often, since that is the rider's escape hatch
 * and turning it is how you get out of an interval that is too hard. Nothing
 * re-arms it automatically, deliberately: re-grabbing the brake would defeat the
 * escape. But a commercial training app will not send another target until the
 * next block, so without this the rest of the interval is unheld.
 *
 * [ErgState.targetWatts] outlives the stand-down for exactly this reason. The
 * number below is what the rider was holding, and tapping it picks that back up.
 */
@Composable
fun ErgButton(modifier: Modifier = Modifier) {
    // Absent under @Preview, where there is no Application of ours to reach.
    val application = LocalContext.current.applicationContext as? GrupettoApplication
    val controller = application?.ergController ?: return
    val state by controller.state.collectAsState()

    val hasTarget = state.targetWatts > 0
    val color = when {
        state.active -> HoldingColor
        hasTarget -> ResumeColor
        else -> IdleColor
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "ERG",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .requiredWidth(96.dp)
                .background(ButtonBackground, RoundedCornerShape(6.dp))
                .clickable(enabled = state.active || hasTarget) { controller.toggle() }
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = if (hasTarget) "${state.targetWatts}" else "--",
                color = color,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = captionFor(state),
            color = Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * One line, and it has to carry three different things: which loop is holding,
 * that a tap will resume, or why the controller let go. The stand-down reason
 * wins when there is one, because it is the only one the rider cannot guess.
 */
private fun captionFor(state: ErgState): String = when {
    state.active -> when (state.path) {
        ErgPath.Native -> "holding · pzaf"
        ErgPath.Host -> "holding · host"
        ErgPath.None -> "holding"
    }
    state.standDownReason != null -> state.standDownReason.removePrefix("disabled by ")
    state.targetWatts > 0 -> "tap to resume"
    else -> "no target"
}
