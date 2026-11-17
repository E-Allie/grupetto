package com.spop.poverlay.control

import com.spop.poverlay.erg.*
import org.junit.Assert.*
import org.junit.Test

class SoftwareAlgorithmSelectionTest {
    @Test fun upstreamIsSelectableAndSwitchingNeverLeavesTwoControllersWriting() {
        val r = ControlRig()
        try {
            r.resistance = 44
            repeat(30) { r.sample(168f, 90f) }
            r.control.setErgMode(ErgMode.Host)
            assertEquals(SoftwareErgAlgorithm.FirmwareTable, r.control.state.value.softwareAlgorithm)
            r.control.setSoftwareAlgorithm(SoftwareErgAlgorithm.UpstreamModel)
            assertTrue(r.control.localErg(220))
            repeat(5) { r.sample(168f, 90f) }
            assertEquals(49, r.resistance)
            val before = r.writes.toList()
            r.control.setSoftwareAlgorithm(SoftwareErgAlgorithm.FirmwareTable)
            repeat(5) { r.sample(168f, 90f) }
            assertEquals(before, r.writes)
            assertEquals(ControlFault.ConfigurationChanged, r.control.state.value.fault)
            assertTrue(r.control.resumeLocally())
            r.sample(168f, 90f)
            assertEquals(PowerTable.resistanceFor(220, 90.0)!!.toInt(), r.resistance)
        } finally { r.control.close() }
    }
    @Test fun externalMeterForcesChosenSoftwareEvenWhenNativeWasSelected() {
        val r=ControlRig()
        try {
            r.resistance=44;r.sample(168f,90f)
            r.control.setSoftwareAlgorithm(SoftwareErgAlgorithm.UpstreamModel)
            r.control.setErgMode(ErgMode.Native)
            r.control.useExternalPower(com.spop.poverlay.sensor.power.ExternalPowerReading(168,r.now,"pedals"))
            assertTrue(r.control.localErg(220));r.control.tick()
            assertEquals(ErgPath.Host,r.control.state.value.ergPath)
            assertTrue(r.resistance in 44..45)
        } finally {r.control.close()}
    }
}
