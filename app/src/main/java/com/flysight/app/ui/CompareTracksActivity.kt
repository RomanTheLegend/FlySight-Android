package com.flysight.app.ui

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.flysight.app.FlySightApp
import com.flysight.app.R
import com.flysight.app.data.DataPoint
import com.flysight.app.databinding.ActivityCompareTracksBinding
import com.flysight.app.util.finishOnBleDisconnect
import com.flysight.app.util.formatFileSize
import com.flysight.app.viewmodel.CompareTracksViewModel
import com.flysight.app.viewmodel.LoadState
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.github.mikephil.charting.utils.MPPointD
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CompareTracksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompareTracksBinding
    private val viewModel: CompareTracksViewModel by viewModels()

    private val pathStack   = ArrayDeque<String>()
    private val currentPath get() = pathStack.lastOrNull() ?: ""
    private val datePattern = Regex("""\d{2}-\d{2}-\d{2}""")
    private lateinit var fileAdapter: FileListAdapter

    private var selectingSlot = 0   // 0 = not browsing; 1 or 2 = selecting that slot
    private var points1: List<DataPoint>? = null
    private var points2: List<DataPoint>? = null
    private var name1 = ""
    private var name2 = ""

    // Track 2 colours — lighter variants of the T1 chart palette
    private val t2HSpeedColor = Color.parseColor("#82B4E8")
    private val t2VSpeedColor = Color.parseColor("#E87A7A")
    private val t2TotalColor  = Color.parseColor("#7ADDA6")
    private val t2ElevColor   = Color.parseColor("#E8C460")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompareTracksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val ble = (application as FlySightApp).bleManager

        binding.btnHeaderBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnDisconnect.setOnClickListener { ble.disconnect(); finish() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    selectingSlot != 0 && pathStack.size > 1 -> { pathStack.removeLast(); loadDirectory() }
                    selectingSlot != 0 -> exitBrowse()
                    else -> finish()
                }
            }
        })

        fileAdapter = FileListAdapter(onClick = { entry ->
            val path = if (currentPath.isEmpty()) entry.name else "$currentPath/${entry.name}"
            if (entry.isDirectory) { pathStack.addLast(path); loadDirectory() }
        })
        binding.recyclerFiles.layoutManager = LinearLayoutManager(this)
        binding.recyclerFiles.adapter = fileAdapter

        binding.btnSelectTrack1.setOnClickListener { enterBrowse(1) }
        binding.btnSelectTrack2.setOnClickListener { enterBrowse(2) }

        observeTrack(1, viewModel.track1, ble)
        observeTrack(2, viewModel.track2, ble)

        finishOnBleDisconnect(ble)
    }

    // ── Browse ────────────────────────────────────────────────────────────────

    private fun enterBrowse(slot: Int) {
        selectingSlot = slot
        viewModel.reset(slot)
        if (slot == 1) points1 = null else points2 = null
        binding.chartCard.visibility  = View.GONE
        binding.valuesPanel.visibility = View.GONE
        pathStack.clear()
        pathStack.addLast("")
        binding.browsingPanel.visibility = View.VISIBLE
        binding.comparePanel.visibility  = View.GONE
        binding.tvSubtitle.text = if (slot == 1) "Select track 1" else "Select track 2"
        loadDirectory()
    }

    private fun exitBrowse() {
        selectingSlot = 0
        binding.browsingPanel.visibility = View.GONE
        binding.comparePanel.visibility  = View.VISIBLE
        binding.tvSubtitle.text = subtitle()
    }

    private fun loadDirectory() {
        binding.tvPath.text = if (currentPath.isEmpty()) "/ root" else "/$currentPath"
        binding.tvEmpty.visibility          = View.GONE
        binding.browsingProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val ble = (application as FlySightApp).bleManager
                val all = ble.listDir(currentPath)

                val trackCsv = all.firstOrNull { it.name.equals("TRACK.CSV", ignoreCase = true) }
                if (trackCsv != null) {
                    val trackPath = if (currentPath.isEmpty()) trackCsv.name else "$currentPath/${trackCsv.name}"
                    val slot = selectingSlot
                    val parts = trackPath.split("/").filter { it.isNotEmpty() }
                    val label = when {
                        parts.size >= 3 -> "${parts[parts.size - 3]} · ${parts[parts.size - 2]}"
                        parts.size >= 2 -> parts[parts.size - 2]
                        else            -> trackCsv.name
                    }
                    if (slot == 1) name1 = label else name2 = label
                    pathStack.removeLast()
                    binding.browsingProgress.visibility = View.GONE
                    exitBrowse()
                    viewModel.load(slot, trackPath, trackCsv.size, ble)
                    return@launch
                }

                val dateFolders    = all.filter { it.isDirectory && datePattern.matches(it.name) }
                val specialFolders = all.filter { it.isDirectory && !datePattern.matches(it.name) }
                val items = buildList<FileBrowserItem> {
                    if (dateFolders.isNotEmpty()) {
                        add(FileBrowserItem.Header("FOLDERS"))
                        dateFolders.forEach { add(FileBrowserItem.Entry(it)) }
                    }
                    if (specialFolders.isNotEmpty()) {
                        add(FileBrowserItem.Header("SPECIAL FOLDERS"))
                        specialFolders.forEach { add(FileBrowserItem.Entry(it)) }
                    }
                }
                fileAdapter.update(items)
                binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Toast.makeText(this@CompareTracksActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.browsingProgress.visibility = View.GONE
            }
        }
    }

    // ── Track observation ─────────────────────────────────────────────────────

    private fun observeTrack(slot: Int, flow: StateFlow<LoadState>, ble: com.flysight.app.ble.BleManager) {
        lifecycleScope.launch {
            flow.collectLatest { state ->
                val nameView  = if (slot == 1) binding.tvTrack1Name   else binding.tvTrack2Name
                val pb        = if (slot == 1) binding.pbTrack1        else binding.pbTrack2
                val otherBtn  = if (slot == 1) binding.btnSelectTrack2 else binding.btnSelectTrack1
                val loading   = state is LoadState.Loading || state is LoadState.Parsing
                otherBtn.isEnabled = !loading
                otherBtn.alpha     = if (loading) 0.4f else 1f
                when (state) {
                    is LoadState.Idle -> {
                        nameView.text = "Select track…"
                        pb.visibility = View.GONE
                    }
                    is LoadState.Loading -> {
                        pb.visibility = View.VISIBLE
                        if (state.total > 0) {
                            pb.isIndeterminate = false
                            pb.max      = state.total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                            pb.progress = state.received.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                            nameView.text = state.received.formatFileSize() + " / " + state.total.formatFileSize()
                        } else {
                            pb.isIndeterminate = true
                            nameView.text = if (state.received > 0) state.received.formatFileSize() else "Loading…"
                        }
                    }
                    is LoadState.Parsing -> {
                        pb.visibility = View.VISIBLE
                        pb.isIndeterminate = true
                        nameView.text = "Analyzing…"
                    }
                    is LoadState.Loaded -> {
                        pb.visibility = View.GONE
                        val pts = state.points.filter { it.t >= 0.0 }
                        if (slot == 1) { points1 = pts; nameView.text = name1 }
                        else           { points2 = pts; nameView.text = name2 }
                        maybeRenderChart()
                    }
                    is LoadState.Failed -> {
                        pb.visibility = View.GONE
                        nameView.text = "Error"
                        if (slot == 1) points1 = null else points2 = null
                        Toast.makeText(this@CompareTracksActivity, state.msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // ── Chart ─────────────────────────────────────────────────────────────────

    private fun maybeRenderChart() {
        val p1 = points1 ?: return
        val p2 = points2 ?: return
        renderChart(p1, p2)
        binding.tvSubtitle.text = subtitle()
    }

    private fun renderChart(p1: List<DataPoint>, p2: List<DataPoint>) {
        val t1HSpeedColor = getColor(R.color.colorChartHSpeed)
        val t1VSpeedColor = getColor(R.color.colorChartVSpeed)
        val t1TotalColor  = getColor(R.color.colorChartTotalSpeed)
        val t1ElevColor   = getColor(R.color.colorChartElevation)

        fun entries(pts: List<DataPoint>, sel: (DataPoint) -> Float) =
            ArrayList<Entry>(pts.size).also { list -> pts.forEach { list.add(Entry(it.t.toFloat(), sel(it))) } }

        fun set(entries: ArrayList<Entry>, label: String, color: Int, axis: YAxis.AxisDependency) =
            LineDataSet(entries, label).apply {
                this.color = color
                setDrawCircles(false); setDrawValues(false)
                lineWidth = 1.5f; axisDependency = axis; mode = LineDataSet.Mode.LINEAR
            }

        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val axisTextColor = if (isDark) getColor(R.color.colorTextSecondary) else Color.DKGRAY
        val gridColor     = if (isDark) Color.parseColor("#33FFFFFF") else Color.parseColor("#22000000")

        // Dataset order: T1 → indices 0–3, T2 → indices 4–7
        val chart = binding.lineChart
        chart.setBackgroundColor(Color.TRANSPARENT)
        chart.data = LineData(
            set(entries(p1) { it.hSpeed.toFloat() },     "T1 H.Speed",  t1HSpeedColor, YAxis.AxisDependency.LEFT),
            set(entries(p1) { it.vSpeed.toFloat() },     "T1 V.Speed",  t1VSpeedColor, YAxis.AxisDependency.LEFT),
            set(entries(p1) { it.totalSpeed.toFloat() }, "T1 Total",    t1TotalColor,  YAxis.AxisDependency.LEFT),
            set(entries(p1) { it.hMSL.toFloat() },       "T1 Elev",     t1ElevColor,   YAxis.AxisDependency.RIGHT),
            set(entries(p2) { it.hSpeed.toFloat() },     "T2 H.Speed",  t2HSpeedColor, YAxis.AxisDependency.LEFT),
            set(entries(p2) { it.vSpeed.toFloat() },     "T2 V.Speed",  t2VSpeedColor, YAxis.AxisDependency.LEFT),
            set(entries(p2) { it.totalSpeed.toFloat() }, "T2 Total",    t2TotalColor,  YAxis.AxisDependency.LEFT),
            set(entries(p2) { it.hMSL.toFloat() },       "T2 Elev",     t2ElevColor,   YAxis.AxisDependency.RIGHT)
        )

        val allPts = p1 + p2
        val leftMax = (allPts.maxOf { maxOf(it.hSpeed, it.vSpeed, it.totalSpeed) }.coerceAtLeast(1.0) * 1.1).toFloat()
        chart.axisLeft.axisMinimum = 0f
        chart.axisLeft.axisMaximum = leftMax

        val elevMin = allPts.minOf { it.hMSL }.toFloat()
        val elevMax = allPts.maxOf { it.hMSL }.toFloat()
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
            setDrawGridLines(true); textColor = axisTextColor
            axisLineColor = axisTextColor; this.gridColor = gridColor
        }
        chart.axisRight.apply {
            setDrawGridLines(false)
            textColor = t1ElevColor; axisLineColor = t1ElevColor
        }
        chart.legend.isEnabled = false

        chart.description.isEnabled      = false
        chart.setTouchEnabled(true)
        chart.isDragEnabled              = true
        chart.isScaleXEnabled            = true
        chart.isScaleYEnabled            = false
        chart.setPinchZoom(false)
        chart.isDoubleTapToZoomEnabled   = true

        chart.post {
            chart.setVisibleXRangeMinimum(1f)
        }
        chart.moveViewToX(0f)

        chart.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                val pt: MPPointD = chart.getValuesByTouchPoint(event.x, event.y, YAxis.AxisDependency.LEFT)
                updateValuesAt(pt.x.toFloat(), p1, p2)
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
                updateValuesAt(chart.lowestVisibleX, p1, p2)
            }
            override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) {}
        }

        chart.animateX(400)

        binding.chartCard.visibility  = View.VISIBLE
        binding.valuesPanel.visibility = View.VISIBLE

        updateValuesAt(0f, p1, p2)

        // Toggle helpers — ds indices: T1: 0=HSpd 1=VSpd 2=Total 3=Elev; T2: 4=HSpd 5=VSpd 6=Total 7=Elev
        fun toggle(col: View, valueViews: List<View>, dsIdx: Int) {
            col.setOnClickListener {
                val ds = chart.data.getDataSetByIndex(dsIdx) ?: return@setOnClickListener
                ds.isVisible = !ds.isVisible
                chart.invalidate()
                valueViews.forEach { it.alpha = if (ds.isVisible) 1f else 0.3f }
            }
        }
        toggle(binding.colT1HSpeed, listOf(binding.tvT1HSpeedValue, binding.tvT1HSpeedUnit), 0)
        toggle(binding.colT1VSpeed, listOf(binding.tvT1VSpeedValue, binding.tvT1VSpeedUnit), 1)
        toggle(binding.colT1Total,  listOf(binding.tvT1TotalValue,  binding.tvT1TotalUnit),  2)
        toggle(binding.colT1Elev,   listOf(binding.tvT1ElevValue,   binding.tvT1ElevUnit),   3)
        toggle(binding.colT2HSpeed, listOf(binding.tvT2HSpeedValue, binding.tvT2HSpeedUnit), 4)
        toggle(binding.colT2VSpeed, listOf(binding.tvT2VSpeedValue, binding.tvT2VSpeedUnit), 5)
        toggle(binding.colT2Total,  listOf(binding.tvT2TotalValue,  binding.tvT2TotalUnit),  6)
        toggle(binding.colT2Elev,   listOf(binding.tvT2ElevValue,   binding.tvT2ElevUnit),   7)
    }

    private fun updateValuesAt(timeSec: Float, p1: List<DataPoint>, p2: List<DataPoint>) {
        fun fmt(pts: List<DataPoint>, sel: (DataPoint) -> String) =
            pointAt(pts, timeSec)?.let { sel(it) } ?: "—"

        binding.tvT1ElevValue.text   = fmt(p1) { "%.0f".format(it.hMSL) }
        binding.tvT1HSpeedValue.text = fmt(p1) { "%.1f".format(it.hSpeed) }
        binding.tvT1VSpeedValue.text = fmt(p1) { "%.1f".format(it.vSpeed) }
        binding.tvT1TotalValue.text  = fmt(p1) { "%.1f".format(it.totalSpeed) }
        binding.tvT2ElevValue.text   = fmt(p2) { "%.0f".format(it.hMSL) }
        binding.tvT2HSpeedValue.text = fmt(p2) { "%.1f".format(it.hSpeed) }
        binding.tvT2VSpeedValue.text = fmt(p2) { "%.1f".format(it.vSpeed) }
        binding.tvT2TotalValue.text  = fmt(p2) { "%.1f".format(it.totalSpeed) }
    }

    private fun pointAt(pts: List<DataPoint>, timeSec: Float): DataPoint? {
        if (pts.isEmpty() || timeSec > pts.last().t.toFloat()) return null
        val idx = pts.indexOfFirst { it.t >= timeSec }
            .let { if (it < 0) pts.size - 1 else it }
            .coerceIn(0, pts.size - 1)
        return pts[idx]
    }

    private fun subtitle(): String = when {
        points1 != null && points2 != null -> "Comparing $name1 vs $name2"
        points1 != null || points2 != null -> "Select one more track"
        else -> "Select two tracks"
    }
}
