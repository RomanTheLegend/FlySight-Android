package com.flysight.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flysight.app.ble.BleManager
import com.flysight.app.data.CsvParser
import com.flysight.app.data.DataPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class TrackLoaderViewModel : ViewModel() {

    protected fun loadInto(
        state: MutableStateFlow<LoadState>,
        path: String,
        totalSize: Long,
        ble: BleManager
    ) {
        state.value = LoadState.Loading(0, totalSize)
        viewModelScope.launch {
            try {
                val bytes = ble.readFile(path, totalSize) { r, t ->
                    state.value = LoadState.Loading(r, t)
                }
                state.value = LoadState.Parsing
                val points = withContext(Dispatchers.Default) {
                    CsvParser.parse(String(bytes, Charsets.UTF_8))
                }
                state.value = if (points.isEmpty()) LoadState.Failed("No data points found")
                              else LoadState.Loaded(points)
            } catch (e: Exception) {
                state.value = LoadState.Failed(e.message ?: "Unknown error")
            }
        }
    }
}
