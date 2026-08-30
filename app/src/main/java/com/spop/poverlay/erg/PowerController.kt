package com.spop.poverlay.erg

/**
 * Something that can hold a rider at a target wattage.
 *
 * There are two implementations. [NativePzafController] hands the target to the
 * Bike+ controller's own Power Zone Auto Follow loop, which runs beside the power
 * calculation itself and drives the brake directly. [HostErgController] is the
 * PID loop that predates it, reading power back over Binder at 5 Hz and writing
 * resistance at 10.
 *
 * Both are kept. The native loop needs controller firmware 5.0 and exposes no
 * gains, so a bike that cannot run it -- or that it holds badly -- still has
 * somewhere to go. [ErgCoordinator] chooses between them.
 */
interface PowerController {

    /** Whether this controller is currently holding a target. */
    val isActive: Boolean

    /** The target being held, or the last one held, in watts. */
    val targetWatts: Int

    /** Start holding [watts], from a clean state. */
    fun enable(watts: Int)

    /** Move an already-held target to [watts]. */
    fun setTarget(watts: Int)

    /** Stop holding, and give the brake back. Safe to call when inactive. */
    fun disable()
}
