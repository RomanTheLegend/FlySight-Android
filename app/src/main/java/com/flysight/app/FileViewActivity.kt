package com.flysight.app

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.flysight.app.databinding.ActivityFileViewBinding
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FileViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME       = "file_name"
        const val EXTRA_PATH       = "file_path"
        const val EXTRA_SIZE       = "file_size"
        const val EXTRA_FROM_CACHE = "from_cache"
    }

    private lateinit var binding: ActivityFileViewBinding
    private val viewModel: FileViewViewModel by viewModels()

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
        binding.btnDisconnect.setOnClickListener {
            (application as FlySightApp).bleManager.disconnect()
            finish()
        }

        lifecycleScope.launch {
            viewModel.loadState.collectLatest { state ->
                when (state) {
                    is LoadState.Idle -> {
                        val cached = TrackCache.points
                        if (intent.getBooleanExtra(EXTRA_FROM_CACHE, false) && cached != null) {
                            viewModel.loadFromCache(cached)
                        } else {
                            initProgressBar(totalSize)
                            setStatus("Downloading $name…")
                            viewModel.load(path, totalSize, (application as FlySightApp).bleManager)
                        }
                    }
                    is LoadState.Loading -> {
                        if (state.total > 0) {
                            binding.progressBar.isIndeterminate = false
                            binding.progressBar.max = state.total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                            binding.progressBar.progress = state.received.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                            binding.tvProgressBytes.visibility = View.VISIBLE
                            binding.tvProgressBytes.text = "${state.received / 1024} KB / ${state.total / 1024} KB"
                        } else {
                            binding.progressBar.isIndeterminate = true
                            binding.tvProgressBytes.visibility = View.GONE
                        }
                    }
                    is LoadState.Loaded -> showChart(state.points)
                    is LoadState.Failed -> {
                        setStatus(state.msg)
                        Toast.makeText(this@FileViewActivity, state.msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // ── Chart ──────────────────────────────────────────────────────────────

    private fun showChart(points: List<DataPoint>) {
        val elevE = ArrayList<Entry>(points.size)
        val hSpdE = ArrayList<Entry>(points.size)
        val vSpdE = ArrayList<Entry>(points.size)
        val totE  = ArrayList<Entry>(points.size)
        val grE   = ArrayList<Entry>(points.size)

        for (p in points) {
            val t = p.t.toFloat()
            elevE.add(Entry(t, p.hMSL.toFloat()))
            hSpdE.add(Entry(t, p.hSpeed.toFloat()))
            vSpdE.add(Entry(t, p.vSpeed.toFloat()))
            totE.add(Entry(t,  p.totalSpeed.toFloat()))
            grE.add(Entry(t,   p.glideRatio.coerceIn(-50.0, 50.0).toFloat()))
        }

        val colorElev   = getColor(R.color.colorChartElevation)
        val colorHSpeed = getColor(R.color.colorChartHSpeed)
        val colorVSpeed = getColor(R.color.colorChartVSpeed)
        val colorTotal  = getColor(R.color.colorChartTotalSpeed)
        val colorGR     = getColor(R.color.colorChartGR)

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
        val axisTextColor = if (isDark) getColor(R.color.colorTextSecondary) else Color.DKGRAY
        val gridColor     = if (isDark) Color.parseColor("#33FFFFFF") else Color.parseColor("#22000000")

        val chart = binding.lineChart
        chart.setBackgroundColor(Color.TRANSPARENT)
        chart.data = LineData(
            set(hSpdE, "H. Speed (km/h)",   colorHSpeed, YAxis.AxisDependency.LEFT),
            set(vSpdE, "V. Speed (km/h)",   colorVSpeed, YAxis.AxisDependency.LEFT),
            set(totE,  "Total Speed (km/h)", colorTotal,  YAxis.AxisDependency.LEFT),
            set(grE,   "Glide Ratio",        colorGR,     YAxis.AxisDependency.LEFT),
            set(elevE, "Elevation (m)",      colorElev,   YAxis.AxisDependency.RIGHT)
        )

        // Lock axis ranges so toggling series visibility never rescales the chart
        val leftMax = (points.maxOf { maxOf(it.hSpeed, it.vSpeed, it.totalSpeed,
            it.glideRatio.coerceIn(0.0, 50.0)) }.coerceAtLeast(1.0) * 1.1).toFloat()
        chart.axisLeft.axisMinimum = 0f
        chart.axisLeft.axisMaximum = leftMax

        val elevMin = points.minOf { it.hMSL }.toFloat()
        val elevMax = points.maxOf { it.hMSL }.toFloat()
        val elevPad = ((elevMax - elevMin) * 0.05f).coerceAtLeast(1f)
        chart.axisRight.axisMinimum = elevMin - elevPad
        chart.axisRight.axisMaximum = elevMax + elevPad

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            granularity = 10f
            textColor = axisTextColor
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = "${value.toInt()}s"
            }
        }

        chart.axisLeft.apply {
            setDrawGridLines(true)
            textColor = axisTextColor
            axisLineColor = axisTextColor
            this.gridColor = gridColor
        }

        chart.axisRight.apply {
            setDrawGridLines(false)
            textColor = colorElev
            axisLineColor = colorElev
        }

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
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.isScaleXEnabled = true
        chart.isScaleYEnabled = false
        chart.setPinchZoom(false)
        chart.isDoubleTapToZoomEnabled = true

        chart.post {
            chart.setVisibleXRangeMinimum(1f)
        }
        chart.moveViewToX(0f)

        chart.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN ||
                event.action == MotionEvent.ACTION_MOVE) {
                val pt: MPPointD = chart.getValuesByTouchPoint(
                    event.x, event.y, YAxis.AxisDependency.LEFT)
                updateValuesAt(pt.x.toFloat(), points)
                MPPointD.recycleInstance(pt)
            }
            false
        }

        chart.onChartGestureListener = object : OnChartGestureListener {
            override fun onChartGestureStart(me: MotionEvent, last: ChartTouchListener.ChartGesture) {}
            override fun onChartGestureEnd(me: MotionEvent, last: ChartTouchListener.ChartGesture) {}
            override fun onChartLongPressed(me: MotionEvent) {}
            override fun onChartDoubleTapped(me: MotionEvent) {}
            override fun onChartFling(me1: MotionEvent, me2: MotionEvent, vx: Float, vy: Float) {}
            override fun onChartSingleTapped(me: MotionEvent) {}
            override fun onChartScale(me: MotionEvent, scaleX: Float, scaleY: Float) {
                updateValuesAt(chart.lowestVisibleX, points)
            }
            override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) {}
        }

        chart.animateX(400)

        binding.loadingPanel.visibility = View.GONE
        binding.chartCard.visibility    = View.VISIBLE
        binding.valuesPanel.visibility  = View.VISIBLE

        updateValuesAt(0f, points)

        // Tap a column to toggle its chart series; value+unit dim when hidden
        fun toggleSeries(col: android.view.View, valueV: android.view.View, unitV: android.view.View, dsIdx: Int) {
            col.setOnClickListener {
                val ds = chart.data.getDataSetByIndex(dsIdx) ?: return@setOnClickListener
                ds.isVisible = !ds.isVisible
                chart.invalidate()
                val on = ds.isVisible
                valueV.alpha = if (on) 1f else 0.3f
                unitV.alpha  = if (on) 1f else 0.3f
            }
        }
        toggleSeries(binding.colElev,   binding.tvElevValue,   binding.tvElevUnit,   4)
        toggleSeries(binding.colHSpeed, binding.tvHSpeedValue, binding.tvHSpeedUnit, 0)
        toggleSeries(binding.colVSpeed, binding.tvVSpeedValue, binding.tvVSpeedUnit, 1)
        toggleSeries(binding.colTotal,  binding.tvTotalValue,  binding.tvTotalUnit,  2)
        toggleSeries(binding.colGR,     binding.tvGRValue,     binding.tvGRUnit,     3)
    }

    private fun updateValuesAt(timeSec: Float, points: List<DataPoint>) {
        if (points.isEmpty()) return
        val idx = points.indexOfFirst { it.t >= timeSec }
            .let { if (it < 0) points.size - 1 else it }
            .coerceIn(0, points.size - 1)
        val p = points[idx]
        binding.tvElevValue.text   = "%.0f".format(p.hMSL)
        binding.tvHSpeedValue.text = "%.1f".format(p.hSpeed)
        binding.tvVSpeedValue.text = "%.1f".format(p.vSpeed)
        binding.tvTotalValue.text  = "%.1f".format(p.totalSpeed)
        binding.tvGRValue.text     = if (p.glideRatio.isNaN()) "—" else "%.2f".format(p.glideRatio.coerceIn(-50.0, 50.0))
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

    private fun setStatus(msg: String) {
        binding.tvStatus.text           = msg
        binding.loadingPanel.visibility = View.VISIBLE
        binding.chartCard.visibility    = View.GONE
        binding.valuesPanel.visibility  = View.GONE
    }
}
