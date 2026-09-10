package com.spop.poverlay.erg

import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.sensor.v2.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow

class FakePowerController : PowerController {
    val writes = mutableListOf<Int>()
    override var isActive = false
    override var targetWatts = 0
    var beforeEnable: () -> Unit = {}
    override fun enable(watts: Int) { beforeEnable(); isActive = true; targetWatts = watts; writes += watts }
    override fun setTarget(watts: Int) { targetWatts = watts; writes += watts }
    override fun disable() { isActive = false }
}

class FakeControlSensor(override val titanControl: TitanControl? = null) : SensorInterface {
    override val supportsResistanceControl = true
    override val power = MutableStateFlow(150f)
    override val cadence = MutableSharedFlow<Float>(replay = 1, extraBufferCapacity = 16).apply { tryEmit(85f) }
    override val resistance = MutableSharedFlow<Float>(replay = 1, extraBufferCapacity = 16).apply { tryEmit(20f) }
    val resistanceWrites = mutableListOf<Int>()
    override fun setResistance(resistance: Int) {
        resistanceWrites += resistance
        this.resistance.tryEmit(resistance.toFloat())
    }
}

class FakeNativeControl : TitanControl {
    override val status = MutableStateFlow(packet(PzafStatus.DISABLED_BY_COMMAND))
    override val latestStatus get() = status.value
    val targets = mutableListOf<Int>()
    var echoEnable = true
    fun standDown() { status.value = packet(PzafStatus.DISABLED_BY_KNOB) }
    override fun setPowerZoneAutoFollow(watts: Int) {
        targets += watts
        if (echoEnable) status.value = packet(PzafStatus.ENABLED_ACTIVE)
    }
    override fun disablePowerZoneAutoFollow() { status.value = packet(PzafStatus.DISABLED_BY_COMMAND) }
    override fun setPzafRampUpRate(rate: Int) = true
    override fun setPzafRampDownRate(rate: Int) = true
    override fun setPzafMaxResistance(percent: Int) = true
    override fun setPzafMinUpdateRpm(rpm: Int) = true
    private fun packet(status: Int) = TitanStatusPacket.parse(titanPacket().also { it[247] = status.toByte() })!!
}
