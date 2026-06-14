package com.flysight.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flysight.app.ble.BleManager
import com.flysight.app.ble.BleState
import com.flysight.app.ble.RSSI_UNKNOWN
import com.flysight.app.ble.ScannedDevice
import com.flysight.app.config.ConfigActivity
import com.flysight.app.databinding.ActivityMainBinding
import com.flysight.app.ui.CompareTracksActivity
import com.flysight.app.ui.FileBrowserActivity
import com.flysight.app.ui.ScoreViewActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val LABEL_SCAN = "Scan for devices"
private const val LABEL_STOP = "Stop scanning"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ble: BleManager
    private lateinit var adapter: DeviceAdapter
    private lateinit var adapterPaired: DeviceAdapter

    private var connectingDevice: ScannedDevice? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) startScan()
        else Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_LONG).show()
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { startScan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ble = (application as FlySightApp).bleManager

        val connectDevice: (ScannedDevice) -> Unit = { device ->
            connectingDevice = device
            ble.stopScan()
            ble.connect(device.device)
        }

        adapterPaired = DeviceAdapter(connectDevice)
        binding.recyclerPaired.layoutManager = LinearLayoutManager(this)
        binding.recyclerPaired.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
        binding.recyclerPaired.adapter = adapterPaired

        adapter = DeviceAdapter(connectDevice)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
        binding.recyclerView.adapter = adapter

        binding.btnScan.setOnClickListener {
            if (binding.tvBtnScanLabel.text == LABEL_STOP) {
                ble.stopScan()
                showScanIdle()
            } else {
                checkPermissionsAndScan()
            }
        }

        binding.btnViewTrack.setOnClickListener {
            startActivity(Intent(this, FileBrowserActivity::class.java))
        }
        binding.btnScoreTrack.setOnClickListener {
            startActivity(Intent(this, ScoreViewActivity::class.java))
        }
        binding.btnCompareTracks.setOnClickListener {
            startActivity(Intent(this, CompareTracksActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java))
        }
        binding.btnDisconnect.setOnClickListener {
            ble.disconnect()
        }

        lifecycleScope.launch {
            ble.scannedDevices.collectLatest { devices ->
                val paired   = devices.filter { it.isPaired }
                val scanning = devices.filter { !it.isPaired }
                adapterPaired.update(paired)
                adapter.update(scanning)
                binding.tvNoPaired.visibility = if (paired.isEmpty()) View.VISIBLE else View.GONE
                if (binding.panelScanActive.visibility == View.VISIBLE) {
                    val count = scanning.size
                    binding.tvScanCount.text = if (count > 0) "$count found" else ""
                    binding.tvEmpty.visibility =
                        if (scanning.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        lifecycleScope.launch {
            ble.batteryLevel.collectLatest { level ->
                if (level >= 0) {
                    binding.tvBatteryPct.text = "$level%"
                    binding.batteryProgress.progress = level
                }
            }
        }

        lifecycleScope.launch {
            ble.state.collectLatest { state ->
                when (state) {
                    BleState.Connecting   -> showConnecting(1)
                    BleState.Bonding      -> showConnecting(2)
                    BleState.Ready        -> showReady()
                    BleState.Disconnected -> showDisconnected()
                    is BleState.Error     -> {
                        showDisconnected()
                        Toast.makeText(this@MainActivity, state.msg, Toast.LENGTH_LONG).show()
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ble.refreshBondedDevices()
    }

    private fun showReady() {
        binding.tvDeviceName.text = connectingDevice?.name ?: "FlySight"
        binding.tvDeviceMac.text  = connectingDevice?.device?.address ?: ""

        binding.panelDeviceReady.visibility      = View.VISIBLE
        binding.panelDeviceConnecting.visibility = View.GONE
        binding.panelDeviceList.visibility       = View.GONE
        binding.sectionActions.visibility        = View.VISIBLE
        binding.btnDisconnect.visibility         = View.VISIBLE

        showScanIdle()
    }

    private fun showConnecting(step: Int) {
        binding.panelDeviceReady.visibility      = View.GONE
        binding.panelDeviceConnecting.visibility = View.VISIBLE
        binding.panelDeviceList.visibility       = View.GONE
        binding.sectionActions.visibility        = View.GONE
        binding.btnDisconnect.visibility         = View.GONE

        binding.tvConnectDevice.text = "Connecting to ${connectingDevice?.name ?: "device"}…"

        val doneColor    = getColor(R.color.colorPrimary)
        val activeColor  = getColor(R.color.colorDownload)
        val pendingColor = getColor(R.color.colorTextTertiary)

        fun stepColor(n: Int) = when {
            n < step  -> doneColor
            n == step -> activeColor
            else      -> pendingColor
        }
        binding.tvStep1.setTextColor(stepColor(1))
        binding.tvStep2.setTextColor(stepColor(2))
        binding.tvStep3.setTextColor(stepColor(3))
        binding.tvStep4.setTextColor(stepColor(4))
    }

    private fun showDisconnected() {
        ble.stopScan()
        binding.panelDeviceReady.visibility      = View.GONE
        binding.panelDeviceConnecting.visibility = View.GONE
        binding.panelDeviceList.visibility       = View.VISIBLE
        binding.sectionActions.visibility        = View.GONE
        binding.btnDisconnect.visibility         = View.GONE
        connectingDevice = null
        showScanIdle()
    }

    private fun showScanIdle() {
        binding.tvBtnScanLabel.text        = LABEL_SCAN
        binding.panelScanIdle.visibility   = View.VISIBLE
        binding.panelScanActive.visibility = View.GONE
    }

    private fun startScan() {
        ble.startScan()
        binding.tvBtnScanLabel.text        = LABEL_STOP
        binding.panelScanIdle.visibility   = View.GONE
        binding.panelScanActive.visibility = View.VISIBLE
        binding.tvScanCount.text           = ""
        binding.tvEmpty.visibility         = View.GONE
    }

    private fun checkPermissionsAndScan() {
        val btAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION)

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScan() else permissionLauncher.launch(missing.toTypedArray())
    }
}

class DeviceAdapter(
    private val onClick: (ScannedDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    private var items = listOf<ScannedDevice>()

    fun update(list: List<ScannedDevice>) { items = list; notifyDataSetChanged() }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName:    TextView = view.findViewById(R.id.tvName)
        val tvAddress: TextView = view.findViewById(R.id.tvAddress)
        val tvRssi:    TextView = view.findViewById(R.id.tvRssi)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvName.text    = item.name
        holder.tvAddress.text = item.device.address

        if (item.rssi != RSSI_UNKNOWN) {
            holder.tvRssi.text = "${item.rssi} dBm"
            holder.tvRssi.visibility = View.VISIBLE
        } else {
            holder.tvRssi.visibility = View.GONE
        }

        val ctx = holder.itemView.context
        holder.itemView.setBackgroundColor(
            if (item.isPairingMode && !item.isPaired)
                ctx.getColor(R.color.colorDevicePairingBg)
            else
                android.graphics.Color.TRANSPARENT
        )

        holder.itemView.setOnClickListener { onClick(item) }
    }
}
