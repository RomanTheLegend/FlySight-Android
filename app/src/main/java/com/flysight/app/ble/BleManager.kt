package com.flysight.app.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

sealed class BleState {
    object Idle        : BleState()
    object Connecting  : BleState()
    object Bonding     : BleState()
    object Ready       : BleState()
    object Disconnected: BleState()
    data class Error(val msg: String) : BleState()
}

data class DirEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long       // bytes; 0 for directories
)

data class ScannedDevice(
    val device: BluetoothDevice,
    val name: String,
    val rssi: Int,
    val isPairingMode: Boolean,
    val isPaired: Boolean = false
)

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "FlySightBle"
        var bleLogging = false   // set to true to enable verbose BLE packet logs
        private const val PING_INTERVAL_MS   = 15_000L
        private const val OP_TIMEOUT_MS      = 5_000L
        private const val XFER_TIMEOUT_MS    = 180_000L
        private const val LIST_TIMEOUT_MS    = 120_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }

    private val btAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var ftPacketIn: BluetoothGattCharacteristic? = null
    private var negotiatedMtu = 23

    private val _state   = MutableStateFlow<BleState>(BleState.Idle)
    val state: StateFlow<BleState> = _state

    private val _scanned = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scanned

    // Recreated on each connect to avoid stale values
    private var connectedCh  = Channel<Boolean>(1)
    private var mtuCh        = Channel<Int>(1)
    private var servicesCh   = Channel<Boolean>(1)
    private var descriptorCh = Channel<Boolean>(1)
    private var notifyCh     = Channel<ByteArray>(Channel.UNLIMITED)
    private var readCharCh   = Channel<Pair<UUID, ByteArray>>(Channel.CONFLATED)

    private val _batteryLevel = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pingJob: Job? = null
    private var leScanner: BluetoothLeScanner? = null

    // MAC addresses of bonded FlySight devices, refreshed on demand
    private var bondedAddresses: Set<String> = emptySet()

    init { refreshBondedDevices() }

    // ── GATT callback ────────────────────────────────────────────────────────

    private val gattCb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            //if (bleLogging) Log.d(TAG, "ConnectionState status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED    -> connectedCh.trySend(true)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedCh.trySend(false)
                    _state.value = BleState.Disconnected
                    _batteryLevel.value = -1
                    pingJob?.cancel()
                    g.close()
                    gatt = null
                    ftPacketIn = null
                    // Unblock any coroutine waiting on notifyCh.receive() so it fails fast
                    // instead of waiting out the full transfer timeout.
                    notifyCh.close()
                    notifyCh = Channel(Channel.UNLIMITED)
                    readCharCh.close()
                    readCharCh = Channel(Channel.CONFLATED)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            if (bleLogging) Log.d(TAG, "MTU=$negotiatedMtu")
            mtuCh.trySend(negotiatedMtu)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesCh.trySend(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                routePacket(c.uuid, c.value ?: return)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            routePacket(c.uuid, value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && status == BluetoothGatt.GATT_SUCCESS)
                readCharCh.trySend(Pair(c.uuid, c.value ?: return))
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS)
                readCharCh.trySend(Pair(c.uuid, value))
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            d: BluetoothGattDescriptor,
            status: Int
        ) {
            if (d.uuid == FlySightUuids.CCCD)
                descriptorCh.trySend(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun routePacket(uuid: UUID, value: ByteArray) {
        when {
            uuid == FlySightUuids.FT_PACKET_OUT && value.isNotEmpty() -> {
                if (bleLogging) Log.d(TAG, "RX ${value.size}b: [${value.joinToString(" ") { "%02x".format(it) }}]")
                notifyCh.trySend(value.copyOf())
            }
            uuid == FlySightUuids.BATTERY_LEVEL && value.isNotEmpty() -> {
                _batteryLevel.value = value[0].toInt() and 0xFF
            }
        }
    }

    // ── Scanning ─────────────────────────────────────────────────────────────

    fun startScan() {
        val scanner = btAdapter?.bluetoothLeScanner ?: return
        // Seed list with already-bonded devices so they stay visible during scan
        _scanned.value = bondedDeviceEntries()
        leScanner = scanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCb)
    }

    fun stopScan() {
        leScanner?.stopScan(scanCb)
        leScanner = null
    }

    /** Call from Activity.onResume() to pick up newly bonded devices. */
    fun refreshBondedDevices() {
        val bonded = btAdapter?.bondedDevices
            ?.filter { it.name?.startsWith("FlySight") == true }
            ?: emptySet()
        bondedAddresses = bonded.map { it.address }.toSet()

        // Add any bonded device not already in the list
        val current = _scanned.value.toMutableList()
        for (dev in bonded) {
            if (current.none { it.device.address == dev.address }) {
                current.add(0, ScannedDevice(dev, dev.name ?: dev.address,
                    RSSI_UNKNOWN, false, isPaired = true))
            }
        }
        _scanned.value = current
    }

    private fun bondedDeviceEntries(): List<ScannedDevice> =
        btAdapter?.bondedDevices
            ?.filter { it.address in bondedAddresses }
            ?.map { ScannedDevice(it, it.name ?: it.address, RSSI_UNKNOWN, false, isPaired = true) }
            ?: emptyList()

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            val manuf     = result.scanRecord?.getManufacturerSpecificData(MANUF_ID_FLYSIGHT)
            val isPaired  = result.device.address in bondedAddresses
            // Accept packet if it has FlySight manufacturer data OR if the device is already bonded
            if (manuf == null && !isPaired) return
            val isPairing = manuf != null && manuf.isNotEmpty() && manuf[0] == 0x01.toByte()
            val name  = result.scanRecord?.deviceName
                ?: result.device.name
                ?: result.device.address
            val entry = ScannedDevice(result.device, name, result.rssi, isPairing, isPaired)
            val list  = _scanned.value.toMutableList()
            val idx   = list.indexOfFirst { it.device.address == result.device.address }
            if (idx >= 0) list[idx] = entry else list.add(entry)
            _scanned.value = list
        }
    }

    // ── Connection ────────────────────────────────────────────────────────────

    fun connect(device: BluetoothDevice) {
        _state.value = BleState.Connecting
        connectedCh  = Channel(1)
        mtuCh        = Channel(1)
        servicesCh   = Channel(1)
        descriptorCh = Channel(1)
        notifyCh     = Channel(Channel.UNLIMITED)
        readCharCh   = Channel(Channel.CONFLATED)
        drain()
        gatt = device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        scope.launch { doConnect() }
    }

    private suspend fun doConnect() {
        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connectedCh.receive() }
        if (connected != true) {
            _state.value = BleState.Error("Connection timed out")
            return
        }

        val g = gatt ?: return
        g.requestMtu(247)
        withTimeoutOrNull(5_000) { mtuCh.receive() }

        _state.value = BleState.Bonding

        g.discoverServices()
        if (withTimeoutOrNull(10_000) { servicesCh.receive() } != true) {
            _state.value = BleState.Error("Service discovery failed")
            return
        }

        val ftSvc = g.getService(FlySightUuids.FILE_TRANSFER_SERVICE)
        if (ftSvc == null) {
            _state.value = BleState.Error(
                "File Transfer service not found.\nEnsure device is in Idle (LED off) mode.")
            return
        }

        ftPacketIn = ftSvc.getCharacteristic(FlySightUuids.FT_PACKET_IN)
        val ftOut  = ftSvc.getCharacteristic(FlySightUuids.FT_PACKET_OUT)
        if (ftPacketIn == null || ftOut == null) {
            _state.value = BleState.Error("Required characteristics missing")
            return
        }

        // Reading an encrypted characteristic triggers OS bonding dialog if not yet paired
        g.readCharacteristic(ftPacketIn)
        delay(4_000)

        // Enable notifications on FT_Packet_Out
        g.setCharacteristicNotification(ftOut, true)
        val cccd = ftOut.getDescriptor(FlySightUuids.CCCD)
        if (cccd == null) {
            _state.value = BleState.Error("CCCD descriptor not found")
            return
        }

        val notifyEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            withTimeoutOrNull(5_000) { descriptorCh.receive() } == true
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
            withTimeoutOrNull(5_000) { descriptorCh.receive() } == true
        }

        if (!notifyEnabled) {
            _state.value = BleState.Error(
                "Could not enable notifications.\nPair the device first (double-press button).")
            return
        }

        // Enable notifications on battery level if the device supports it.
        // Without CCCD enabled the device returns its stale/default value (0) on plain reads.
        val batChar = g.getService(FlySightUuids.BATTERY_SERVICE)
            ?.getCharacteristic(FlySightUuids.BATTERY_LEVEL)
        if (batChar != null) {
            g.setCharacteristicNotification(batChar, true)
            val batCccd = batChar.getDescriptor(FlySightUuids.CCCD)
            if (batCccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(batCccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    batCccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(batCccd)
                }
                withTimeoutOrNull(5_000) { descriptorCh.receive() }
            }
        }

        _state.value = BleState.Ready
        startPing()
    }

    // ── Keep-alive ────────────────────────────────────────────────────────────

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                if (_state.value == BleState.Ready)
                    runCatching { rawWrite(byteArrayOf(FtOpcode.PING)) }
            }
        }
    }

    // ── File operations ───────────────────────────────────────────────────────

    suspend fun readFile(
        path: String,
        totalBytes: Long = 0L,
        onProgress: ((bytesReceived: Long, totalBytes: Long) -> Unit)? = null
    ): ByteArray {
        pingJob?.cancel()
        return try {
            doRead(path, totalBytes, onProgress)
        } finally {
            if (gatt != null) startPing()
        }
    }

    private suspend fun doRead(
        path: String,
        totalBytes: Long,
        onProgress: ((bytesReceived: Long, totalBytes: Long) -> Unit)?
    ): ByteArray {
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        // Command: 0x02 + offset_mult(u32 LE) + stride-1_mult(u32 LE) + path + null
        val cmd = ByteBuffer.allocate(1 + 4 + 4 + pathBytes.size + 1)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(FtOpcode.READ_FILE)
            .putInt(0)   // offset_multiplier = 0
            .putInt(0)   // stride_minus1_multiplier = 0
            .put(pathBytes)
            .put(0)
            .array()

        drain()
        rawWrite(cmd)
        waitAck(FtOpcode.READ_FILE) ?: error("Read NAK/timeout for $path")

        val buf = ByteArrayOutputStream()
        var expected = 0
        var received = 0L

        try {
            while (true) {
                val pkt = withTimeout(XFER_TIMEOUT_MS) { awaitDataPacket() }
                val counter = pkt[1].toInt() and 0xFF
                val data = if (pkt.size > 2) pkt.copyOfRange(2, pkt.size) else ByteArray(0)

                val behind = (expected - counter) and 0xFF
                when {
                    behind == 0 -> {
                        // In-order: new packet
                        rawWrite(byteArrayOf(FtOpcode.ACK_DATA, counter.toByte()), delayMs = 10)
                        if (data.isEmpty()) break  // EOF marker
                        buf.write(data)
                        received += data.size
                        onProgress?.invoke(received, totalBytes)
                        expected = (expected + 1) and 0xFF
                    }
                    behind in 1..8 -> {
                        // GBN retransmission — re-ACK so firmware can advance next_ack
                        rawWrite(byteArrayOf(FtOpcode.ACK_DATA, counter.toByte()), delayMs = 0)
                    }
                    // else: out-of-order future packet — ignore
                }
            }
        } catch (e: Exception) {
            runCatching { rawWrite(byteArrayOf(FtOpcode.CANCEL)) }
            throw e
        }

        return buf.toByteArray()
    }

    suspend fun writeFile(
        path: String,
        data: ByteArray,
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ) {
        pingJob?.cancel()
        try {
            doWrite(path, data, onProgress)
        } finally {
            if (gatt != null) startPing()
        }
    }

    private suspend fun doWrite(
        path: String,
        data: ByteArray,
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ) {
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        val openCmd = ByteArray(1 + pathBytes.size + 1)
        openCmd[0] = FtOpcode.WRITE_FILE
        pathBytes.copyInto(openCmd, 1)

        drain()
        rawWrite(openCmd)
        when (waitAck(FtOpcode.WRITE_FILE)) {
            true  -> Unit
            false -> error("Write rejected (NAK) for $path — ensure device is in Idle mode (LED off)")
            null  -> error("Write timed out for $path")
        }

        // GBN ARQ — matches iOS BluetoothManager: frameLength=242, window=8, timeout=200ms
        val frameLen   = 242
        val windowLen  = 8
        val retryMs    = 200L
        val dataChunks = if (data.isEmpty()) 0 else (data.size + frameLen - 1) / frameLen
        val totalPkts  = dataChunks + 1   // data packets + 1 EOF

        var nextSend = 0
        var nextAck  = 0

        fun buildPkt(idx: Int): ByteArray {
            val chunk = if (idx < dataChunks)
                data.copyOfRange(idx * frameLen, minOf((idx + 1) * frameLen, data.size))
            else ByteArray(0)
            val pkt = ByteArray(2 + chunk.size)
            pkt[0] = FtOpcode.FILE_DATA
            pkt[1] = (idx and 0xFF).toByte()
            chunk.copyInto(pkt, 2)
            return pkt
        }

        try {
            while (nextAck < totalPkts) {
                // Fill send window; hold EOF until all data packets are ACKed
                while (nextSend < nextAck + windowLen && nextSend < totalPkts) {
                    if (nextSend == dataChunks && nextAck < dataChunks) break
                    if (!rawWrite(buildPkt(nextSend), delayMs = 0)) break
                    nextSend++
                }

                // Wait for the next expected ACK; on 200 ms timeout GBN retransmits
                val acked = withTimeoutOrNull(retryMs) {
                    while (true) {
                        val p = notifyCh.receive()
                        if (p.isNotEmpty() && p[0] == FtOpcode.NAK) error("NAK during file write")
                        if (p.size >= 2 && p[0] == FtOpcode.ACK_DATA
                            && (p[1].toInt() and 0xFF) == (nextAck and 0xFF))
                            return@withTimeoutOrNull true
                    }
                    @Suppress("UNREACHABLE_CODE") false
                } ?: false

                if (acked) {
                    nextAck++
                    onProgress?.invoke(
                        minOf(nextAck.toLong() * frameLen, data.size.toLong()),
                        data.size.toLong()
                    )
                } else {
                    nextSend = nextAck   // GBN: retransmit window from oldest un-ACKed packet
                }
            }
        } catch (e: Exception) {
            runCatching { rawWrite(byteArrayOf(FtOpcode.CANCEL)) }
            throw e
        }
    }

    suspend fun deleteRecursive(path: String) {
        pingJob?.cancel()
        try {
            doDeleteRecursive(path)
        } finally {
            if (gatt != null) startPing()
        }
    }

    private suspend fun doDeleteRecursive(path: String) {
        if (bleLogging) Log.d(TAG, "DELETE_RECURSIVE: listing '$path'")
        val entries = doListDir(path)
        for (entry in entries) {
            val entryPath = if (path.isEmpty()) entry.name else "$path/${entry.name}"
            if (entry.isDirectory) {
                doDeleteRecursive(entryPath)
            } else {
                doDelete(entryPath)
            }
        }
        doDelete(path)
    }

    private suspend fun doDelete(path: String) {
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        val cmd = ByteArray(1 + pathBytes.size + 1)
        cmd[0] = FtOpcode.DELETE_FILE
        pathBytes.copyInto(cmd, 1)

        if (bleLogging) Log.d(TAG, "DELETE TX: path='$path' packet=[${cmd.joinToString(" ") { "%02x".format(it) }}]")
        drain()
        rawWrite(cmd)
        if (bleLogging) Log.d(TAG, "DELETE: waiting for ACK/NAK…")

        when (waitAck(FtOpcode.DELETE_FILE)) {
            true  -> if (bleLogging) Log.d(TAG, "DELETE: ACK — success for '$path'")
            false -> {
                if (bleLogging) Log.d(TAG, "DELETE: NAK — device rejected for '$path'")
                error("Delete rejected by device (NAK) for '$path'")
            }
            null  -> {
                if (bleLogging) Log.d(TAG, "DELETE: timeout for '$path'")
                error("Delete timeout for '$path'")
            }
        }
    }

    suspend fun listDir(path: String): List<DirEntry> {
        pingJob?.cancel()
        return try {
            doListDir(path)
        } finally {
            if (gatt != null) startPing()
        }
    }

    private suspend fun doListDir(path: String): List<DirEntry> {
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        val cmd = ByteArray(1 + pathBytes.size + 1)
        cmd[0] = FtOpcode.LIST_DIR
        pathBytes.copyInto(cmd, 1)

        drain()
        rawWrite(cmd)
        waitAck(FtOpcode.LIST_DIR) ?: error("List dir NAK/timeout for '$path'")

        // Receive 0x11 FILE_INFO notifications — no ACK required from the phone.
        // Format per packet: [0x11][counter][size:4LE][date:2LE][time:2LE][attr:1][name:null-terminated]
        // End of list: name[0] == 0
        val entries = mutableListOf<DirEntry>()
        try {
            withTimeout(LIST_TIMEOUT_MS) {
                while (true) {
                    val pkt = awaitFileInfoPacket()          // waits for next 0x11 notification
                    if (pkt.size < 24) continue              // malformed, skip
                    if (pkt[11] == 0.toByte()) break         // name[0] == 0 → end of list

                    val size = ByteBuffer.wrap(pkt, 2, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    val attr = pkt[10].toInt() and 0xFF
                    val nameLen = (11 until pkt.size).indexOfFirst { pkt[it] == 0.toByte() }
                        .let { if (it < 0) pkt.size - 11 else it }
                    val name = String(pkt, 11, nameLen, Charsets.UTF_8)
                    val isDir = (attr and 0x10) != 0
                    entries.add(DirEntry(name, isDir, if (isDir) 0L else size))
                }
            }
        } catch (e: Exception) {
            runCatching { rawWrite(byteArrayOf(FtOpcode.CANCEL)) }
            throw e
        }

        return entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.uppercase() }))
    }

    // ── Battery ───────────────────────────────────────────────────────────────
    // Battery level is driven by CCCD notifications subscribed in doConnect().
    // A direct readCharacteristic on the battery level returns 0x00 (firmware bug —
    // the read handler does not return the current measured value). Do not use reads.

    // ── Protocol helpers ──────────────────────────────────────────────────────

    private suspend fun waitAck(cmd: Byte): Boolean? = withTimeoutOrNull(OP_TIMEOUT_MS) {
        while (true) {
            val p = notifyCh.receive()
            if (p.size >= 2) {
                if (p[0] == FtOpcode.ACK && p[1] == cmd) return@withTimeoutOrNull true
                if (p[0] == FtOpcode.NAK && p[1] == cmd) return@withTimeoutOrNull false
            }
        }
        @Suppress("UNREACHABLE_CODE") false
    }

    private suspend fun awaitDataPacket(): ByteArray {
        while (true) {
            val p = notifyCh.receive()
            if (p[0] == FtOpcode.FILE_DATA) return p
        }
    }

    private suspend fun awaitFileInfoPacket(): ByteArray {
        while (true) {
            val p = notifyCh.receive()
            if (p.isNotEmpty() && p[0] == FtOpcode.FILE_INFO) return p
        }
    }

    private fun drain() {
        while (notifyCh.tryReceive().isSuccess) { /* discard stale packets */ }
    }

    private suspend fun rawWrite(data: ByteArray, delayMs: Long = 20L): Boolean {
        if (delayMs > 0) delay(delayMs)
        val char = ftPacketIn ?: error("Not connected")
        val g    = gatt        ?: error("Not connected")
        if (bleLogging) Log.d(TAG, "TX ${data.size}b: [${data.joinToString(" ") { "%02x".format(it) }}]")
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
        if (!ok && bleLogging) Log.w(TAG, "TX FAILED: [${data.joinToString(" ") { "%02x".format(it) }}]")
        return ok
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun disconnect() {
        pingJob?.cancel()
        gatt?.disconnect()
    }

    fun close() {
        stopScan()
        disconnect()
        scope.cancel()
    }
}
