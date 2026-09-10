package com.spop.poverlay.erg

/**
 * Recognizes target-resistance feedback belonging to recent app commands. Motor
 * position is deliberately not used: a moving brake is not a knob override.
 * Commands may take several polls to echo, so retain their values for 1.5 s.
 * This cannot distinguish a knob turn that happens to select a recent target.
 */
class ResistanceCommandTracker(private val echoWindowMs: Long = 1500) {
    private val commands = ArrayDeque<Pair<Long, Int>>()
    private var latest: Int? = null

    fun reset(initialTarget: Int, nowMs: Long) {
        commands.clear()
        latest = initialTarget
        commanded(initialTarget, nowMs)
    }

    fun commanded(target: Int, nowMs: Long) {
        latest = target
        commands.addLast(nowMs to target)
        prune(nowMs)
    }

    fun isUnexpected(target: Int, nowMs: Long): Boolean {
        prune(nowMs)
        return latest != null && target != latest && commands.none { it.second == target }
    }

    private fun prune(nowMs: Long) {
        while (commands.isNotEmpty() && nowMs - commands.first().first > echoWindowMs) commands.removeFirst()
    }
}
