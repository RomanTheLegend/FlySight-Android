package com.flysight.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import com.flysight.app.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ble: BleManager
    private lateinit var adapter: DeviceAdapter
    private lateinit var adapterPaired: DeviceAdapter

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

        supportActionBar?.title = "FlySight 2 Manager"

        ble = (application as FlySightApp).bleManager

        val connectDevice: (ScannedDevice) -> Unit = { device ->
            ble.stopScan()
            binding.btnScan.text = "Scan"
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
            if (binding.btnScan.text == "Stop") {
                ble.stopScan()
                binding.btnScan.text = "Scan"
            } else {
                checkPermissionsAndScan()
            }
        }

        lifecycleScope.launch {
            ble.scannedDevices.collectLatest { devices ->
                val paired   = devices.filter { it.isPaired }
                val scanning = devices.filter { !it.isPaired }
                adapterPaired.update(paired)
                adapter.update(scanning)
                binding.tvNoPaired.visibility = if (paired.isEmpty()) View.VISIBLE else View.GONE
                binding.tvEmpty.visibility =
                    if (scanning.isEmpty() && binding.btnScan.text == "Stop") View.VISIBLE
                    else View.GONE
            }
        }

        lifecycleScope.launch {
            ble.state.collectLatest { state ->
                when (state) {
                    BleState.Connecting  -> showProgress(true)
                    BleState.Bonding     -> showProgress(true)
                    BleState.Ready       -> {
                        showProgress(false)
                        startActivity(Intent(this@MainActivity, FileBrowserActivity::class.java))
                    }
                    BleState.Disconnected -> showProgress(false)
                    is BleState.Error    -> {
                        showProgress(false)
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

    private fun startScan() {
        ble.startScan()
        binding.btnScan.text = "Stop"
        binding.tvEmpty.visibility = View.GONE
    }

    private fun showProgress(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
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
        val tvPairing: TextView = view.findViewById(R.id.tvPairing)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvName.text    = item.name
        holder.tvAddress.text = item.device.address
        holder.tvRssi.text    = if (item.rssi != RSSI_UNKNOWN) "${item.rssi} dBm" else ""
        when {
            item.isPairingMode -> {
                holder.tvPairing.text = "● PAIRING"
                holder.tvPairing.setTextColor(Color.parseColor("#00AA44"))
                holder.tvPairing.visibility = View.VISIBLE
            }
            item.isPaired -> {
                holder.tvPairing.text = "● PAIRED"
                holder.tvPairing.setTextColor(Color.parseColor("#4CAF50"))
                holder.tvPairing.visibility = View.VISIBLE
            }
            else -> holder.tvPairing.visibility = View.GONE
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }
}
