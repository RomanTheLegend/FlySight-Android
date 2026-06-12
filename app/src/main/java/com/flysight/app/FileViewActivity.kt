package com.flysight.app

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.flysight.app.databinding.ActivityFileViewBinding
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.github.mikephil.charting.utils.MPPointD
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class FileViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "file_name"
        const val EXTRA_PATH = "file_path"
        const val EXTRA_SIZE = "file_size"
    }

    private lateinit var binding: ActivityFileViewBinding
    private var trackPoints: List<TrackPoint> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val name      = intent.getStringExtra(EXTRA_NAME) ?: "Unknown"
        val path      = intent.getStringExtra(EXTRA_PATH) ?: ""
        val totalSize = intent.getLongExtra(EXTRA_SIZE, 0L)

        val parts = path.split("/").filter { it.isNotEmpty() }
        binding.tvTitle.text = when {
            parts.size >= 3 -> "${parts[parts.size - 3]} · ${parts[parts.size - 2]}"
            parts.size >= 2 -> parts[parts.size - 2]
            else            -> name
        }
        binding.tvSubtitle.text = "Jump recording"

        binding.btnHeaderBack.setOnClickListener { finish() }

        initProgressBar(totalSize)
        setStatus("Downloading $name…")

        lifecycleScope.launch {
            try {
                val bytes = (application as FlySightApp).bleManager.readFile(
                    path, totalSize
                ) { received, total ->
                    updateProgress(received, total)
                }
                val csv = String(bytes, Charsets.UTF_8)
                trackPoints = parseCsv(csv)
                if (trackPoints.isEmpty()) {
                    setStatus("No data points found")
                } else {
                    showChart()
                }
            } catch (e: Exception) {
                setStatus("Error: ${e.message}")
                Toast.makeText(this@FileViewActivity, "Read failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }


    // ── Data model ─────────────────────────────────────────────────────────

    private data class TrackPoint(
        val timeSec: Double,
        val hMSL: Double,
        val velN: Double,
        val velE: Double,
        val velD: Double
    ) {
        val hSpeed     get() = sqrt(velN * velN + velE * velE) * 3.6
        val vSpeed     get() = velD * 3.6
        val totalSpeed get() = sqrt(velN * velN + velE * velE + velD * velD) * 3.6
    }

    // ── CSV parsing ────────────────────────────────────────────────────────

    private fun parseCsv(text: String): List<TrackPoint> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        return if (lines.first().trimStart().startsWith("\$")) parseNew(lines) else parseOld(lines)
    }

    private fun parseNew(lines: List<String>): List<TrackPoint> {
        // $GNSS,<ISO_datetime>,lat,lon,hMSL,velN,velE,velD,hAcc,vAcc,sAcc,numSV
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
        // header row, units row, data rows
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

    // ── Chart ──────────────────────────────────────────────────────────────

    private fun showChart() {
        val elevE  = ArrayList<Entry>(trackPoints.size)
        val hSpdE  = ArrayList<Entry>(trackPoints.size)
        val vSpdE  = ArrayList<Entry>(trackPoints.size)
        val totE   = ArrayList<Entry>(trackPoints.size)

        for (p in trackPoints) {
            val t = p.timeSec.toFloat()
            elevE.add(Entry(t, p.hMSL.toFloat()))
            hSpdE.add(Entry(t, p.hSpeed.toFloat()))
            vSpdE.add(Entry(t, p.vSpeed.toFloat()))
            totE.add(Entry(t,  p.totalSpeed.toFloat()))
        }

        val colorElev   = Color.parseColor("#FF9800")
        val colorHSpeed = Color.parseColor("#2196F3")
        val colorVSpeed = Color.parseColor("#F44336")
        val colorTotal  = Color.parseColor("#4CAF50")

        fun set(entries: ArrayList<Entry>, label: String, color: Int, axis: YAxis.AxisDependency) =
            LineDataSet(entries, label).apply {
                this.color = color
                setDrawCircles(false)
                setDrawValues(false)
                lineWidth = 1.5f
                axisDependency = axis
                mode = LineDataSet.Mode.LINEAR
            }

        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val axisTextColor = if (isDark) Color.parseColor("#AAAAAA") else Color.DKGRAY
        val gridColor     = if (isDark) Color.parseColor("#33FFFFFF") else Color.parseColor("#22000000")

        val chart = binding.lineChart
        chart.setBackgroundColor(Color.TRANSPARENT)
        chart.data = LineData(
            set(hSpdE, "H. Speed (km/h)",   colorHSpeed, YAxis.AxisDependency.LEFT),
            set(vSpdE, "V. Speed (km/h)",   colorVSpeed, YAxis.AxisDependency.LEFT),
            set(totE,  "Total Speed (km/h)", colorTotal,  YAxis.AxisDependency.LEFT),
            set(elevE, "Elevation (m)",      colorElev,   YAxis.AxisDependency.RIGHT)
        )

        // X-axis: seconds, at bottom
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            granularity = 10f
            textColor = axisTextColor
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = "${value.toInt()}s"
            }
        }

        // Left axis: km/h (speeds)
        chart.axisLeft.apply {
            setDrawGridLines(true)
            textColor = axisTextColor
            axisLineColor = axisTextColor
            this.gridColor = gridColor
        }

        // Right axis: elevation in meters, orange to match series
        chart.axisRight.apply {
            setDrawGridLines(false)
            textColor = colorElev
            axisLineColor = colorElev
        }

        // Horizontal legend above the chart
        chart.legend.apply {
            isEnabled = true
            verticalAlignment = Legend.LegendVerticalAlignment.TOP
            horizontalAlignment = Legend.LegendHorizontalAlignment.LEFT
            orientation = Legend.LegendOrientation.HORIZONTAL
            setDrawInside(false)
            yOffset = 8f
            textSize = 11f
            textColor = axisTextColor
        }

        chart.description.isEnabled = false

        // Horizontal scroll + pinch-zoom on X axis only (like HorizontalScrollView)
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.isScaleXEnabled = true
        chart.isScaleYEnabled = false
        chart.setPinchZoom(false)          // false = scale X only, not both axes together
        chart.isDoubleTapToZoomEnabled = true

        // Show full track on first load; user zooms in from there
        // After the view is laid out, enforce max zoom = 1 second per 10 pixels
        chart.post {
            val minVisibleSeconds = (chart.width / 10f).coerceAtLeast(1f)
            chart.setVisibleXRangeMinimum(minVisibleSeconds)
        }
        chart.moveViewToX(0f)

        // Update values panel instantly on every touch/drag event
        chart.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN ||
                event.action == MotionEvent.ACTION_MOVE) {
                val pt: MPPointD = chart.getValuesByTouchPoint(
                    event.x, event.y, YAxis.AxisDependency.LEFT)
                updateValuesAt(pt.x.toFloat())
                MPPointD.recycleInstance(pt)
            }
            false // let the chart handle scrolling/zooming normally
        }

        // Also update on pinch-zoom so values reflect new viewport
        chart.onChartGestureListener = object : OnChartGestureListener {
            override fun onChartGestureStart(me: MotionEvent, last: ChartTouchListener.ChartGesture) {}
            override fun onChartGestureEnd(me: MotionEvent, last: ChartTouchListener.ChartGesture) {}
            override fun onChartLongPressed(me: MotionEvent) {}
            override fun onChartDoubleTapped(me: MotionEvent) {}
            override fun onChartFling(me1: MotionEvent, me2: MotionEvent, vx: Float, vy: Float) {}
            override fun onChartSingleTapped(me: MotionEvent) {}
            override fun onChartScale(me: MotionEvent, scaleX: Float, scaleY: Float) {
                updateValuesAt(chart.lowestVisibleX)
            }
            override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) {}
        }

        chart.animateX(400)

        binding.loadingPanel.visibility = View.GONE
        binding.chartCard.visibility    = View.VISIBLE
        binding.valuesPanel.visibility  = View.VISIBLE

        updateValuesAt(0f)
    }

    private fun updateValuesAt(timeSec: Float) {
        if (trackPoints.isEmpty()) return
        val idx = trackPoints.indexOfFirst { it.timeSec >= timeSec }
            .let { if (it < 0) trackPoints.size - 1 else it }
            .coerceIn(0, trackPoints.size - 1)
        val p = trackPoints[idx]
        binding.tvElevValue.text   = "%.0f".format(p.hMSL)
        binding.tvHSpeedValue.text = "%.1f".format(p.hSpeed)
        binding.tvVSpeedValue.text = "%.1f".format(p.vSpeed)
        binding.tvTotalValue.text  = "%.1f".format(p.totalSpeed)
    }

    private fun initProgressBar(totalSize: Long) {
        if (totalSize > 0) {
            binding.progressBar.isIndeterminate = false
            binding.progressBar.max = totalSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            binding.progressBar.progress = 0
            binding.tvProgressBytes.visibility = View.VISIBLE
        } else {
            binding.progressBar.isIndeterminate = true
            binding.tvProgressBytes.visibility = View.GONE
        }
    }

    private fun updateProgress(received: Long, total: Long) {
        if (total > 0) {
            binding.progressBar.progress = received.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            binding.tvProgressBytes.text = "${received / 1024} KB / ${total / 1024} KB"
        } else {
            binding.tvProgressBytes.text = "${received / 1024} KB"
        }
    }

    private fun setStatus(msg: String) {
        binding.tvStatus.text           = msg
        binding.loadingPanel.visibility = View.VISIBLE
        binding.chartCard.visibility    = View.GONE
        binding.valuesPanel.visibility  = View.GONE
    }
}
