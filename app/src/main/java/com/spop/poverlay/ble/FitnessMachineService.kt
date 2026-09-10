package com.spop.poverlay.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import com.spop.poverlay.erg.ErgCoordinator
import com.spop.poverlay.erg.ErgPath
import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.sensor.v2.PzafStatus
import com.spop.poverlay.sim.TrainerController
import com.spop.poverlay.sim.TrainerMode
import timber.log.Timber

@Suppress("DEPRECATION")
class FitnessMachineService(
    server: BleServer,
    private val ergController: ErgCoordinator,
    private val sensorInterface: SensorInterface,
    private val trainerController: TrainerController
) : BaseBleService(server) {

    /**
     * Whether this bike can actually act on a resistance or power target.
     *
     * Only the Bike+ has a motorised brake. On every other Peloton the FTMS
     * target-setting features are omitted from the Feature characteristic, the
     * supported-range characteristics are left off the service, and the
     * corresponding Control Point procedures answer OpCodeNotSupported, so a
     * controller app can discover the limitation instead of silently sending
     * targets into a no-op.
     */
    private val resistanceControlSupported = sensorInterface.supportsResistanceControl

    private val indoorBikeDataCharacteristic = BluetoothGattCharacteristic(
        FitnessMachineConstants.IndoorBikeDataUUID,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply {
        addDescriptor(
            BluetoothGattDescriptor(
                FitnessMachineConstants.ClientCharacteristicConfigurationUUID,
                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
            )
        )
    }

    private val featureCharacteristic = BluetoothGattCharacteristic(
        FitnessMachineConstants.FeatureUUID,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply {
        // 8-byte payload: 4 bytes FeatureFlags + 4 bytes TargetFlags (LE)
        val featureFlags =
            FitnessMachineConstants.FeatureFlags.CadenceSupported or
            FitnessMachineConstants.FeatureFlags.PowerMeasurementSupported or
            FitnessMachineConstants.FeatureFlags.ResistanceLevelSupported

        // ResistanceLevelSupported above is a *measurement* feature and stays on
        // every bike; these are the settable ones and depend on the brake.
        //
        // Terrain control is an explicit setting. Re-registering after a change
        // lets clients discover the matching feature bit before sending commands.
        val targetFlags = if (resistanceControlSupported) {
            FitnessMachineConstants.FitnessMachineTargetFlags.ResistanceTargetSettingSupported or
                FitnessMachineConstants.FitnessMachineTargetFlags.PowerTargetSettingSupported or
                (if (trainerController.supportsSimulation)
                    FitnessMachineConstants.FitnessMachineTargetFlags.IndoorBikeSimulationParametersSupported else 0)
        } else {
            0
        }

        val payload = byteArrayOf(
            // Feature flags (uint32 LE)
            (featureFlags and 0xFF).toByte(),
            (featureFlags shr 8 and 0xFF).toByte(),
            (featureFlags shr 16 and 0xFF).toByte(),
            (featureFlags shr 24 and 0xFF).toByte(),
            // Target flags (uint32 LE)
            (targetFlags and 0xFF).toByte(),
            (targetFlags shr 8 and 0xFF).toByte(),
            (targetFlags shr 16 and 0xFF).toByte(),
            (targetFlags shr 24 and 0xFF).toByte()
        )
        setValue(payload)
    }

    private val controlPointCharacteristic = BluetoothGattCharacteristic(
        FitnessMachineConstants.ControlPointUUID,
    BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_WRITE
    ).apply {
        addDescriptor(
            BluetoothGattDescriptor(
                FitnessMachineConstants.ClientCharacteristicConfigurationUUID,
                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
            )
        )
    }

    private val supportedResistanceRangeCharacteristic = BluetoothGattCharacteristic(
        FitnessMachineConstants.SupportedResistanceRangeUUID,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply {
        // FTMS uses 0.1 resolution. Peloton range 0-100 maps to 0-1000 in FTMS units.
        // Little-endian sint16: min=0 (0%), max=1000 (100%), step=10 (1%)
        setValue(byteArrayOf(
            0x00, 0x00,             // min = 0
            0xE8.toByte(), 0x03,    // max = 1000
            0x0A, 0x00              // step = 10
        ))
    }

    private val trainingStatusCharacteristic = BluetoothGattCharacteristic(
        FitnessMachineConstants.TrainingStatusUUID,
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply {
        // Common FTMS layout: first byte is additional info (0x00), second is status (Idle)
        setValue(byteArrayOf(0x00, FitnessMachineConstants.TrainingStatus.Idle.toByte()))
        addDescriptor(
            BluetoothGattDescriptor(
                FitnessMachineConstants.ClientCharacteristicConfigurationUUID,
                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
            )
        )
    }

    private val fitnessMachineStatusCharacteristic = BluetoothGattCharacteristic(
        FitnessMachineConstants.FitnessMachineStatusUUID,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply {
        addDescriptor(
            BluetoothGattDescriptor(
                FitnessMachineConstants.ClientCharacteristicConfigurationUUID,
                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
            )
        )
    }

    private val supportedPowerRangeCharacteristic = BluetoothGattCharacteristic(
        FitnessMachineConstants.SupportedPowerRangeUUID,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply {
        // A stable range supported by either selectable ERG backend. Internal
        // SIM demand uses each backend's own limits, independently of this field.
        // Little-endian: min(25W), max(800W), step(1W).
        setValue(byteArrayOf(
            0x19, 0x00,       // min = 25
            0x20, 0x03, // max = 800: supported by either selectable backend
            0x01, 0x00        // step = 1
        ))
    }

    override val service = BluetoothGattService(
        FitnessMachineConstants.ServiceUUID,
        BluetoothGattService.SERVICE_TYPE_PRIMARY
    ).apply {
        addCharacteristic(indoorBikeDataCharacteristic)
        addCharacteristic(featureCharacteristic)
        // Control Point stays on every bike: RequestControl, Reset, Start and Stop
        // are still meaningful without a brake. Only the target procedures are gated.
        addCharacteristic(controlPointCharacteristic)
        if (resistanceControlSupported) {
            // FTMS requires these ranges only when the matching target flag is set,
            // and advertising them without the flag misleads controller apps.
            addCharacteristic(supportedResistanceRangeCharacteristic)
            addCharacteristic(supportedPowerRangeCharacteristic)
        }
        addCharacteristic(trainingStatusCharacteristic)
        addCharacteristic(fitnessMachineStatusCharacteristic)
    }

    @Synchronized
    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?
    ) {
        if (characteristic.uuid == FitnessMachineConstants.ControlPointUUID) {
            if (preparedWrite || offset != 0) {
                if (responseNeeded) server.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            respondToControlPoint(value, runControlPointProcedure(value, "gatt:${device.address}"), device)
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            return
        }

        // Default behavior for other characteristics
        super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
    }

    /**
     * DIRCON carries the same FTMS Control Point writes that GATT does, but the
     * bridge carries the connection identity so only the requester gets a reply.
     */
    @Synchronized
    override fun onDirConCharacteristicWrite(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        clientId: String
    ): Boolean {
        if (characteristic.uuid != FitnessMachineConstants.ControlPointUUID) {
            return super.onDirConCharacteristicWrite(characteristic, value, clientId)
        }
        respondToControlPoint(value, runControlPointProcedure(value, clientId), device = null, clientId = clientId)
        return true
    }

    private fun respondToControlPoint(
        request: ByteArray?,
        result: Int,
        device: BluetoothDevice?,
        clientId: String? = null
    ) {
        val opcode = request?.getOrNull(0)?.toInt()?.and(0xFF) ?: -1
        // Build Response Code indication: [0x80, requestOpCode, resultCode]
        controlPointCharacteristic.setValue(
            byteArrayOf(
                FitnessMachineConstants.FitnessMachineControlPointProcedure.ResponseCode.toByte(),
                (opcode.coerceAtLeast(0) and 0xFF).toByte(),
                (result and 0xFF).toByte()
            )
        )
        // FTMS mandates indications for Control Point
        if (device != null) {
            server.notifyCharacteristicChanged(device, controlPointCharacteristic, true)
        } else {
            server.notifyDirConCharacteristicChanged(controlPointCharacteristic, clientId)
        }
    }

    private fun runControlPointProcedure(value: ByteArray?, clientId: String): Int {
        val opcode = value?.firstOrNull()?.toInt()?.and(255) ?: -1
        val previousOwner = trainerController.state.value.owner
        val result = trainerController.command(clientId, value)
        if (result == TrainerController.SUCCESS && opcode == 0 && previousOwner != null && previousOwner != clientId) {
            notifyControlPermissionLost(previousOwner)
        }
        if (result == TrainerController.SUCCESS) {
            val status = when (opcode) {
                1 -> byteArrayOf(FitnessMachineConstants.FitnessMachineStatus.Reset.toByte())
                7 -> byteArrayOf(FitnessMachineConstants.FitnessMachineStatus.StartedOrResumedByUser.toByte())
                8 -> byteArrayOf(FitnessMachineConstants.FitnessMachineStatus.StoppedOrPausedByUser.toByte(), value!![1])
                4 -> byteArrayOf(FitnessMachineConstants.FitnessMachineStatus.TargetResistanceLevelChanged.toByte()) + value!!.copyOfRange(1, 3)
                5 -> byteArrayOf(FitnessMachineConstants.FitnessMachineStatus.TargetPowerChanged.toByte()) + value!!.copyOfRange(1, 3)
                17 -> byteArrayOf(FitnessMachineConstants.FitnessMachineStatus.IndoorBikeSimulationParametersChanged.toByte()) + value!!.copyOfRange(1, 7)
                else -> null
            }
            status?.let(::notifyFitnessMachineStatus)
            setTrainingStatus(currentTrainingStatus())
        }
        return result
    }

    /** Revoke only the old reservation; observers/new owners must not reacquire. */
    private fun notifyControlPermissionLost(owner: String) {
        fitnessMachineStatusCharacteristic.setValue(byteArrayOf(
            FitnessMachineConstants.FitnessMachineStatus.ControlPermissionLost.toByte()))
        if (owner.startsWith("dircon:")) {
            server.notifyDirConCharacteristicChanged(fitnessMachineStatusCharacteristic, owner)
        } else {
            connectedDevices.firstOrNull { "gatt:${it.address}" == owner }?.let {
                server.notifyCharacteristicChanged(it, fitnessMachineStatusCharacteristic, false)
            }
        }
    }

    override fun onDisconnected(device: BluetoothDevice) {
        super.onDisconnected(device)
        trainerController.disconnected("gatt:${device.address}")
    }

    private fun currentTrainingStatus(cadence: Float = 0f): Int {
        val state = trainerController.state.value
        return when {
            !state.controlEngaged -> FitnessMachineConstants.TrainingStatus.Idle
            state.suspension != null || state.paused -> FitnessMachineConstants.TrainingStatus.Idle
            state.mode == TrainerMode.Simulation -> FitnessMachineConstants.TrainingStatus.Other
            state.mode == TrainerMode.Erg && ergController.isActive -> FitnessMachineConstants.TrainingStatus.WattControl
            state.mode == TrainerMode.Resistance || cadence > 0 -> FitnessMachineConstants.TrainingStatus.ManualMode
            else -> FitnessMachineConstants.TrainingStatus.Idle
        }
    }

    /**
     * Training Status, carrying the name of the loop that is holding the target.
     *
     * Which loop [ErgCoordinator] resolved is a decision with real consequences
     * for a controller app -- the two behave differently and only one of them
     * reports a stand-down -- and until now it had no representation on the wire
     * at all, so anything outside this process had to be told by the rider.
     *
     * The Training Status characteristic already has the field for it. FTMS
     * defines an optional UTF-8 status string after the status byte, announced
     * by bit 0 of the flags, so this is the specification's own mechanism rather
     * than a private extension: a client that does not want the string never
     * looks past byte 1, and one that does gets the same words the
     * configuration page shows the rider.
     *
     * The string is omitted entirely until a target has been sent, because
     * before that no path has been resolved and naming one would be a guess.
     */
    private fun trainingStatusPayload(status: Int): ByteArray {
        val path = when (ergController.state.value.path) {
            ErgPath.Native -> "Bike PZAF"
            ErgPath.Host -> "App PID"
            ErgPath.None -> null
        } ?: return byteArrayOf(0x00, status.toByte())

        val flags = FitnessMachineConstants.TrainingStatusFlags.StringPresent
        val mode = if (trainerController.state.value.mode == TrainerMode.Simulation) "SIM" else "ERG"
        return byteArrayOf(flags.toByte(), status.toByte()) + "$mode · $path".toByteArray(Charsets.UTF_8)
    }

    /**
     * Publishes Training Status, and only when it has actually changed.
     *
     * The comparison is over the whole payload rather than the status byte,
     * which is what lets a path switch reach subscribers on its own: moving
     * from the host loop to the controller's does not change the status -- both
     * are watt control -- and under the old rule that change was invisible.
     */
    private fun setTrainingStatus(status: Int) {
        val payload = trainingStatusPayload(status)
        if (payload.contentEquals(trainingStatusCharacteristic.getValue())) return

        trainingStatusCharacteristic.setValue(payload)
        server.notifyDirConCharacteristicChanged(trainingStatusCharacteristic)
        for (d in connectedDevices) {
            server.notifyCharacteristicChanged(d, trainingStatusCharacteristic, false)
        }
    }

    private fun notifyFitnessMachineStatus(statusValue: ByteArray) {
        fitnessMachineStatusCharacteristic.setValue(statusValue)
        server.notifyDirConCharacteristicChanged(fitnessMachineStatusCharacteristic)
        for (d in connectedDevices) {
            server.notifyCharacteristicChanged(d, fitnessMachineStatusCharacteristic, false)
        }
    }

    override fun onSensorDataUpdated(cadence: Float, power: Float, speed: Float, resistance: Float) {
        // Build 16-bit flags (LE when serialized). MoreData bit (0) is intentionally 0.
        val flags = FitnessMachineConstants.IndoorBikeDataFlags.InstantaneousCadencePresent or
            FitnessMachineConstants.IndoorBikeDataFlags.InstantaneousPowerPresent or
            FitnessMachineConstants.IndoorBikeDataFlags.ResistanceLevelPresent

        val speedKmh = speed * 1.60934f // fixes the Issue #30 in the doudar fork of grupetto
        val speedValue = (speedKmh * 100).toInt() // fixes the Issue #30 in the doudar fork of grupetto
        val cadenceValue = (cadence * 2).toInt()
        val powerValue = power.toInt()
        val resistanceValue = resistance.toInt()

        indoorBikeDataCharacteristic.setValue(byteArrayOf(
            (flags and 0xFF).toByte(),
            (flags shr 8 and 0xFF).toByte(),
            (speedValue and 0xFF).toByte(),
            (speedValue shr 8 and 0xFF).toByte(),
            (cadenceValue and 0xFF).toByte(),
            (cadenceValue shr 8 and 0xFF).toByte(),
            (resistanceValue and 0xFF).toByte(),
            (resistanceValue shr 8 and 0xFF).toByte(),
            (powerValue and 0xFF).toByte(),
            (powerValue shr 8 and 0xFF).toByte()
        ))
        server.notifyDirConCharacteristicChanged(indoorBikeDataCharacteristic)

        for (device in connectedDevices) {
            server.notifyCharacteristicChanged(device, indoorBikeDataCharacteristic, false)
        }

        setTrainingStatus(currentTrainingStatus(cadence))
    }

    init {
        // The controller can drop PZAF on its own -- knob, homing, calibration,
        // low power, error, or sixty seconds without pedalling. Nothing re-arms
        // it: the knob is the rider's only control once software has stopped,
        // and grabbing the brake back would fight that. What this does is tell
        // the controller app the truth, so it stops drawing a target that is no
        // longer being held. The rider's target is kept by ErgCoordinator and
        // comes back from the overlay's ERG control.
        // A path switch does not change the training status -- both loops are
        // watt control -- so the republish has to be driven by the change
        // itself rather than waiting for a status that will not move.
        ergController.onPathChanged = { path ->
            Timber.i("FTMS reporting ERG path: %s", path)
            setTrainingStatus(currentTrainingStatus())
        }

        ergController.onStandDown = { status ->
            val cause = if (status == PzafStatus.DISABLED_BY_KNOB ||
                status == PzafStatus.DISABLED_BY_NO_USAGE_TIMEOUT
            ) {
                FitnessMachineConstants.FitnessMachineStatus.StoppedOrPausedByUser
            } else {
                FitnessMachineConstants.FitnessMachineStatus.StoppedBySafetyKey
            }
            Timber.w("FTMS reporting PZAF stand down: %s", PzafStatus.name(status))
            setTrainingStatus(FitnessMachineConstants.TrainingStatus.Idle)
            notifyFitnessMachineStatus(if (cause == FitnessMachineConstants.FitnessMachineStatus.StoppedOrPausedByUser)
                byteArrayOf(cause.toByte(), 0x02) else byteArrayOf(cause.toByte()))
        }
    }
}
