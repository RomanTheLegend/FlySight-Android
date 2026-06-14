package com.flysight.app.viewmodel

import com.flysight.app.data.DataPoint

sealed class LoadState {
    object Idle : LoadState()
    data class Loading(val received: Long, val total: Long) : LoadState()
    object Parsing : LoadState()
    data class Loaded(val points: List<DataPoint>) : LoadState()
    data class Failed(val msg: String) : LoadState()
}
