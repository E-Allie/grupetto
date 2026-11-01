package com.spop.poverlay.sensor

import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.time.ExperimentalTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * At times the Peloton sensor service stops responding until the Bike is power cycled
 * This class monitors for this
 */
@OptIn(ExperimentalTime::class)
class DeadSensorDetector(
    private val sensorInterface: SensorInterface,
    override val coroutineContext: CoroutineContext,
) : CoroutineScope {

    // Must be a SharedFlow to ensure duplicate values are sent
    private val mutableDeadSensorDetected = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Emits when a dead sensor is detected
     * Guaranteed not to fire more often than DeadSensorWarningInterval
     */
    val deadSensorDetected = mutableDeadSensorDetected.asSharedFlow()

    companion object {
        // Max time between sensor updates before the user is shown the dead sensor message
        val DeadSensorTimeout = 10.seconds

        // Once the dead sensor warning has been shown,
        // wait at least this long before showing it again
        val DeadSensorWarningInterval = 5.minutes

        private val DeadSensorCheckPeriod = 1.seconds
    }

    // Written by the sensor collectors, read by the detection loop. A plain write per sample
    // replaces a channel send that woke the detector and re-armed its timeout on every reading.
    @Volatile private var lastSampleNanos = System.nanoTime()
    private var lastDeadSensorMessageMillis: Long? = null

    init {
        setupTimeoutReset()
        setupDeadSensorDetection()
    }

    // Whenever a value is received for a sensor, reset the dead sensor timeout
    private fun setupTimeoutReset() {
        val resetTimeout = object : FlowCollector<Float> {
            override suspend fun emit(value: Float) {
                lastSampleNanos = System.nanoTime()
            }
        }
        // An external meter must not hide a stalled Peloton sensor service.
        launch(Dispatchers.IO) { sensorInterface.nativePower.collect(resetTimeout) }
        launch(Dispatchers.IO) { sensorInterface.resistance.collect(resetTimeout) }
        launch(Dispatchers.IO) { sensorInterface.cadence.collect(resetTimeout) }
    }

    // When the Bike is left on for a few days, occasionally the system stops being able to respond
    // If DeadSensorTimeout passes without any messages being received, show an error message.
    // Checked once a second, so the warning appears 10-11 s after the last reading.
    private fun setupDeadSensorDetection() =
        launch(Dispatchers.IO) {
            while (isActive) {
                delay(DeadSensorCheckPeriod)
                if (System.nanoTime() - lastSampleNanos <= DeadSensorTimeout.inWholeNanoseconds) {
                    continue
                }
                // Check 'DeadSensorWarningInterval' has passed
                // since the last error emission
                val canShowMessage = lastDeadSensorMessageMillis?.let {
                    System.currentTimeMillis() - it >
                            DeadSensorWarningInterval.inWholeMilliseconds
                } ?: true

                if (canShowMessage) {
                    lastDeadSensorMessageMillis = System.currentTimeMillis()
                    mutableDeadSensorDetected.tryEmit(Unit)
                }
            }
        }
}
