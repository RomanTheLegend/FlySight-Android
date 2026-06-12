package com.flysight.app

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.flysight.app.ble.BleManager
import com.flysight.app.ble.BleState
import com.flysight.app.ble.CONFIG_PATH
import com.flysight.app.config.ConfigParser
import com.flysight.app.config.ConfigSerializer
import com.flysight.app.config.SettingItem
import com.flysight.app.config.SettingsAdapter
import com.flysight.app.databinding.ActivityConfigBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigBinding
    private lateinit var ble: BleManager

    private var originalText: String = ""
    private var settingsItems: List<SettingItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ble = (application as FlySightApp).bleManager

        binding.btnHeaderBack.setOnClickListener { finish() }

        binding.recyclerSettings.layoutManager = LinearLayoutManager(this)

        binding.btnRead.setOnClickListener { readConfig() }
        binding.btnSave.setOnClickListener { saveConfig() }
        binding.btnDisconnect.setOnClickListener {
            ble.disconnect()
            finish()
        }

        lifecycleScope.launch {
            ble.state.collectLatest { state ->
                when {
                    state == BleState.Disconnected -> {
                        Toast.makeText(this@ConfigActivity, "Disconnected", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    state is BleState.Error -> {
                        setLoading(false)
                        Toast.makeText(this@ConfigActivity, state.msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun readConfig() {
        setLoading(true)
        setStatus("Reading $CONFIG_PATH…")
        lifecycleScope.launch {
            try {
                val bytes = ble.readFile(CONFIG_PATH)
                originalText = String(bytes, Charsets.UTF_8)
                val settings = ConfigParser.parse(originalText)
                settingsItems = buildItems(settings)
                binding.recyclerSettings.adapter = SettingsAdapter(settingsItems)
                binding.recyclerSettings.visibility = View.VISIBLE
                binding.tvEmpty.visibility = View.GONE
                setStatus("${settingsItems.count { it !is SettingItem.Section }} settings loaded")
                binding.btnSave.isEnabled = true
            } catch (e: Exception) {
                setStatus("Read error: ${e.message}")
                Toast.makeText(this@ConfigActivity, "Read failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun saveConfig() {
        if (originalText.isBlank()) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            return
        }
        setLoading(true)
        setStatus("Writing $CONFIG_PATH…")
        lifecycleScope.launch {
            try {
                val updates = ConfigSerializer.collectValues(settingsItems)
                val newText = ConfigSerializer.serialize(originalText, updates)
                val bytes   = newText.toByteArray(Charsets.UTF_8)
                ble.writeFile(CONFIG_PATH, bytes)
                originalText = newText
                setStatus("Saved ${bytes.size} bytes  ✓")
                Toast.makeText(this@ConfigActivity, "Config saved!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                setStatus("Write error: ${e.message}")
                Toast.makeText(this@ConfigActivity, "Write failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility  = if (loading) View.VISIBLE else View.GONE
        binding.btnRead.isEnabled       = !loading
        binding.btnSave.isEnabled       = !loading && originalText.isNotBlank()
        binding.btnDisconnect.isEnabled = !loading
    }

    private fun setStatus(text: String) { binding.tvStatus.text = text }

    private fun buildItems(s: Map<String, String>): List<SettingItem> {
        fun choice(key: String, label: String, opts: List<String>, vals: List<String>): SettingItem.Choice {
            val idx = vals.indexOf(s[key]).coerceAtLeast(0)
            return SettingItem.Choice(key, label, opts, vals, idx)
        }
        fun toggle(key: String, label: String) =
            SettingItem.Toggle(key, label, (s[key]?.toIntOrNull() ?: 0) != 0)
        fun num(key: String, label: String, unit: String = "", def: String = "0") =
            SettingItem.NumberInput(key, label, s[key] ?: def, unit)

        // GPS model: firmware skips value 1 (no such model)
        val gpsModelOpts = listOf("Portable", "Stationary", "Pedestrian", "Automotive",
                                   "Sea", "Airborne <1G", "Airborne <2G", "Airborne <4G")
        val gpsModelVals = listOf("0", "2", "3", "4", "5", "6", "7", "8")

        // Tone/speech mode options per config.c HANDLE_VALUE constraints
        val modeOpts = listOf("Horizontal Speed", "Vertical Speed", "Glide Ratio",
                               "Inverse Glide Ratio", "Total Speed",
                               "Direction to Dest.", "Distance to Dest.",
                               "Direction to Bearing", "Dive Angle")
        val modeVals = listOf("0", "1", "2", "3", "4", "5", "6", "7", "11")

        val mode2Opts = listOf("Horizontal Speed", "Vertical Speed", "Glide Ratio",
                                "Inverse Glide Ratio", "Total Speed",
                                "Magnitude of Value 1", "Change in Value 1", "Dive Angle")
        val mode2Vals = listOf("0", "1", "2", "3", "4", "8", "9", "11")

        val spModeOpts = modeOpts + listOf("Altitude above DZ")
        val spModeVals = modeVals + listOf("12")

        return buildList {
            // ── GPS ──────────────────────────────────────────────────────────
            add(SettingItem.Section("GPS"))
            add(choice("Model", "GPS Model", gpsModelOpts, gpsModelVals))
            add(num("Rate", "Measurement Rate", "ms", "200"))

            // ── Tone ─────────────────────────────────────────────────────────
            add(SettingItem.Section("Tone"))
            add(choice("Mode", "Mode", modeOpts, modeVals))
            add(num("Min", "Min Value", "", "0"))
            add(num("Max", "Max Value", "", "300"))
            add(choice("Limits", "Outside Limits",
                listOf("No Tone", "Min/Max Tone", "Chirp Up/Down", "Chirp Down/Up"),
                listOf("0", "1", "2", "3")))
            add(num("Volume", "Volume", "(0–8)", "6"))

            // ── Tone Rate ────────────────────────────────────────────────────
            add(SettingItem.Section("Tone Rate"))
            add(choice("Mode_2", "Rate Source", mode2Opts, mode2Vals))
            add(num("Min_Val_2", "Min Value", "", "300"))
            add(num("Max_Val_2", "Max Value", "", "1500"))
            add(num("Min_Rate", "Min Rate", "Hz×100", "100"))
            add(num("Max_Rate", "Max Rate", "Hz×100", "500"))
            add(toggle("Flatline", "Flatline at Min Rate"))

            // ── Speech ───────────────────────────────────────────────────────
            add(SettingItem.Section("Speech"))
            add(num("Sp_Rate", "Rate", "s", "0"))
            add(num("Sp_Volume", "Volume", "(0–8)", "6"))
            add(choice("Sp_Mode", "Mode", spModeOpts, spModeVals))
            add(choice("Sp_Units", "Units",
                listOf("km/h or m", "mph or feet"),
                listOf("0", "1")))
            add(num("Sp_Dec", "Decimal Places", "", "1"))

            // ── Thresholds ───────────────────────────────────────────────────
            add(SettingItem.Section("Thresholds"))
            add(num("V_Thresh", "Vertical Speed",   "cm/s", "1000"))
            add(num("H_Thresh", "Horizontal Speed", "cm/s", "0"))

            // ── Miscellaneous ────────────────────────────────────────────────
            add(SettingItem.Section("Miscellaneous"))
            add(toggle("Use_SAS", "Use SAS Altitude"))
            add(num("TZ_Offset", "Timezone Offset", "s", "0"))

            // ── Initialization ───────────────────────────────────────────────
            add(SettingItem.Section("Initialization"))
            add(choice("Init_Mode", "On Power-On",
                listOf("Do Nothing", "Test Speech Mode", "Play File"),
                listOf("0", "1", "2")))

            // ── Altitude Alarms ──────────────────────────────────────────────
            add(SettingItem.Section("Altitude Alarms"))
            add(num("DZ_Elev",    "Dropzone Elevation", "m", "0"))
            add(num("Win_Above",  "Window Above",        "m", "0"))
            add(num("Win_Below",  "Window Below",        "m", "0"))

            // ── Altitude Mode ────────────────────────────────────────────────
            add(SettingItem.Section("Altitude Mode"))
            add(choice("Alt_Units", "Units",
                listOf("Meters", "Feet"),
                listOf("0", "1")))
            add(num("Alt_Step", "Step", "", "0"))

            // ── Enable Modules ───────────────────────────────────────────────
            add(SettingItem.Section("Enable Modules"))
            add(toggle("Enable_Audio",   "Audio Output"))
            add(toggle("Enable_Logging", "Data Logging"))
            add(toggle("Enable_Vbat",    "Battery Voltage"))
            add(toggle("Enable_Mic",     "Microphone"))
            add(toggle("Enable_Imu",     "IMU (Accel/Gyro)"))
            add(toggle("Enable_Gnss",    "GNSS"))
            add(toggle("Enable_Baro",    "Barometer"))
            add(toggle("Enable_Hum",     "Humidity Sensor"))
            add(toggle("Enable_Mag",     "Magnetometer"))
            add(toggle("Enable_Raw",     "Raw Sensor Data"))
            add(toggle("Cold_Start",     "Cold Start"))
            add(num("Ble_Tx_Power",      "BLE TX Power", "(0–31)", "25"))

            // ── Sensor ODR / FS ──────────────────────────────────────────────
            add(SettingItem.Section("Sensor Settings"))
            add(num("Baro_ODR",  "Barometer ODR",       "(0–7)",  "2"))
            add(num("Hum_ODR",   "Humidity ODR",        "(0–3)",  "1"))
            add(num("Mag_ODR",   "Magnetometer ODR",    "(0–3)",  "0"))
            add(num("Accel_ODR", "Accelerometer ODR",   "(0–11)", "1"))
            add(num("Accel_FS",  "Accelerometer FS",    "(0–3)",  "1"))
            add(num("Gyro_ODR",  "Gyroscope ODR",       "(0–10)", "1"))
            add(num("Gyro_FS",   "Gyroscope FS",        "(0–3)",  "3"))

            // ── Navigation ───────────────────────────────────────────────────
            add(SettingItem.Section("Navigation"))
            add(num("Lat",       "Latitude",         "×1e-7°", "0"))
            add(num("Lon",       "Longitude",        "×1e-7°", "0"))
            add(num("Bearing",   "Bearing",          "°",      "0"))
            add(num("End_Nav",   "Min Altitude",     "m",      "1500"))
            add(num("Max_Dist",  "Max Distance",     "m",      "10000"))
            add(num("Min_Angle", "Min Direction Angle", "°",   "5"))
            add(num("Target_Lat","Target Latitude",  "×1e-7°", "0"))
            add(num("Target_Lon","Target Longitude", "×1e-7°", "0"))

            // ── ActiveLook ───────────────────────────────────────────────────
            add(SettingItem.Section("ActiveLook"))
            add(choice("AL_Mode", "Mode",
                listOf("Not Active", "Default Mode"),
                listOf("0", "1")))
            add(num("AL_Rate", "Rate", "ms", "1000"))
        }
    }
}
