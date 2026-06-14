package com.flysight.app.viewmodel

import com.flysight.app.ble.BleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScoreViewViewModel : TrackLoaderViewModel() {

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Idle)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    fun load(path: String, totalSize: Long, ble: BleManager) {
        if (_loadState.value !is LoadState.Idle) return
        loadInto(_loadState, path, totalSize, ble)
    }

    fun reset() {
        _loadState.value = LoadState.Idle
    }
}
