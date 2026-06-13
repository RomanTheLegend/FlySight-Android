package com.flysight.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flysight.app.ble.BleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

data class TrackPoint(
    val timeSec: Double,
    val hMSL: Double,
    val velN: Double,
    val velE: Double,
    val velD: Double
) {
    val hSpeed     get() = sqrt(velN * velN + velE * velE) * 3.6
    val vSpeed     get() = velD * 3.6
    val totalSpeed get() = sqrt(velN * velN + velE * velE + velD * velD) * 3.6
    val glideRatio get() = if (vSpeed != 0.0) (hSpeed / vSpeed).coerceIn(-50.0, 50.0) else 0.0
}

sealed class LoadState {
    object Idle : LoadState()
    data class Loading(val received: Long, val total: Long) : LoadState()
    data class Loaded(val points: List<TrackPoint>) : LoadState()
    data class Failed(val msg: String) : LoadState()
}

class FileViewViewModel : ViewModel() {

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Idle)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    fun load(path: String, totalSize: Long, ble: BleManager) {
        if (_loadState.value !is LoadState.Idle) return
        _loadState.value = LoadState.Loading(0, totalSize)
        viewModelScope.launch {
            try {
                val bytes = ble.readFile(path, totalSize) { received, total ->
                    _loadState.value = LoadState.Loading(received, total)
                }
                val points = parseCsv(String(bytes, Charsets.UTF_8))
                _loadState.value = if (points.isEmpty()) LoadState.Failed("No data points found")
                                   else LoadState.Loaded(points)
            } catch (e: Exception) {
                _loadState.value = LoadState.Failed(e.message ?: "Unknown error")
            }
        }
    }

    private fun parseCsv(text: String): List<TrackPoint> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        return if (lines.first().trimStart().startsWith("\$")) parseNew(lines) else parseOld(lines)
    }

    private fun parseNew(lines: List<String>): List<TrackPoint> {
        val result = mutableListOf<TrackPoint>()
        var firstMs = Long.MIN_VALUE
        for (line in lines) {
            val cols = line.split(",")
            if (cols.size < 12 || cols[0] != "\$GNSS") continue
            val hMSL = cols[4].toDoubleOrNull() ?: continue
            val velN = cols[5].toDoubleOrNull() ?: continue
            val velE = cols[6].toDoubleOrNull() ?: continue
            val velD = cols[7].toDoubleOrNull() ?: continue
            val ms = parseIsoMs(cols[1])
            if (firstMs == Long.MIN_VALUE && ms >= 0) firstMs = ms
            val t = if (ms >= 0 && firstMs >= 0) (ms - firstMs) / 1000.0 else result.size * 0.2
            result.add(TrackPoint(t, hMSL, velN, velE, velD))
        }
        return result
    }

    private fun parseOld(lines: List<String>): List<TrackPoint> {
        if (lines.size < 3) return emptyList()
        val headers = lines[0].split(",").map { it.trim() }
        fun col(name: String) = headers.indexOf(name)
        val iTime = col("time")
        val iHMSL = col("hMSL"); val iVelN = col("velN")
        val iVelE = col("velE"); val iVelD = col("velD")
        if (listOf(iHMSL, iVelN, iVelE, iVelD).any { it < 0 }) return emptyList()
        val result = mutableListOf<TrackPoint>()
        var firstMs = Long.MIN_VALUE
        for (line in lines.drop(2)) {
            val cols = line.split(",")
            if (cols.size <= maxOf(iHMSL, iVelN, iVelE, iVelD)) continue
            val hMSL = cols[iHMSL].toDoubleOrNull() ?: continue
            val velN = cols[iVelN].toDoubleOrNull() ?: continue
            val velE = cols[iVelE].toDoubleOrNull() ?: continue
            val velD = cols[iVelD].toDoubleOrNull() ?: continue
            val ms = if (iTime >= 0 && iTime < cols.size) parseIsoMs(cols[iTime]) else -1L
            if (firstMs == Long.MIN_VALUE && ms >= 0) firstMs = ms
            val t = if (ms >= 0 && firstMs >= 0) (ms - firstMs) / 1000.0 else result.size * 0.2
            result.add(TrackPoint(t, hMSL, velN, velE, velD))
        }
        return result
    }

    private fun parseIsoMs(s: String): Long = try {
        java.time.Instant.parse(s.trim()).toEpochMilli()
    } catch (e: Exception) {
        -1L
    }
}
