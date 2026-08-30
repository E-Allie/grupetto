package com.spop.poverlay.sim

/**
 * The parameters carried by an FTMS Set Indoor Bike Simulation Parameters
 * (0x11) write -- the message a game sends to put a trainer in "sim mode",
 * where the trainer is told about the road and works out the resistance itself.
 *
 * Together with a road speed they define the power the rider owes:
 *
 *     P = v * ( m*g*sin(theta) + m*g*Crr*cos(theta) + Cw*(v + wind)^2 )
 *
 * Everything on the right except mass and speed arrives in this message. Mass
 * has no field anywhere in FTMS and has to be configured. Speed is the hard one
 * on a Peloton: there is no wheel, no freewheel and exactly one gear ratio, so
 * it has to be synthesised from cadence and a chosen ratio. See [VirtualGears].
 *
 * Nothing acts on these yet. They are parsed, logged and recorded so a real
 * ride can answer what a controller app actually sends -- in particular whether
 * a game's own virtual shifting shows up as a changed [gradePercent] or a
 * scaled [crr], which decides how much of the gear model has to live here.
 */
data class SimulationParameters(
    /** Head wind in m/s. Positive is a head wind, negative a tail wind. */
    val windSpeedMps: Double,
    /** Road gradient in percent, so 5.0 is a 5% climb. */
    val gradePercent: Double,
    /** Coefficient of rolling resistance, dimensionless. Road tyres are ~0.004. */
    val crr: Double,
    /** Wind resistance coefficient in kg/m, equal to 0.5 * rho * CdA. */
    val cw: Double
) {
    override fun toString(): String = "grade=%+.2f%% wind=%+.3fm/s crr=%.4f cw=%.2fkg/m"
        .format(gradePercent, windSpeedMps, crr, cw)

    companion object {
        /** Opcode byte plus sint16 wind, sint16 grade, uint8 Crr, uint8 Cw. */
        const val PAYLOAD_LENGTH = 7

        /**
         * Decode a Control Point write, or null when it is too short to be one.
         * The caller has already established that byte 0 is the 0x11 opcode.
         */
        fun parse(value: ByteArray?): SimulationParameters? {
            if (value == null || value.size < PAYLOAD_LENGTH) return null

            fun sint16(offset: Int): Int {
                val raw = (value[offset].toInt() and 0xFF) or
                    ((value[offset + 1].toInt() and 0xFF) shl 8)
                return raw.toShort().toInt()
            }

            return SimulationParameters(
                windSpeedMps = sint16(1) * 0.001,
                gradePercent = sint16(3) * 0.01,
                crr = (value[5].toInt() and 0xFF) * 0.0001,
                cw = (value[6].toInt() and 0xFF) * 0.01
            )
        }
    }
}
