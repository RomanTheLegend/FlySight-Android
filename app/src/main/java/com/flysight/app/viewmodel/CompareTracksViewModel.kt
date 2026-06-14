package com.flysight.app.viewmodel

import com.flysight.app.ble.BleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CompareTracksViewModel : TrackLoaderViewModel() {

    private val _track1 = MutableStateFlow<LoadState>(LoadState.Idle)
    val track1: StateFlow<LoadState> = _track1.asStateFlow()

    private val _track2 = MutableStateFlow<LoadState>(LoadState.Idle)
    val track2: StateFlow<LoadState> = _track2.asStateFlow()

    fun load(slot: Int, path: String, totalSize: Long, ble: BleManager) {
        val state = if (slot == 1) _track1 else _track2
        if (state.value is LoadState.Loading) return
        loadInto(state, path, totalSize, ble)
    }

    fun reset(slot: Int) {
        if (slot == 1) _track1.value = LoadState.Idle else _track2.value = LoadState.Idle
    }
}
