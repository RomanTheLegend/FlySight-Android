package com.flysight.app

import android.app.Application
import com.flysight.app.ble.BleManager

class FlySightApp : Application() {
    lateinit var bleManager: BleManager

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager(this)
    }

    override fun onTerminate() {
        bleManager.close()
        super.onTerminate()
    }
}
