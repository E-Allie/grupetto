package com.spop.poverlay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.sim.MassUnit
import com.spop.poverlay.sim.GearProfile
import com.spop.poverlay.sim.SimulationSettings
import java.util.Locale

@Composable
fun SimulationSettingsCard(settings: SimulationSettings, onSave: (SimulationSettings) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingLimit by rememberSaveable { mutableStateOf(false) }
    val unit = settings.massUnit
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Simulation", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Terrain resistance", color = Color.White)
                Text("Let an app's hills and Grupetto's gears adjust resistance on Bike+.",
                    color = Color(0xFFD0D0D0), fontSize = 14.sp)
            }
            Switch(checked = settings.enabled, onCheckedChange = { onSave(settings.copy(enabled = it)) })
        }
        Text("Experimental · uses your ERG Control selection, including App PID fallback. Keep the game's gear fixed while trying Grupetto's gears.",
            color = Color(0xFFD0D0D0), fontSize = 14.sp)
        Text("Rider: ${massText(unit.fromKg(settings.riderMassKg))} ${unit.label}    " +
            "Virtual bicycle: ${massText(unit.fromKg(settings.bicycleMassKg))} ${unit.label}", color = Color.White)
        OutlinedButton(onClick = { editing = true }) { Text("Edit weights") }
        Text("Gearing", color = Color.White, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GearProfile.values().forEach { profile ->
                OutlinedButton(onClick = { onSave(settings.copy(gearing = profile)) }) {
                    Text(if (settings.gearing == profile) "✓ ${profile.label}" else profile.label)
                }
            }
        }
        Text(if (settings.gearing == GearProfile.Climbing) "Lower climbing gears · ratios 0.40–4.55" else
            "Original road gears · ratios 1.21–4.55", color = Color(0xFFD0D0D0), fontSize = 14.sp)
        OutlinedButton(onClick = { editingLimit = true }) { Text("SIM power limit: ${settings.maxSimWatts} W") }
        Text("Caps the requested terrain power. Measured power may briefly overshoot; the bike's own limit may be lower.",
            color = Color(0xFFD0D0D0), fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Resume automatically after pauses", color = Color.White)
                Text("Opt-in · resumes with steady pedaling after the bike reports minimum resistance.",
                    color = Color(0xFFD0D0D0), fontSize = 14.sp)
            }
            Switch(checked = settings.autoResumeAfterPause,
                onCheckedChange = { onSave(settings.copy(autoResumeAfterPause = it)) })
        }
        Text("Stop and detected overrides still need a tap to resume. Restarts ramp up gently; the overlay previews the target.",
            color = Color(0xFFD0D0D0), fontSize = 14.sp)
    }
    if (editing) MassSettingsDialog(settings, onDismiss = { editing = false }) {
        onSave(it)
        editing = false
    }
    if (editingLimit) SimPowerLimitDialog(settings.maxSimWatts, { editingLimit = false }) {
        onSave(settings.copy(maxSimWatts = it))
        editingLimit = false
    }
}

@Composable
private fun SimPowerLimitDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.toString()) }
    val watts = text.toIntOrNull()
    val valid = watts != null && watts in SimulationSettings.SIM_WATTS_RANGE
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Maximum SIM target") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose the most power you want terrain resistance to request. Harder hills or gears stop raising the target at this limit.")
                OutlinedTextField(text, { text = it }, label = { Text("Power limit (W)") },
                    singleLine = true, isError = !valid, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (!valid) Text("Enter 30–1000 W.", color = MaterialTheme.colors.error)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(watts!!) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun massText(value: Double) = String.format(Locale.ROOT, "%.1f", value)

@Composable
private fun MassSettingsDialog(settings: SimulationSettings, onDismiss: () -> Unit, onSave: (SimulationSettings) -> Unit) {
    var unitName by rememberSaveable { mutableStateOf(settings.massUnit.name) }
    val unit = MassUnit.valueOf(unitName)
    var rider by rememberSaveable { mutableStateOf(massText(unit.fromKg(settings.riderMassKg))) }
    var bicycle by rememberSaveable { mutableStateOf(massText(unit.fromKg(settings.bicycleMassKg))) }
    val riderKg = if (rider == massText(unit.fromKg(settings.riderMassKg))) settings.riderMassKg else unit.parseKg(rider)
    val bicycleKg = if (bicycle == massText(unit.fromKg(settings.bicycleMassKg))) settings.bicycleMassKg else unit.parseKg(bicycle)
    val riderValid = riderKg != null && riderKg in SimulationSettings.RIDER_KG_RANGE
    val bicycleValid = bicycleKg != null && bicycleKg in SimulationSettings.BICYCLE_KG_RANGE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Simulation weights") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Use your game profile's rider weight. Bicycle weight means a virtual road bicycle, not the Peloton.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MassUnit.values().forEach { choice ->
                        OutlinedButton(onClick = {
                            if (choice != unit) {
                                rider = riderKg?.let { massText(choice.fromKg(it)) } ?: ""
                                bicycle = bicycleKg?.let { massText(choice.fromKg(it)) } ?: ""
                                unitName = choice.name
                            }
                        }) { Text(if (choice == unit) "✓ ${choice.label}" else choice.label) }
                    }
                }
                OutlinedTextField(rider, { rider = it }, label = { Text("Rider weight (${unit.label})") },
                    isError = !riderValid, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (!riderValid) Text("Enter ${massText(unit.fromKg(20.0))}–${massText(unit.fromKg(300.0))} ${unit.label}.",
                    color = MaterialTheme.colors.error)
                OutlinedTextField(bicycle, { bicycle = it }, label = { Text("Virtual bicycle weight (${unit.label})") },
                    isError = !bicycleValid, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (!bicycleValid) Text("Enter ${massText(unit.fromKg(1.0))}–${massText(unit.fromKg(50.0))} ${unit.label}.",
                    color = MaterialTheme.colors.error)
            }
        },
        confirmButton = {
            TextButton(enabled = riderValid && bicycleValid, onClick = {
                onSave(settings.copy(riderMassKg = riderKg!!, bicycleMassKg = bicycleKg!!, massUnit = unit))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
