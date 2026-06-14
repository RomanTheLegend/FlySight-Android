package com.flysight.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flysight.app.ble.BleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class LoadState {
    object Idle : LoadState()
    data class Loading(val received: Long, val total: Long) : LoadState()
    data class Loaded(val points: List<DataPoint>) : LoadState()
    data class Failed(val msg: String) : LoadState()
}

class FileViewViewModel : ViewModel() {

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Idle)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    fun loadFromCache(points: List<DataPoint>) {
        _loadState.value = LoadState.Loaded(points)
    }

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

    private fun parseCsv(text: String): List<DataPoint> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val raw = if (lines.first().trimStart().startsWith("\$")) parseNew(lines) else parseOld(lines)
        return DataProcessor.process(raw)
    }

    // ── New-format parser ($GNSS rows) ────────────────────────────────────────

    private fun parseNew(lines: List<String>): List<DataPoint> {
        val result = mutableListOf<DataPoint>()
        for (line in lines) {
            val cols = line.split(",")
            if (cols.size < 12 || cols[0] != "\$GNSS") continue
            val ms   = parseIsoMs(cols[1])
            val lat  = cols[2].toDoubleOrNull() ?: continue
            val lon  = cols[3].toDoubleOrNull() ?: continue
            val hMSL = cols[4].toDoubleOrNull() ?: continue
            val velN = cols[5].toDoubleOrNull() ?: continue
            val velE = cols[6].toDoubleOrNull() ?: continue
            val velD = cols[7].toDoubleOrNull() ?: continue
            val hAcc = cols[8].toDoubleOrNull() ?: 0.0
            val vAcc = cols[9].toDoubleOrNull() ?: 0.0
            val sAcc = cols[10].toDoubleOrNull() ?: 0.0
            val numSV = cols[11].toIntOrNull() ?: 0
            result.add(DataPoint(
                dateTimeMs = ms, lat = lat, lon = lon, hMSL = hMSL,
                velN = velN, velE = velE, velD = velD,
                hAcc = hAcc, vAcc = vAcc, sAcc = sAcc, numSV = numSV
            ))
        }
        return result
    }

    // ── Old-format parser (header + units row + data rows) ───────────────────

    private fun parseOld(lines: List<String>): List<DataPoint> {
        if (lines.size < 3) return emptyList()
        val headers = lines[0].split(",").map { it.trim() }
        fun col(name: String) = headers.indexOf(name)

        val iTime = col("time"); val iLat  = col("lat");  val iLon  = col("lon")
        val iHMSL = col("hMSL"); val iVelN = col("velN"); val iVelE = col("velE")
        val iVelD = col("velD"); val iHAcc = col("hAcc"); val iVAcc = col("vAcc")
        val iSAcc = col("sAcc"); val iNumSV = col("numSV")

        if (listOf(iHMSL, iVelN, iVelE, iVelD).any { it < 0 }) return emptyList()

        // Skip header + optional units row (starts with ',' or '(')
        val hasUnitsRow = lines.size > 1 &&
            lines[1].trimStart().let { it.startsWith(",") || it.startsWith("(") }
        val result = mutableListOf<DataPoint>()
        for (line in lines.drop(if (hasUnitsRow) 2 else 1)) {
            val cols = line.split(",")
            val hMSL = cols.getOrNull(iHMSL)?.toDoubleOrNull() ?: continue
            val velN = cols.getOrNull(iVelN)?.toDoubleOrNull() ?: continue
            val velE = cols.getOrNull(iVelE)?.toDoubleOrNull() ?: continue
            val velD = cols.getOrNull(iVelD)?.toDoubleOrNull() ?: continue
            val ms   = if (iTime >= 0) parseIsoMs(cols.getOrElse(iTime) { "" }) else 0L
            result.add(DataPoint(
                dateTimeMs = ms,
                lat  = if (iLat  >= 0) cols.getOrNull(iLat )?.toDoubleOrNull() ?: 0.0 else 0.0,
                lon  = if (iLon  >= 0) cols.getOrNull(iLon )?.toDoubleOrNull() ?: 0.0 else 0.0,
                hMSL = hMSL, velN = velN, velE = velE, velD = velD,
                hAcc = if (iHAcc  >= 0) cols.getOrNull(iHAcc )?.toDoubleOrNull() ?: 0.0 else 0.0,
                vAcc = if (iVAcc  >= 0) cols.getOrNull(iVAcc )?.toDoubleOrNull() ?: 0.0 else 0.0,
                sAcc = if (iSAcc  >= 0) cols.getOrNull(iSAcc )?.toDoubleOrNull() ?: 0.0 else 0.0,
                numSV = if (iNumSV >= 0) cols.getOrNull(iNumSV)?.toIntOrNull()    ?: 0  else 0
            ))
        }
        return result
    }

    private fun parseIsoMs(s: String): Long = try {
        java.time.Instant.parse(s.trim()).toEpochMilli()
    } catch (e: Exception) {
        0L
    }
}
