package com.flysight.app.util

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.flysight.app.ble.BleManager
import com.flysight.app.ble.BleState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

fun AppCompatActivity.finishOnBleDisconnect(ble: BleManager) {
    lifecycleScope.launch {
        ble.state.collectLatest { if (it == BleState.Disconnected) finish() }
    }
}

fun Long.formatFileSize(): String = when {
    this >= 1_048_576 -> "%.1f MB".format(this / 1_048_576.0)
    this >= 1_024     -> "%.1f KB".format(this / 1_024.0)
    else              -> "$this B"
}
