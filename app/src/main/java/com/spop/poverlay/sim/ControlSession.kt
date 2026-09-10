package com.spop.poverlay.sim

enum class TrainerMode { Idle, Erg, Resistance, Simulation }

/** Single controlling connection across BLE and DIRCON; observers never own control. */
class ControlSession {
    var owner: String? = null
        private set
    var mode = TrainerMode.Idle
        private set
    var paused = false
        private set
    var suspension: String? = null
        private set
    /** A discovery-time grant is yieldable until a real target starts a session. */
    var engaged = false
        private set

    fun requestControl(client: String): Boolean {
        if (owner != null && owner != client && engaged) return false
        owner = client
        return true
    }

    fun canControl(client: String): Boolean = owner == client && suspension == null

    fun select(mode: TrainerMode, engage: Boolean = true) {
        check(suspension == null)
        this.mode = mode
        paused = false
        engaged = engaged || engage
    }

    fun pause() { paused = true }

    fun resume(): Boolean {
        if (owner == null || mode == TrainerMode.Idle || suspension != null) return false
        paused = false
        return true
    }

    fun suspend(reason: String) { suspension = reason }

    fun resumeLocally(): Boolean {
        suspension = null
        paused = false
        return owner != null && mode != TrainerMode.Idle
    }

    /** Reset/Stop never clear a rider override; only a deliberate local resume does. */
    fun reset() { mode = TrainerMode.Idle; paused = false; engaged = false }

    fun disconnected(client: String): Boolean {
        if (owner != client) return false
        owner = null
        reset()
        return true
    }

    fun close() { owner = null; reset() }
}
