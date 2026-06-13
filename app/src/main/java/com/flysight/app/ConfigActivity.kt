package com.flysight.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var mapPickerLauncher: ActivityResultLauncher<Intent>

    private var originalText: String = ""
    private var settingsItems: List<SettingItem> = emptyList()
    private var settingsAdapter: SettingsAdapter? = null
    private var dzCoordPicker:  SettingItem.CoordPicker? = null
    private var tgtCoordPicker: SettingItem.CoordPicker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ble = (application as FlySightApp).bleManager

        binding.btnHeaderBack.setOnClickListener { finish() }
        binding.recyclerSettings.layoutManager = LinearLayoutManager(this)
        binding.btnRead.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { saveConfig() }
        binding.btnDisconnect.setOnClickListener {
            ble.disconnect()
            finish()
        }

        mapPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                if (data.getBooleanExtra(MapPickerActivity.EXTRA_HAS_DZ, false)) {
                    val lat = data.getDoubleExtra(MapPickerActivity.EXTRA_DZ_LAT, 0.0)
                    val lon = data.getDoubleExtra(MapPickerActivity.EXTRA_DZ_LON, 0.0)
                    dzCoordPicker?.apply {
                        latRaw = Math.round(lat * 1e7).toString()
                        lonRaw = Math.round(lon * 1e7).toString()
                    }
                }
                if (data.getBooleanExtra(MapPickerActivity.EXTRA_HAS_TGT, false)) {
                    val lat = data.getDoubleExtra(MapPickerActivity.EXTRA_TGT_LAT, 0.0)
                    val lon = data.getDoubleExtra(MapPickerActivity.EXTRA_TGT_LON, 0.0)
                    tgtCoordPicker?.apply {
                        latRaw = Math.round(lat * 1e7).toString()
                        lonRaw = Math.round(lon * 1e7).toString()
                    }
                }
                settingsAdapter?.notifyDataSetChanged()
            }
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

        readConfig()
    }

    private fun readConfig() {
        setLoading(true, "Reading $CONFIG_PATH…")
        lifecycleScope.launch {
            try {
                val bytes = ble.readFile(CONFIG_PATH) { received, total ->
                    binding.tvProgressBytes.text = if (total > 0)
                        "${received / 1024} KB / ${total / 1024} KB"
                    else
                        "$received bytes"
                    binding.tvProgressBytes.visibility = View.VISIBLE
                }
                originalText = String(bytes, Charsets.UTF_8)
                val settings = ConfigParser.parse(originalText)
                settingsItems = buildItems(settings)
                settingsAdapter = SettingsAdapter(settingsItems)
                binding.recyclerSettings.adapter = settingsAdapter
                binding.recyclerSettings.visibility = View.VISIBLE
                binding.tvEmpty.visibility = View.GONE
                val label = if (originalText.isBlank()) "defaults" else "${settingsItems.count { it !is SettingItem.Section }} settings"
                setStatus("$label loaded")
            } catch (e: Exception) {
                setStatus("Read error: ${e.message}")
                Toast.makeText(this@ConfigActivity, "Read failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun saveConfig() {
        if (settingsItems.isEmpty()) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val updates = ConfigSerializer.collectValues(settingsItems)
                val newText = ConfigSerializer.serialize(originalText, updates)
                val bytes   = newText.toByteArray(Charsets.UTF_8)
                setLoading(true, "Writing $CONFIG_PATH…")
                binding.progressBar.isIndeterminate = false
                binding.progressBar.max = bytes.size
                binding.progressBar.progress = 0
                ble.writeFile(CONFIG_PATH, bytes) { written, total ->
                    binding.progressBar.progress = written.toInt()
                    binding.tvProgressBytes.text = "${written / 1024} KB / ${total / 1024} KB"
                    binding.tvProgressBytes.visibility = View.VISIBLE
                }
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

    private fun openMap() {
        val intent = Intent(this, MapPickerActivity::class.java)
        dzCoordPicker?.let {
            val lat = it.latRaw.toLongOrNull() ?: 0L
            val lon = it.lonRaw.toLongOrNull() ?: 0L
            if (lat != 0L || lon != 0L) {
                intent.putExtra(MapPickerActivity.EXTRA_HAS_DZ, true)
                intent.putExtra(MapPickerActivity.EXTRA_DZ_LAT, lat / 1e7)
                intent.putExtra(MapPickerActivity.EXTRA_DZ_LON, lon / 1e7)
            }
        }
        tgtCoordPicker?.let {
            val lat = it.latRaw.toLongOrNull() ?: 0L
            val lon = it.lonRaw.toLongOrNull() ?: 0L
            if (lat != 0L || lon != 0L) {
                intent.putExtra(MapPickerActivity.EXTRA_HAS_TGT, true)
                intent.putExtra(MapPickerActivity.EXTRA_TGT_LAT, lat / 1e7)
                intent.putExtra(MapPickerActivity.EXTRA_TGT_LON, lon / 1e7)
            }
        }
        mapPickerLauncher.launch(intent)
    }

    private fun setLoading(loading: Boolean, message: String = "") {
        binding.loadingPanel.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) {
            if (message.isNotEmpty()) binding.tvLoadingStatus.text = message
            binding.progressBar.isIndeterminate = true
            binding.tvProgressBytes.visibility = View.GONE
        }
        binding.btnRead.isEnabled       = !loading
        binding.btnSave.isEnabled       = !loading && settingsItems.isNotEmpty()
        binding.btnDisconnect.isEnabled = !loading
    }

    private fun setStatus(text: String) { binding.tvStatus.text = text }

    private fun buildItems(s: Map<String, String>): List<SettingItem> {
        fun choice(key: String, label: String, opts: List<String>, vals: List<String>, hint: String? = null): SettingItem.Choice {
            val idx = vals.indexOf(s[key]).coerceAtLeast(0)
            return SettingItem.Choice(key, label, opts, vals, idx, hint = hint)
        }
        fun toggle(key: String, label: String, hint: String? = null) =
            SettingItem.Toggle(key, label, (s[key]?.toIntOrNull() ?: 0) != 0, hint = hint)
        fun num(key: String, label: String, unit: String = "", def: String = "0", hint: String? = null) =
            SettingItem.NumberInput(key, label, s[key] ?: def, unit, hint = hint)

        val gpsModelOpts = listOf("Portable", "Stationary", "Pedestrian", "Automotive",
                                   "Sea", "Airborne <1G", "Airborne <2G", "Airborne <4G")
        val gpsModelVals = listOf("0", "2", "3", "4", "5", "6", "7", "8")

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

        val dzCoord = SettingItem.CoordPicker(
            "Lat", "Lon", "DZ Coordinates",
            s["Lat"] ?: "0", s["Lon"] ?: "0",
            hint = "GPS coordinates of the DropZone."
        ) { openMap() }
        val tgtCoord = SettingItem.CoordPicker(
            "Target_Lat", "Target_Lon", "Target Coordinates",
            s["Target_Lat"] ?: "0", s["Target_Lon"] ?: "0",
            hint = "GPS coordinates of the target in Competition mode."
        ) { openMap() }
        dzCoordPicker  = dzCoord
        tgtCoordPicker = tgtCoord

        return buildList {
            // ── Navigation ───────────────────────────────────────────────────
            add(SettingItem.Section("Navigation").also { it.isExpanded = true })
            add(dzCoord)
            add(num("DZ_Elev",   "Dropzone Elevation",  "m",  "0",
                hint = "Ground elevation of the dropzone in meters. All alarm and AGL elevations are measured relative to this value."))
            add(tgtCoord)
            add(num("Bearing",   "Bearing",             "°",  "0",
                hint = "Target compass heading for navigation. FlySight will cue you when you drift off this bearing."))
            add(num("End_Nav",   "Min Altitude",        "m",  "1500",
                hint = "Navigation cues are active above this altitude AGL (meters)."))
            add(num("Max_Dist",  "Max Distance",        "m",  "10000",
                hint = "Navigation cues only play within this distance from the dropzone (meters)."))
            add(num("Min_Angle", "Min Direction Angle", "°",  "5",
                hint = "Minimum off-bearing angle (degrees) before a direction correction cue plays."))

            // ── GPS ──────────────────────────────────────────────────────────
            add(SettingItem.Section("GPS"))
            add(choice("Model", "GPS Model", gpsModelOpts, gpsModelVals,
                hint = "GPS filter model. Use 'Airborne <1G' for skydiving; higher-G models for high-performance maneuvers like wingsuit flares or HP landings."))
            add(num("Rate", "Measurement Rate", "ms", "200",
                hint = "How often GPS takes a measurement. Default: 200 ms (5 per second). Lower values use more battery and storage."))

            // ── Tone ─────────────────────────────────────────────────────────
            add(SettingItem.Section("Tone"))
            add(choice("Mode", "Mode", modeOpts, modeVals,
                hint = "Which measurement controls tone pitch. Default: glide ratio (horizontal ÷ vertical speed)."))
            add(num("Min", "Min Value", "", "0",
                hint = "Value that maps to the lowest-pitch tone. Set just outside the low end of your expected range."))
            add(num("Max", "Max Value", "", "300",
                hint = "Value that maps to the highest-pitch tone. Set just outside the high end of your expected range."))
            add(choice("Limits", "Outside Limits",
                listOf("No Tone", "Min/Max Tone", "Chirp Up/Down", "Chirp Down/Up"),
                listOf("0", "1", "2", "3"),
                hint = "What happens when your value goes outside the Min–Max range: silence, clamp to the limit tone, or play a chirp."))
            add(num("Volume", "Volume", "(0–8)", "6",
                hint = "Volume for all tones, including alarm tones (0–8)."))

            // ── Tone Rate ────────────────────────────────────────────────────
            add(SettingItem.Section("Tone Rate"))
            add(choice("Mode_2", "Rate Source", mode2Opts, mode2Vals,
                hint = "Which measurement determines how fast the tone repeats."))
            add(num("Min_Val_2", "Min Value", "", "300",
                hint = "Value corresponding to the slowest tone rate."))
            add(num("Max_Val_2", "Max Value", "", "1500",
                hint = "Value corresponding to the fastest tone rate."))
            add(num("Min_Rate", "Min Rate", "Hz×100", "100",
                hint = "Slowest tone rate (Hz×100). Default 100 = 1 beep per second."))
            add(num("Max_Rate", "Max Rate", "Hz×100", "500",
                hint = "Fastest tone rate (Hz×100). Default 500 = 5 beeps per second."))
            add(toggle("Flatline", "Flatline at Min Rate",
                hint = "Play a steady continuous tone when the rate drops to minimum, instead of slow beeps."))

            // ── Speech ───────────────────────────────────────────────────────
            add(SettingItem.Section("Speech"))
            add(num("Sp_Rate", "Rate", "s", "0",
                hint = "How often a value is spoken aloud. Set to 0 to disable. Typical: 3–10 seconds."))
            add(num("Sp_Volume", "Volume", "(0–8)", "6",
                hint = "Volume for speech and any audio files played as alarms (0–8)."))
            add(choice("Sp_Mode", "Mode", spModeOpts, spModeVals,
                hint = "Which measurement is spoken aloud."))
            add(choice("Sp_Units", "Units",
                listOf("km/h or m", "mph or feet"),
                listOf("0", "1"),
                hint = "Units used when speaking values. Independent of display units."))
            add(num("Sp_Dec", "Decimal Places", "", "1",
                hint = "Decimal places in the spoken value. Use 0 for speed (e.g. 'two one zero'), 1 for glide ratio (e.g. 'two point five')."))

            // ── Thresholds ───────────────────────────────────────────────────
            add(SettingItem.Section("Thresholds"))
            add(num("V_Thresh", "Vertical Speed",   "cm/s", "1000",
                hint = "Minimum vertical speed (cm/s) before audio plays. Default 1000 cm/s suppresses tones in the aircraft and under canopy. Set both thresholds to 0 to test on the ground."))
            add(num("H_Thresh", "Horizontal Speed", "cm/s", "0",
                hint = "Minimum horizontal speed (cm/s) before audio plays. Set both thresholds to 0 to test on the ground."))

            // ── Miscellaneous ────────────────────────────────────────────────
            add(SettingItem.Section("Miscellaneous"))
            add(toggle("Use_SAS", "Use SAS Altitude",
                hint = "Adjust speed to a sea-level equivalent so tone pitch stays consistent as air density changes through freefall. Does not affect logged values."))
            add(num("TZ_Offset", "Timezone Offset", "s", "0",
                hint = "UTC offset in seconds for log file and folder names. Does not affect the time values stored in the log."))

            // ── Initialization ───────────────────────────────────────────────
            add(SettingItem.Section("Initialization"))
            add(choice("Init_Mode", "On Power-On",
                listOf("Do Nothing", "Test Speech Mode", "Play File"),
                listOf("0", "1", "2"),
                hint = "What FlySight does immediately after powering on: nothing, a speech test of all digits, or play a specific audio file."))

            // ── Altitude Alarms ──────────────────────────────────────────────
            add(SettingItem.Section("Altitude Alarms"))
            add(num("Win_Above", "Window Above", "m", "0",
                hint = "Meters above an alarm elevation during which background tones and speech are silenced. Helps make the alarm audibly distinct (~50 m is typical for wingsuiting)."))
            add(num("Win_Below", "Window Below", "m", "0",
                hint = "Meters below an alarm elevation during which background tones and speech are silenced."))

            // ── Altitude Mode ────────────────────────────────────────────────
            add(SettingItem.Section("Altitude Mode"))
            add(choice("Alt_Units", "Units",
                listOf("Meters", "Feet"),
                listOf("0", "1")))
            add(num("Alt_Step", "Step", "", "0",
                hint = "Altitude callout step size. E.g. 500 ft → 'nine thousand five hundred'. Use 1 for exact altitude. Not called out below 1500 m AGL."))

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

            // ── Sensor Settings ──────────────────────────────────────────────
            add(SettingItem.Section("Sensor Settings"))
            add(num("Baro_ODR",  "Barometer ODR",       "(0–7)",  "2"))
            add(num("Hum_ODR",   "Humidity ODR",        "(0–3)",  "1"))
            add(num("Mag_ODR",   "Magnetometer ODR",    "(0–3)",  "0"))
            add(num("Accel_ODR", "Accelerometer ODR",   "(0–11)", "1"))
            add(num("Accel_FS",  "Accelerometer FS",    "(0–3)",  "1"))
            add(num("Gyro_ODR",  "Gyroscope ODR",       "(0–10)", "1"))
            add(num("Gyro_FS",   "Gyroscope FS",        "(0–3)",  "3"))

            // ── ActiveLook ───────────────────────────────────────────────────
            add(SettingItem.Section("ActiveLook"))
            add(choice("AL_Mode", "Mode",
                listOf("Not Active", "Default Mode"),
                listOf("0", "1")))
            add(num("AL_Rate", "Rate", "ms", "1000"))
        }
    }
}
