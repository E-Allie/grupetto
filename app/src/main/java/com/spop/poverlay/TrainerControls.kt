package com.spop.poverlay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.control.ControlMode
import com.spop.poverlay.control.ControlState
import com.spop.poverlay.erg.ErgMode
import com.spop.poverlay.erg.ErgPath
import java.util.Locale
import kotlin.math.roundToInt

internal fun ergPathLabel(path: ErgPath): String = when (path) {
    ErgPath.Native -> "Peloton Auto Follow"
    ErgPath.Host -> "Software ERG"
    ErgPath.None -> "Not regulating"
}

internal fun controlNeedsResume(state: ControlState): Boolean =
    state.suspended || state.paused || state.simulationDecision?.resumeRequired == true

internal fun trainerTargetSummary(state: ControlState): String = when (state.mode) {
    ControlMode.Erg -> "ERG · ${state.targetWatts} W"
    ControlMode.Simulation -> "SIM · Gear ${state.gear} / 24 · ${String.format(Locale.US, "%.1f%%", state.targetIncline)}"
    else -> "Manual · ${state.targetResistance}"
}

internal fun simulationStatus(state: ControlState): String {
    val decision = state.simulationDecision
    val limit = decision?.powerLimitWatts ?: state.simulationSettings.maxSimWatts
    val target = decision?.requestedWatts?.let { "$it W requested" } ?: "No active target"
    val cap = if (decision?.capped == true) "Capped at $limit W" else "$limit W limit"
    val restart = decision?.resumeTargetWatts?.let { watts ->
        val cadence = decision.previewCadenceRpm?.roundToInt()?.let { " at $it rpm" } ?: ""
        " · Restart $watts W$cadence"
    } ?: ""
    return "$target · $cap$restart"
}

/** Targets come directly from control state so remote commands also update an open dialog. */
@Composable
internal fun TrainerControls(
    state: ControlState,
    onErg: (Int) -> Unit,
    onSimulation: (Float) -> Unit,
    onManual: () -> Unit,
    onResistance: (Int) -> Unit,
    onTuning: (Int, Float, Int, Int) -> Unit,
    onErgMode: (ErgMode) -> Unit = {},
    onShift: (Int) -> Unit = {},
    onResume: () -> Unit = {},
    onStop: () -> Unit = {},
    onSimulationSettings: () -> Unit = {},
    onSoftwareAlgorithm: (com.spop.poverlay.erg.SoftwareErgAlgorithm) -> Unit = {}
) {
    val mode = if (state.mode == ControlMode.Resistance) ControlMode.Manual else state.mode
    Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .60f).dp)
        .verticalScroll(rememberScrollState())) {
        state.externalControl?.let { ExternalControlFlag(it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(ControlMode.Erg to "ERG", ControlMode.Simulation to "Sim", ControlMode.Manual to "Manual").forEach { (item, label) ->
                val active = mode == item
                Button({ when (item) {
                    ControlMode.Erg -> onErg(state.targetWatts)
                    ControlMode.Simulation -> if (state.simulationSettings.enabled) onSimulation(state.targetIncline) else onSimulationSettings()
                    else -> onManual()
                } }, Modifier.weight(1f).semantics { selected = active },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (active) Color(0xFFB7F7DF) else Color(0xFF243242),
                        contentColor = if (active) Color(0xFF10251F) else Color(0xFFBAC6D3))) { Text(label) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("ERG controller", fontSize = 15.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ErgMode.Auto to "Auto", ErgMode.Native to "Native", ErgMode.Host to "Software").forEach { (value, label) ->
                OutlinedButton({ onErgMode(value) }, Modifier.weight(1f).semantics { selected = state.ergMode == value }) {
                    Text(if (state.ergMode == value) "✓ $label" else label)
                }
            }
        }
        Text("Software algorithm", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.spop.poverlay.erg.SoftwareErgAlgorithm.entries.forEach { algorithm ->
                OutlinedButton({ onSoftwareAlgorithm(algorithm) }, Modifier.weight(1f).semantics {
                    selected = state.softwareAlgorithm == algorithm
                }) { Text(algorithm.label) }
            }
        }
        if (state.softwareAlgorithm == com.spop.poverlay.erg.SoftwareErgAlgorithm.UpstreamModel) {
            TrainerSlider("Upstream model ramp speed", "${state.rampRate} levels/s",
                state.rampRate.toFloat(), 3f..20f, 16) {
                onTuning(state.shiftSize, state.gain, state.wattsPerShift, it.roundToInt())
            }
        }
        Text(ergPathLabel(state.ergPath), color = Color(0xFFB7F7DF), fontSize = 13.sp)
        Text(state.capability ?: when (state.ergMode) {
            ErgMode.Auto -> "Uses Peloton Auto Follow when available, otherwise software ERG."
            ErgMode.Native -> "Uses the bike's Power Zone Auto Follow."
            ErgMode.Host -> "Uses the selected software algorithm."
        }, fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFF9EAEC0))
        Spacer(Modifier.height(10.dp))
        if ((mode == ControlMode.Erg || mode == ControlMode.Simulation) &&
            (state.ergPath == ErgPath.Host || state.ergMode == ErgMode.Host)) {
            TrainerSlider("Software gain", String.format(Locale.US, "%.3f", state.gain), state.gain, .001f..0.030f, 28) {
                onTuning(state.shiftSize, it, state.wattsPerShift, state.rampRate)
            }
        }
        when (mode) {
            ControlMode.Erg -> {
                TrainerSlider("ERG target", "${state.targetWatts} W", state.targetWatts.toFloat(), 25f..800f) { onErg(it.roundToInt()) }
                TrainerSlider("Watts per shift", "${state.wattsPerShift} W", state.wattsPerShift.toFloat(), 1f..50f, 48) {
                    onTuning(state.shiftSize, state.gain, it.roundToInt(), state.rampRate)
                }
            }
            ControlMode.Simulation -> {
                TrainerSlider("Incline", String.format(Locale.US, "%.1f%%", state.targetIncline), state.targetIncline,
                    minOf(-20f, state.targetIncline)..maxOf(20f, state.targetIncline)) { onSimulation((it * 10).roundToInt() / 10f) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ onShift(-1) }, enabled = state.gear > 1) { Text("Lower gear") }
                    Text("Gear ${state.gear} / 24", color = Color(0xFFB7F7DF))
                    OutlinedButton({ onShift(1) }, enabled = state.gear < 24) { Text("Higher gear") }
                }
                Text(simulationStatus(state), fontSize = 13.sp, lineHeight = 18.sp)
                TextButton(onSimulationSettings) { Text("SIM weights, gearing & limits") }
            }
            else -> {
                TrainerSlider("Resistance", "${state.targetResistance} points", state.targetResistance.toFloat(), 0f..100f, 99) {
                    onResistance(it.roundToInt())
                }
                TrainerSlider("Resistance per shift", "${state.shiftSize} points", state.shiftSize.toFloat(), 1f..10f, 8) {
                    onTuning(it.roundToInt(), state.gain, state.wattsPerShift, state.rampRate)
                }
            }
        }
        Text(state.message, fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFFB7F7DF))
        if (mode != ControlMode.Manual || controlNeedsResume(state)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (controlNeedsResume(state)) Button(onResume, Modifier.weight(1f)) { Text("Resume control") }
                OutlinedButton(onStop, Modifier.weight(1f)) { Text("Stop control") }
            }
        }
        Text("Connected training apps set the mode and target. Rider stops and overrides require Resume control.",
            fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFF9EAEC0))
    }
}

@Composable
internal fun ExternalControlFlag(transport: String) {
    Surface(color = Color(0xFFB7F7DF), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)) {
        Text("External control · $transport", Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color(0xFF10251F), fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun TrainerSlider(label: String, detail: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 15.sp, lineHeight = 20.sp)
        Text(detail, fontSize = 15.sp, lineHeight = 20.sp, color = Color(0xFFB7F7DF))
    }
    Slider(value.coerceIn(range), onChange, Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = label }, valueRange = range, steps = steps)
}
