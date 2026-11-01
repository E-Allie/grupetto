package com.spop.poverlay.sensor.v2

import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import com.spop.poverlay.control.BikeSample
import com.spop.poverlay.sensor.readBikeCoreData
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import kotlin.concurrent.thread

private const val READ_DELAY = 200L
private const val TXN_GET_BIKE_DATA = 14

/** Stops polling after repeated service failures; a successful sample resets it. */
class ConsecutiveErrorCounter(private val limit: Int = 5) {
    private var consecutiveErrors = 0
    fun increment() {
        consecutiveErrors++
        if (consecutiveErrors >= limit) throw Exception("Too many consecutive errors")
    }
    fun reset() { consecutiveErrors = 0 }
}

internal enum class TitanCommand(
    val transaction: Int,
    val range: IntRange? = null,
    val returnsBoolean: Boolean = false
) {
    Enable(51, TitanControl.PZAF_POWER_RANGE),
    Disable(52),
    RampUp(53, TitanControl.RAMP_RATE_RANGE, true),
    RampDown(54, TitanControl.RAMP_RATE_RANGE, true),
    MaxResistance(55, TitanControl.MAX_RESISTANCE_RANGE, true),
    MinRpm(56, TitanControl.MIN_UPDATE_RPM_RANGE, true)
}

/** Checked two-way service call; false means no acknowledgement of acceptance. */
internal fun writeTitanCommand(binder: IBinder, command: TitanCommand, argument: Int? = null): Boolean {
    if (command.range == null && argument != null ||
        command.range != null && (argument == null || argument !in command.range)) return false
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    return try {
        data.writeInterfaceToken(SERVICE_ACTION)
        if (argument != null) data.writeInt(argument)
        if (!binder.transact(command.transaction, data, reply, 0)) false else {
            reply.readException()
            !command.returnsBoolean || reply.readInt() != 0
        }
    } catch (error: Exception) {
        Timber.w(error, "Bike+ native ERG command %s failed", command)
        false
    } finally {
        data.recycle()
        reply.recycle()
    }
}

/**
 * The first six fields have already been read by readBikeCoreData. Only the
 * firmware string and raw packet follow them; ignore the large evolving tail.
 * Older/partial parcels can still supply ordinary telemetry without native ERG.
 */
internal fun readBikeStatusPacket(parcel: Parcel): TitanStatusPacket? = try {
    parcel.readString() // mFWVersionNumber
    TitanStatusPacket.parse(parcel.createByteArray())
} catch (_: Exception) {
    null
}

class BikePlusCombinedSensor(
    private val binder: IBinder,
    private val onSample: (BikeSample?) -> Unit = {},
    private val clock: () -> Long = SystemClock::elapsedRealtime
) : TitanControl, com.spop.poverlay.sensor.interfaces.SensorInterface {
    private val lifecycleLock = Any()
    private val publicationLock = Any()
    @Volatile private var poller: Thread? = null
    // A sensor belongs to one binding. Stop is terminal: reconnect creates a
    // new sensor instead of reviving commands queued against the old binder.
    @Volatile private var stopRequested = false
    // Protected by lifecycleLock. Only accepted enables from this instance
    // create a teardown obligation; merely observing native status does not.
    private var ownsNativeControl = false
    private var nativeReleaseConfirmed = false

    /** The upstream checked resistance write stays separate from native ERG. */
    fun setResistance(resistance: Int): Boolean = synchronized(lifecycleLock) {
        !stopRequested && poller != null && writeBikeResistance(binder, resistance)
    }

    private val mutablePower = sensorFlow<Float>()
    override val power = mutablePower.asSharedFlow()
    private val mutableCadence = sensorFlow<Float>()
    override val cadence = mutableCadence.asSharedFlow()
    private val mutableResistance = sensorFlow<Float>()
    override val resistance = mutableResistance.asSharedFlow()
    private val mutableStatus = sensorFlow<TitanStatusPacket>()
    override val status = mutableStatus.asSharedFlow()

    @Volatile override var latestStatusReading: TitanStatusReading? = null
        private set

    fun start() = synchronized(lifecycleLock) {
        if (stopRequested || poller != null) return@synchronized
        val worker = thread(start = false, name = "BikePlusSensorPoller") { poll() }
        poller = worker
        worker.start()
    }

    private fun poll() {
        val worker = Thread.currentThread()
        val errors = ConsecutiveErrorCounter()
        Timber.i("Starting BikePlus polling thread")
        try {
            while (!stopRequested && poller === worker) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(SERVICE_ACTION)
                    check(binder.transact(TXN_GET_BIKE_DATA, data, reply, 0)) { "Bike+ read transaction rejected" }
                    reply.readException()
                    check(reply.readInt() != 0) { "Bike+ data absent" }
                    val bike = readBikeCoreData(reply)
                    val packet = readBikeStatusPacket(reply)
                    val timestamp = clock()
                    synchronized(publicationLock) {
                        // Stop can run while a blocking Binder read is in flight. Its
                        // result must never restore control or publish a stale packet.
                        if (!stopRequested && poller === worker) {
                            latestStatusReading = packet?.let { TitanStatusReading(it, timestamp) }
                            if (packet == null) mutableStatus.resetReplayCache()
                            else mutableStatus.tryEmit(packet)
                            mutablePower.tryEmit(bike.power / 100f)
                            mutableCadence.tryEmit(bike.rpm.toFloat())
                            mutableResistance.tryEmit(bike.targetResistance.toFloat())
                            onSample(BikeSample(bike.power / 100f, bike.rpm.toFloat(), bike.targetResistance,
                                timestamp, packet?.currentResistance ?: bike.currentResistance))
                        }
                    }
                    errors.reset()
                } catch (error: Exception) {
                    if (poller !== worker) break
                    try {
                        errors.increment()
                    } catch (_: Exception) {
                        Timber.w(error, "Bike+ sensor stopped after repeated read failures")
                        break
                    }
                } finally {
                    data.recycle()
                    reply.recycle()
                }
                Thread.sleep(READ_DELAY)
            }
        } catch (_: InterruptedException) {
            // stop() wakes the sleeping poller; no failed sensor read is implied.
        } catch (error: Exception) {
            Timber.e(error, "BikePlusCombinedSensor thread crashed")
        } finally {
            val wasCurrent = synchronized(lifecycleLock) {
                if (poller === worker) {
                    stopRequested = true
                    poller = null
                    releaseOwnedNativeControl()
                    true
                } else false
            }
            if (wasCurrent) publishDisconnected()
            Timber.i("BikePlus polling thread stopped")
        }
    }

    override fun setPowerZoneAutoFollow(watts: Int): Boolean =
        command(TitanCommand.Enable, watts.coerceIn(TitanControl.PZAF_POWER_RANGE))
    override fun disablePowerZoneAutoFollow(): Boolean = command(TitanCommand.Disable)
    override fun setPzafRampUpRate(rate: Int): Boolean = command(TitanCommand.RampUp, rate)
    override fun setPzafRampDownRate(rate: Int): Boolean = command(TitanCommand.RampDown, rate)
    override fun setPzafMaxResistance(percent: Int): Boolean = command(TitanCommand.MaxResistance, percent)
    override fun setPzafMinUpdateRpm(rpm: Int): Boolean = command(TitanCommand.MinRpm, rpm)

    private fun command(command: TitanCommand, argument: Int? = null): Boolean = synchronized(lifecycleLock) {
        if (stopRequested || poller == null) {
            // Read failure must revoke every command that can engage the brake,
            // while still allowing a failed teardown release to be retried.
            return@synchronized command == TitanCommand.Disable &&
                (if (ownsNativeControl) releaseOwnedNativeControl() else nativeReleaseConfirmed)
        }
        val accepted = writeTitanCommand(binder, command, argument)
        if (accepted && command == TitanCommand.Enable) {
            ownsNativeControl = true
            nativeReleaseConfirmed = false
        } else if (accepted && command == TitanCommand.Disable) {
            ownsNativeControl = false
            nativeReleaseConfirmed = true
        }
        accepted
    }

    fun stop() {
        // Set before taking the command lock so an enable queued behind an
        // in-flight enable cannot win the monitor race and arm after shutdown.
        stopRequested = true
        val worker = synchronized(lifecycleLock) {
            poller.also {
                poller = null
                releaseOwnedNativeControl()
            }
        }
        worker?.interrupt()
        publishDisconnected()
    }

    /** Caller holds lifecycleLock, including across the checked Binder reply. */
    private fun releaseOwnedNativeControl(): Boolean {
        if (!ownsNativeControl) return nativeReleaseConfirmed
        val released = writeTitanCommand(binder, TitanCommand.Disable)
        if (released) {
            ownsNativeControl = false
            nativeReleaseConfirmed = true
        }
        return released
    }

    private fun publishDisconnected() = synchronized(publicationLock) {
        if (poller == null) {
            latestStatusReading = null
            mutableStatus.resetReplayCache()
            onSample(null)
        }
    }
}

private fun <T> sensorFlow() = MutableSharedFlow<T>(
    replay = 1,
    extraBufferCapacity = 64,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)
