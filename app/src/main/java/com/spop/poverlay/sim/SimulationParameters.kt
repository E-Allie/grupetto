package com.spop.poverlay.sim

/**
 * The parameters carried by an FTMS Set Indoor Bike Simulation Parameters
 * (0x11) write -- the message a game sends to put a trainer in "sim mode",
 * where the trainer is told about the road and works out the resistance itself.
 *
 * Together with a road speed they define the power the rider owes:
 *
 * See [RoadLoadModel] for the equation and the wind-coefficient convention.
 *
 * Mass and speed are not carried in this message. Mass is configured locally.
 * Speed is the hard one
 * on a Peloton: there is no wheel, no freewheel and exactly one gear ratio, so
 * it has to be synthesised from cadence and a chosen ratio. See [VirtualGears].
 *
 * App-side gear changes may affect these parameters. They are honored as sent;
 * a changed coefficient is not decoded as a second gear command.
 */
data class SimulationParameters(
    /** Head wind in m/s. Positive is a head wind, negative a tail wind. */
    val windSpeedMps: Double,
    /** Road gradient in percent, so 5.0 is a 5% climb. */
    val gradePercent: Double,
    /** Coefficient of rolling resistance, dimensionless. Road tyres are ~0.004. */
    val crr: Double,
    /** Trainer convention: rho * CdA in kg/m; the force equation includes 0.5. */
    val cw: Double
) {
    /** Finite values within the FTMS wire ranges, including legitimate zeros. */
    val isValid: Boolean get() =
        windSpeedMps.isFinite() && windSpeedMps in -32.768..32.767 &&
            gradePercent.isFinite() && gradePercent in -327.68..327.67 &&
            crr.isFinite() && crr in 0.0..0.0255 && cw.isFinite() && cw in 0.0..2.55

    override fun toString(): String = "grade=%+.2f%% wind=%+.3fm/s crr=%.4f cw=%.2fkg/m"
        .format(gradePercent, windSpeedMps, crr, cw)

    companion object {
        /** Opcode byte plus sint16 wind, sint16 grade, uint8 Crr, uint8 Cw. */
        const val PAYLOAD_LENGTH = 7

        /**
         * Decode exactly one 0x11 Control Point write. Reject truncated/extra bytes.
         */
        fun parse(value: ByteArray?): SimulationParameters? {
            if (value == null || value.size != PAYLOAD_LENGTH || value[0] != 0x11.toByte()) return null

            fun sint16(offset: Int): Int {
                val raw = (value[offset].toInt() and 0xFF) or
                    ((value[offset + 1].toInt() and 0xFF) shl 8)
                return raw.toShort().toInt()
            }

            return SimulationParameters(
                windSpeedMps = sint16(1) / 1000.0,
                gradePercent = sint16(3) / 100.0,
                crr = (value[5].toInt() and 0xFF) / 10000.0,
                cw = (value[6].toInt() and 0xFF) / 100.0
            )
        }
    }
}
