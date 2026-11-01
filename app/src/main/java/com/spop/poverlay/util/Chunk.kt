@file:OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)

package com.spop.poverlay.util

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

//Imported from declined PR to main Kotlin Flow repository

/**
 * Returns a flow of results of applying the given [transform] function to
 * an each list representing a view over the window of the given [size]
 * sliding along this collection with the given [step].
 *
 * Note that the list passed to the [transform] function is ephemeral and is valid only inside that function.
 * You should not store it or allow it to escape in some way, unless you made a snapshot of it.
 * Several last lists may have less elements than the given [size].
 *
 * This is more efficient, than using flow.windowed(...).map { ... }
 *
 * Both [size] and [step] must be positive and can be greater than the number of elements in this collection.
 * @param size the number of elements to take in each window
 * @param step the number of elements to move the window forward by on an each step.
 * @param partialWindows controls whether or not to keep partial windows in the end if any.
 */

@FlowPreview
fun <T, R> Flow<T>.windowed(size: Int, step: Int, partialWindows: Boolean, transform: suspend (List<T>) -> R): Flow<R> {
    require(size > 0 && step > 0) { "Size and step should be greater than 0, but was size: $size, step: $step" }

    return flow {
        val buffer = ArrayDeque<T>(size)
        val toDrop = min(step, size)
        val toSkip = max(step - size, 0)
        var skipped = toSkip

        this@windowed.collect(object : FlowCollector<T> {
            override suspend fun emit(value: T) {
                if (toSkip == skipped) buffer.addLast(value)
                else skipped++

                if (buffer.size == size) {
                    this@flow.emit(transform(buffer))
                    repeat(toDrop) { buffer.removeFirst() }
                    skipped = 0
                }
            }
        })

        while (partialWindows && buffer.isNotEmpty()) {
            emit(transform(buffer))
            repeat(min(toDrop, buffer.size)) { buffer.removeFirst() }
        }
    }
}

/**
 * Emits once at every multiple of [period] on a shared monotonic clock. Tickers whose periods
 * divide each other therefore wake together (their UI updates land in the same frame), and
 * ticks don't drift the way a plain delay loop does. Each grid point emits at most once. With
 * [catchUp], a late wake-up emits the missed ticks (unless it fell more than [MaxTickCatchUp]
 * behind), so counting ticks keeps time; without it, missed ticks are skipped.
 */
fun alignedTickerFlow(
    period: Duration,
    catchUp: Boolean = true,
    clockMillis: () -> Long = ::monotonicMillis,
) = flow {
    val periodMillis = period.inWholeMilliseconds
    require(periodMillis > 0) { "Period must be at least 1 ms, was $period" }
    var next = (clockMillis() / periodMillis + 1) * periodMillis
    while (true) {
        var now = clockMillis()
        while (now < next) {
            delay(next - now)
            now = clockMillis()
        }
        emit(Unit)
        next += periodMillis
        if (!catchUp && next <= now || now - next > MaxTickCatchUp.inWholeMilliseconds) {
            next = (now / periodMillis + 1) * periodMillis
        }
    }
}

private val MaxTickCatchUp = 5.seconds

private fun monotonicMillis() = System.nanoTime() / 1_000_000
