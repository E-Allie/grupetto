package com.spop.poverlay.sim

/** Masses describe the rider and a virtual road bicycle, never the Peloton. */
data class SimulationSettings(
    val riderMassKg: Double = DEFAULT_RIDER_KG,
    val bicycleMassKg: Double = DEFAULT_BICYCLE_KG,
    val massUnit: MassUnit = MassUnit.Kilograms,
    val enabled: Boolean = false,
    val gearing: GearProfile = GearProfile.Climbing,
    val maxSimWatts: Int = DEFAULT_MAX_SIM_WATTS,
    val autoResumeAfterPause: Boolean = false
) {
    val totalMassKg: Double get() = riderMassKg + bicycleMassKg

    init {
        require(riderMassKg.isFinite() && riderMassKg in RIDER_KG_RANGE)
        require(bicycleMassKg.isFinite() && bicycleMassKg in BICYCLE_KG_RANGE)
        require(maxSimWatts in SIM_WATTS_RANGE)
    }

    companion object {
        const val DEFAULT_RIDER_KG = 75.0
        const val DEFAULT_BICYCLE_KG = 8.0
        const val DEFAULT_MAX_SIM_WATTS = 140
        val SIM_WATTS_RANGE = 30..1000
        val RIDER_KG_RANGE = 20.0..300.0
        val BICYCLE_KG_RANGE = 1.0..50.0
    }
}

enum class MassUnit(val label: String, private val perKg: Double) {
    Kilograms("kg", 1.0),
    Pounds("lb", 2.2046226218487757);

    fun fromKg(kg: Double): Double = kg * perKg
    fun toKg(value: Double): Double = value / perKg

    /** Decimal commas are accepted; grouping separators are deliberately unsupported. */
    fun parseKg(text: String): Double? = text.trim().replace(',', '.')
        .toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::toKg)
}
