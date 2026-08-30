package com.spop.poverlay.sensor.interfaces

import android.content.Context
import android.os.IBinder
import com.spop.poverlay.sensor.v2.BikePlusCombinedSensor
import com.spop.poverlay.sensor.v2.TitanControl
import com.spop.poverlay.sensor.v2.TitanStatusPacket
import com.spop.poverlay.sensor.v2.getV2Binder
import com.spop.poverlay.util.windowed
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformLatest
import timber.log.Timber
import kotlin.coroutines.CoroutineContext

class PelotonBikePlusSensorInterface(val context: Context) : SensorInterface, CoroutineScope {
    companion object{
        /**
         * Resistance is filtered with a moving window since it occasionally spikes
         * The last few resistance readings will grouped, and the lowest reading will be shown
         *
         * The spikes are likely a limitation of ADC accuracy
         */
        const val ResistanceMovingAverageWindowSize = 3
    }
    private val binder = MutableSharedFlow<IBinder>(replay = 1)

    init {
        launch(Dispatchers.IO) {
            val service = getV2Binder(context)
            binder.emit(service)
        }
    }

    override val coroutineContext: CoroutineContext
        get() = SupervisorJob()

    fun stop() {
        coroutineContext.cancelChildren()
    }

    @Volatile
    private var currentSensor: BikePlusCombinedSensor? = null

    private val combinedSensorState = binder.transformLatest { service ->
        val sensor = BikePlusCombinedSensor(service)
        currentSensor = sensor
        sensor.start()
        emit(sensor)
        try {
            awaitCancellation()
        } finally {
            sensor.stop()
            currentSensor = null
        }
    }.shareIn(this, SharingStarted.Lazily, 1)

    override val power: Flow<Float>
        get() = combinedSensorState.flatMapLatest { it.power }

    override val cadence: Flow<Float>
        get() = combinedSensorState.flatMapLatest { it.cadence }

    override val resistance: Flow<Float>
        get() = combinedSensorState.flatMapLatest { it.resistance }
            .windowed(ResistanceMovingAverageWindowSize, 1, true) { readings ->
                // Resistance sensor occasionally spikes for a single reading
                // So take the least of the last few readings
                readings.minOf { it }
            }

    /** The Bike+ has a motorised brake reachable over the v2 binder. */
    override val supportsResistanceControl: Boolean
        get() = true

    override fun setResistance(resistance: Int) {
        currentSensor?.setResistance(resistance)
    }

    /**
     * Stable handle on the controller, across binder reconnections.
     *
     * [combinedSensorState] replaces its sensor whenever the service rebinds, so
     * callers holding this cannot hold the sensor itself. A command issued while
     * nothing is bound is dropped with a log rather than throwing: a rebind is
     * exactly when the ERG path most wants to keep running, and the status flow
     * will show whether the controller acted.
     */
    override val titanControl: TitanControl = object : TitanControl {

        override val status: Flow<TitanStatusPacket>
            get() = combinedSensorState.flatMapLatest { it.status }

        override val latestStatus: TitanStatusPacket?
            get() = currentSensor?.latestStatus

        override fun setPowerZoneAutoFollow(watts: Int) =
            withSensor("setPowerZoneAutoFollow", Unit) { it.setPowerZoneAutoFollow(watts) }

        override fun disablePowerZoneAutoFollow() =
            withSensor("disablePowerZoneAutoFollow", Unit) { it.disablePowerZoneAutoFollow() }

        override fun setPzafRampUpRate(rate: Int) =
            withSensor("setPzafRampUpRate", false) { it.setPzafRampUpRate(rate) }

        override fun setPzafRampDownRate(rate: Int) =
            withSensor("setPzafRampDownRate", false) { it.setPzafRampDownRate(rate) }

        override fun setPzafMaxResistance(percent: Int) =
            withSensor("setPzafMaxResistance", false) { it.setPzafMaxResistance(percent) }

        override fun setPzafMinUpdateRpm(rpm: Int) =
            withSensor("setPzafMinUpdateRpm", false) { it.setPzafMinUpdateRpm(rpm) }

        private fun <T> withSensor(name: String, unbound: T, block: (BikePlusCombinedSensor) -> T): T {
            val sensor = currentSensor
            if (sensor == null) {
                Timber.w("%s ignored: sensor service is not bound", name)
                return unbound
            }
            return block(sensor)
        }
    }
}
