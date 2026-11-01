package com.spop.poverlay.util

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class AlignedTickerFlowTest {
    @Test fun emitsOncePerGridPointAndCatchesUpOnlyAfterShortStalls() = runBlocking {
        val readings = ArrayDeque(listOf<Long>(
            0,          // start: first tick at 10
            9, 10,      // woke 1 ms early: waits for the grid point instead of ticking twice
            19, 20,
            45,         // late: ticks for 30 now...
            45,         // ...and catches up on 40 right away
            50,
            10_000,     // stalled far behind: ticks once, then skips to the next grid point
            10_010,
        ))
        var now = 0L
        val clock = { readings.removeFirst().also { now = it } }

        val ticks = alignedTickerFlow(10.milliseconds, clockMillis = clock).map { now }.take(7).toList()

        assertEquals(listOf<Long>(10, 20, 45, 45, 50, 10_000, 10_010), ticks)
    }

    @Test fun withoutCatchUpSkipsMissedTicks() = runBlocking {
        val readings = ArrayDeque(listOf<Long>(0, 10, 45, 50))
        var now = 0L
        val clock = { readings.removeFirst().also { now = it } }

        val ticks = alignedTickerFlow(10.milliseconds, catchUp = false, clockMillis = clock)
            .map { now }.take(3).toList()

        // Late at 45: one tick, then the next grid point (50); 30 and 40 are skipped.
        assertEquals(listOf<Long>(10, 45, 50), ticks)
    }
}
