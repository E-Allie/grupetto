package com.spop.poverlay

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import com.spop.poverlay.ble.BleServer
import com.spop.poverlay.erg.ErgCoordinator
import com.spop.poverlay.erg.ErgMode
import com.spop.poverlay.sensor.interfaces.DummySensorInterface
import com.spop.poverlay.sensor.interfaces.PelotonBikePlusSensorInterface
import com.spop.poverlay.sensor.interfaces.PelotonBikeSensorInterfaceV1New
import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.sim.SimulationPreferences
import com.spop.poverlay.sim.TrainerController
import com.spop.poverlay.util.IsBikePlus
import com.spop.poverlay.util.IsG700CrossTrainer
import com.spop.poverlay.util.IsRunningOnPeloton
import timber.log.Timber

class GrupettoApplication : Application() {
    lateinit var bleServer: BleServer
        private set

    /**
     * Held on the application so the overlay's resume control can reach it. The
     * rider's target outlives any one FTMS connection, and after a stand-down the
     * only thing that knows what they were holding is this object.
     */
    lateinit var ergController: ErgCoordinator
        private set
    lateinit var trainerController: TrainerController
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val sensorInterface = createSensorInterface()
        ergController = ErgCoordinator(sensorInterface, ::ergMode)
        trainerController = TrainerController(sensorInterface, ergController,
            settingsProvider = { SimulationPreferences.read(getSharedPreferences(ConfigurationRepository.SharedPrefsName, MODE_PRIVATE)) })
        bleServer = BleServer(this, bluetoothManager, sensorInterface, ergController, trainerController)
    }

    /**
     * Read fresh each time rather than cached. SharedPreferences is an in-memory
     * map after the first load, and reading on demand means a change on the
     * configuration page takes effect on the next target without a listener or a
     * restart.
     */
    private fun ergMode(): ErgMode = ErgMode.read(
        getSharedPreferences(ConfigurationRepository.SharedPrefsName, Context.MODE_PRIVATE),
        ConfigurationRepository.Preferences.ErgControl.key
    )

    private fun createSensorInterface(): SensorInterface {
        return if (IsRunningOnPeloton) {
            if (IsG700CrossTrainer || IsBikePlus) {
                PelotonBikePlusSensorInterface(this)
            } else {
                PelotonBikeSensorInterfaceV1New(this)
            }
        } else {
            DummySensorInterface()
        }
    }
}
