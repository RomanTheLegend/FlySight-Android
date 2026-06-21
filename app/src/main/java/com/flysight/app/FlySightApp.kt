package com.flysight.app

import android.app.Application
import com.flysight.app.ble.BleManager
import com.flysight.app.ble.BleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FlySightApp : Application() {
    lateinit var bleManager: BleManager

    // Config cache — valid only while connected to the same device
    var cachedConfigText: String = ""
    var cachedConfigValues: Map<String, String>? = null

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager(this)
        appScope.launch {
            bleManager.state.collect { state ->
                if (state == BleState.Disconnected || state == BleState.Connecting) {
                    cachedConfigText   = ""
                    cachedConfigValues = null
                }
            }
        }
    }

    override fun onTerminate() {
        appScope.cancel()
        bleManager.close()
        super.onTerminate()
    }
}
