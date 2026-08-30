package com.spop.poverlay.sensor.v2

import android.os.IBinder
import android.os.Parcel
import com.spop.poverlay.sensor.BikeData
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val READ_DELAY = 200L

/** `IBikeInterface.Stub` transaction codes. */
private const val TXN_GET_BIKE_DATA = 14
private const val TXN_SET_RESISTANCE = 7
private const val TXN_SET_PZAF = 51
private const val TXN_DISABLE_PZAF = 52
private const val TXN_PZAF_RAMP_UP = 53
private const val TXN_PZAF_RAMP_DOWN = 54
private const val TXN_PZAF_MAX_RESISTANCE = 55
private const val TXN_PZAF_MIN_RPM = 56

/**
 * Counts consecutive errors, and throws an exception if the limit is reached
 * This is used to stop the sensor if it is not responding.
 * While testing, the sensor would sometimes stop responding randomly, and this was a way to handle that
 * It is not a perfect solution, but it is better than nothing.
 */
class ConsecutiveErrorCounter(private val limit: Int = 5) {
    private var consecutiveErrors = 0

    fun increment() {
        consecutiveErrors++
        if (consecutiveErrors >= limit) {
            throw Exception("Too many consecutive errors")
        }
    }

    fun reset() {
        consecutiveErrors = 0
    }
}

class BikePlusCombinedSensor(private val binder: IBinder) : TitanControl {

    private val mutablePower = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val power = mutablePower.asSharedFlow()

    private val mutableCadence = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val cadence = mutableCadence.asSharedFlow()

    private val mutableResistance = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val resistance = mutableResistance.asSharedFlow()

    private val mutableStatus = MutableSharedFlow<TitanStatusPacket>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val status = mutableStatus.asSharedFlow()

    @Volatile
    override var latestStatus: TitanStatusPacket? = null
        private set

    private val errorCounter = ConsecutiveErrorCounter()
    private var threadRunning = AtomicBoolean(false)

    fun start() {
        if (threadRunning.getAndSet(true)) return

        thread(name = "BikePlusSensorPoller") {
            Timber.i("Starting BikePlus polling thread")
            try {
                while (threadRunning.get()) {
                    val parcel = Parcel.obtain()
                    val parcel2 = Parcel.obtain()
                    try {
                        parcel.writeInterfaceToken(SERVICE_ACTION)
                        // Transact code 14 fetches the full BikeData
                        binder.transact(TXN_GET_BIKE_DATA, parcel, parcel2, 0)
                        parcel2.readException()
                        // Skip the first integer
                        parcel2.readInt()
                        
                        val bikeData = BikeData.CREATOR.createFromParcel(parcel2)
                        
                        // Emit values
                        // Power is divided by 100 in original BikePlusPowerSensor
                        // Note: Property access 'rpm' vs 'RPM' depends on interop, sticking to existing convention
                        mutablePower.tryEmit(bikeData.power.toFloat() / 100f)
                        mutableCadence.tryEmit(bikeData.rpm.toFloat())
                        mutableResistance.tryEmit(bikeData.targetResistance.toFloat())

                        // The same numbers again, plus the PZAF block, straight
                        // from the controller's own record rather than from the
                        // tail of a parcel whose field order Peloton has already
                        // extended. A malformed or absent packet is not an error
                        // worth counting; the parcel fields above still worked.
                        TitanStatusPacket.parse(bikeData.packetData)?.let {
                            if (latestStatus == null) {
                                // Once per connection. Which controller this is,
                                // and what its PZAF block holds before anything
                                // has touched it, are the two things a recorded
                                // session cannot be read without.
                                Timber.i("Titan controller: %s", it)
                            }
                            latestStatus = it
                            mutableStatus.tryEmit(it)
                        }

                        errorCounter.reset()
                    } catch (e: Exception) {
                        try {
                            errorCounter.increment()
                        } catch (e: Exception) {
                            Timber.e(e, "BikePlusCombinedSensor stopped due to errors")
                            stop()
                            break
                        }
                    } finally {
                        parcel.recycle()
                        parcel2.recycle()
                    }
                    Thread.sleep(READ_DELAY)
                }
            } catch (e: Exception) {
                Timber.e(e, "BikePlusCombinedSensor thread crashed")
            } finally {
                threadRunning.set(false)
                Timber.i("BikePlus polling thread stopped")
            }
        }
    }

    fun setResistance(resistance: Int) {
        val clamped = resistance.coerceIn(0, 100)
        transact(TXN_SET_RESISTANCE, clamped, "setResistance($clamped)")
    }

    override fun setPowerZoneAutoFollow(watts: Int) {
        val clamped = watts.coerceIn(TitanControl.PZAF_POWER_RANGE)
        transact(TXN_SET_PZAF, clamped, "setPowerZoneAutoFollow($clamped)")
    }

    override fun disablePowerZoneAutoFollow() {
        transact(TXN_DISABLE_PZAF, argument = null, description = "disablePowerZoneAutoFollow")
    }

    override fun setPzafRampUpRate(rate: Int) =
        transactForBoolean(TXN_PZAF_RAMP_UP, rate, "setPzafRampUpRate($rate)")

    override fun setPzafRampDownRate(rate: Int) =
        transactForBoolean(TXN_PZAF_RAMP_DOWN, rate, "setPzafRampDownRate($rate)")

    override fun setPzafMaxResistance(percent: Int) =
        transactForBoolean(TXN_PZAF_MAX_RESISTANCE, percent, "setPzafMaxResistance($percent)")

    override fun setPzafMinUpdateRpm(rpm: Int) =
        transactForBoolean(TXN_PZAF_MIN_RPM, rpm, "setPzafMinUpdateRpm($rpm)")

    /**
     * One two-way transaction with flags 0, in the shape Peloton's generated
     * proxy uses: interface token, optional int argument, transact, read the
     * exception back. [reply] is handed the reply parcel once it is known to
     * carry no exception, for the transactions that return something.
     */
    private fun <T> transact(
        code: Int,
        argument: Int?,
        description: String,
        failed: T,
        reply: (Parcel) -> T
    ): T {
        val data = Parcel.obtain()
        val replyParcel = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SERVICE_ACTION)
            if (argument != null) data.writeInt(argument)
            binder.transact(code, data, replyParcel, 0)
            replyParcel.readException()
            reply(replyParcel)
        } catch (e: Exception) {
            // A SecurityException here is AffernetService refusing the platform,
            // which is the answer to "can this bike do that" rather than a fault.
            Timber.e(e, "Binder transaction %d failed: %s", code, description)
            failed
        } finally {
            data.recycle()
            replyParcel.recycle()
        }
    }

    private fun transact(code: Int, argument: Int?, description: String) =
        transact(code, argument, description, failed = Unit) { }

    private fun transactForBoolean(code: Int, argument: Int, description: String) =
        transact(code, argument, description, failed = false) { it.readInt() != 0 }

    fun stop() {
        threadRunning.set(false)
    }
}
