package com.spop.poverlay.overlay.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.sim.VirtualGears

/** Narrower than a StatCard: two buttons and a number need less room. */
val GearShifterWidth = 130.dp

private val GearColor = Color(0xFFFF9800)
private val ButtonBackground = Color(0x33FFFFFF)

/**
 * Two buttons and a gear number.
 *
 * The cheapest of the three ways to put a shifter on this bike, and the only one
 * that needs nothing the app does not already have -- the overlay window takes
 * touches today. The other two are worth revisiting before this becomes the
 * permanent answer: the bike's own resistance knob may be readable over the
 * AffernetService binder, which would make the existing hardware the shifter,
 * and a BLE HID keypad is what the MyWhoosh and Rouvy communities use, at the
 * cost of making the app a BLE central as well as a peripheral.
 *
 * Changes [VirtualGears], which supplies the simulation model.
 * Gear changes are retained but do not change an explicit ERG target.
 */
@Composable
fun GearShifter(modifier: Modifier = Modifier) {
    val gear by VirtualGears.gear.collectAsState()
    val profile by VirtualGears.profile.collectAsState()

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "SIM gear",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            ShiftButton(label = "−") { VirtualGears.shiftDown() }
            Text(
                text = "$gear",
                color = GearColor,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .requiredWidth(42.dp)
                    .padding(horizontal = 2.dp)
            )
            ShiftButton(label = "+") { VirtualGears.shiftUp() }
        }
        Text(
            text = "%.2f".format(com.spop.poverlay.sim.GearRatios.ratioFor(gear, profile)),
            color = Color.White,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun ShiftButton(label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            // Large enough to hit without looking down mid-effort.
            .requiredSize(40.dp)
            .background(ButtonBackground, RoundedCornerShape(6.dp))
            .clickable { onClick() }
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
