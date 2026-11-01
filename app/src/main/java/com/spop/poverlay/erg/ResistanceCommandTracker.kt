package com.spop.poverlay.erg

/**
 * Recognizes target-resistance feedback belonging to recent app commands. Motor
 * position is deliberately not used: a moving brake is not a knob override.
 * Commands may take several polls to echo. Retain the immediately preceding
 * target for 1.5 s after each change, even if it had been held for a long time.
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
        // Polls already in flight can still report the preceding target. Its
        // grace starts at this transition, not when that old target was set.
        // Only the immediate predecessor is renewed; older targets still expire.
        latest?.takeIf { it != target }?.let { commands.addLast(nowMs to it) }
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
