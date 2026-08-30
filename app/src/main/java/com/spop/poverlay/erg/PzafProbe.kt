package com.spop.poverlay.erg

import com.spop.poverlay.sensor.v2.TitanControl
import com.spop.poverlay.sensor.v2.TitanStatusPacket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** What [PzafProbe] concluded about this bike. */
sealed interface PzafCapability {
    /** The controller answered a real command. Native ERG is available. */
    data class Supported(val firmware: String) : PzafCapability

    /**
     * Reason is for the log and the configuration page, not for branching on.
     *
     * [transient] marks the one failure worth retrying: no status packet arrived
     * at all, which at app start usually means AffernetService had not finished
     * binding rather than that the bike cannot do this. A firmware version or a
     * missing echo will not change on a retry.
     */
    data class Unsupported(val reason: String, val transient: Boolean = false) : PzafCapability
}

/**
 * Decides whether this bike's controller can actually run Power Zone Auto Follow.
 *
 * Nothing between Grupetto and the controller will refuse a PZAF command on
 * firmware that cannot execute it. `BikeServiceHelper` guards all six PZAF
 * transactions with a platform check and nothing else -- the `mummy` feature flag
 * and Peloton's "minimum controller firmware 5.0" rule live in the Peloton *app*,
 * which Grupetto does not go through. So the gate has to be here.
 *
 * Three passive checks, then one active one:
 *
 *  1. A status packet arrives and validates against its magic and footer.
 *  2. Offsets 240..243 are not the fixed `00 11 22 33` marker that Titan 1.88 and
 *     1.94 write where 5.08 keeps live PZAF state.
 *  3. Firmware major is at least 5.
 *  4. A PZAF *configuration* value written over Binder comes back changed in the
 *     next status packet.
 *
 * Step 4 is the one that proves anything. The booleans returned by transactions
 * 53..56 do not: `BikeServiceHelper.setPZAFMinUpdateRPM` returns true as soon as
 * its own range check passes and the message is queued, and never hears from the
 * bike, so 1.88 firmware with no PZAF at all would also return true. An echo at
 * packet offset 251 is a round trip through Binder, AffernetService, USB CDC and
 * the controller's own command listener.
 *
 * The minimum update cadence is the safe value to probe with. It is a plain
 * setter in `StartListnerTask`, dispatched whether or not PZAF is enabled, and it
 * has no effect on anything while PZAF is off. The brake is never commanded and
 * PZAF is never enabled.
 */
class PzafProbe(private val control: TitanControl?) {

    suspend fun run(): PzafCapability {
        if (control == null) {
            return PzafCapability.Unsupported("no Titan controller on this bike")
        }

        val packet = withTimeoutOrNull(PACKET_TIMEOUT_MS) { control.status.first() }
            ?: return PzafCapability.Unsupported(
                "no valid status packet within ${PACKET_TIMEOUT_MS}ms", transient = true
            )

        val firmware = "${packet.firmwareMajor}.${packet.firmwareMinor}"

        if (packet.isLegacyPzafMarker) {
            return PzafCapability.Unsupported(
                "controller firmware $firmware writes the legacy 00 11 22 33 marker; no PZAF"
            )
        }

        // Compared as two integers straight out of the packet rather than as the
        // string AffernetService formats. That string renders 5.08.0 as "5.8",
        // so a float parse would rank a future "5.10" below it and misgate.
        if (packet.firmwareMajor < MIN_FIRMWARE_MAJOR) {
            return PzafCapability.Unsupported(
                "controller firmware $firmware; PZAF needs $MIN_FIRMWARE_MAJOR.0"
            )
        }

        val probeValue = probeValueFor(packet.pzafMinUpdateRpm)
        Timber.i(
            "PZAF probe: firmware %s, min update rpm reads %d, writing %d",
            firmware, packet.pzafMinUpdateRpm, probeValue
        )

        if (!control.setPzafMinUpdateRpm(probeValue)) {
            return PzafCapability.Unsupported(
                "AffernetService would not send the probe value $probeValue"
            )
        }

        val echoed = withTimeoutOrNull(ECHO_TIMEOUT_MS) {
            control.status.first { it.pzafMinUpdateRpm == probeValue }
        }

        return if (echoed != null) {
            Timber.i("PZAF probe: echoed back at offset 251, native ERG available")
            PzafCapability.Supported(firmware)
        } else {
            PzafCapability.Unsupported(
                "firmware $firmware did not echo the probe value within ${ECHO_TIMEOUT_MS}ms"
            )
        }
    }

    companion object {
        /** PZAF is absent through Titan 1.94 and present by 5.08. */
        const val MIN_FIRMWARE_MAJOR = 5

        /**
         * The value the controller is left holding when the probe succeeds, and
         * the alternate used when it is already there -- writing what is already
         * in the packet would make "it echoed" indistinguishable from "nothing
         * happened". Both sit inside AffernetService's 30..120 range and the
         * firmware's own lower clamp of 30.
         */
        const val PROBE_RPM_PRIMARY = 40
        const val PROBE_RPM_ALTERNATE = 45

        fun probeValueFor(currentMinUpdateRpm: Int) =
            if (currentMinUpdateRpm == PROBE_RPM_PRIMARY) PROBE_RPM_ALTERNATE else PROBE_RPM_PRIMARY

        /**
         * The sensor polls every 200ms and the service every 33ms, so an echo
         * should land within two or three frames. These are generous rather than
         * tight: the cost of waiting is a slower first answer, and the cost of
         * giving up early is falling back to the host loop on a bike that would
         * have worked.
         */
        const val PACKET_TIMEOUT_MS = 10_000L
        const val ECHO_TIMEOUT_MS = 3_000L
    }
}

/** Convenience for logging a packet's PZAF block in one line. */
fun TitanStatusPacket.pzafSummary(): String =
    "mode=$pzafMode setpoint=${pzafSetpointWatts}W status=$pzafStatus " +
        "rampUp=$pzafRampUpRate rampDown=$pzafRampDownRate " +
        "maxRes=$pzafMaxResistance minRpm=$pzafMinUpdateRpm"
