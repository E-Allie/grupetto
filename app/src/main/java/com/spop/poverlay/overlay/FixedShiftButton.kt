package com.spop.poverlay.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.control.ControlMode

/** No drag handler: position belongs to the paired WindowManager layouts. */
@Composable
internal fun FixedShiftButton(up: Boolean, watts: Boolean, onClick: () -> Unit) {
    FixedShiftButton(up, if (watts) ControlMode.Erg else ControlMode.Manual, onClick)
}

@Composable
internal fun FixedShiftButton(up: Boolean, mode: ControlMode, onClick: () -> Unit) {
    val target = when (mode) {
        ControlMode.Erg -> "ERG target watts"
        ControlMode.Simulation -> "virtual gear"
        ControlMode.Manual, ControlMode.Resistance -> "resistance"
    }
    val label = when (mode) {
        ControlMode.Erg -> "Watts"
        ControlMode.Simulation -> "Gear"
        ControlMode.Manual, ControlMode.Resistance -> "Resist"
    }
    Button(onClick, Modifier.size(ShifterWidthDp.dp, ShifterHeightDp.dp).semantics {
        contentDescription = if (up) "Shift up: increase $target" else "Shift down: decrease $target"
    }, contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(10.dp),
        border = BorderStroke(2.dp, Color(0xFF91FFE0)),
        elevation = ButtonDefaults.elevation(0.dp, 0.dp),
        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF008761))) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (up) "+" else "−", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
