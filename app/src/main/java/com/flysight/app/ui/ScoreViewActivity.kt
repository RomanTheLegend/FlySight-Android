package com.flysight.app.ui

import android.content.Intent
import android.graphics.Bitmap
import kotlin.math.*
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.flysight.app.FlySightApp
import com.flysight.app.R
import com.flysight.app.ble.BleManager
import com.flysight.app.calc.FlySightCalc
import com.flysight.app.data.DataPoint
import com.flysight.app.data.DataProcessor
import com.flysight.app.data.TrackCache
import com.flysight.app.databinding.ActivityScoreViewBinding
import com.flysight.app.util.finishOnBleDisconnect
import com.flysight.app.viewmodel.LoadState
import com.flysight.app.viewmodel.ScoreViewViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class ScoreViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScoreViewBinding
    private val viewModel: ScoreViewViewModel by viewModels()

    private lateinit var ble: BleManager

    private val pathStack   = ArrayDeque<String>()
    private val currentPath get() = pathStack.lastOrNull() ?: ""
    private val datePattern = Regex("""\d{2}-\d{2}-\d{2}""")

    private lateinit var fileAdapter: FileListAdapter

    private var loadedPoints: List<DataPoint>? = null
    private var targetLat:    Double?           = null
    private var targetLon:    Double?           = null
    private var trackTitle:   String            = "Run Score"

    private var targetMarker:   Marker?   = null
    private var lockInMarker:   Marker?   = null
    private var trackPolyline:  Polyline? = null
    private var lanePolyline:   Polyline? = null
    private var laneLeft150:    Polyline? = null
    private var laneRight150:   Polyline? = null
    private var laneLeft300:    Polyline? = null
    private var laneRight300:   Polyline? = null
    private var exitMarker:     Marker?   = null
    private var gate2500Marker: Marker?   = null
    private var gate1500Marker: Marker?   = null

    private val colorExit     by lazy { getColor(R.color.colorMapExit) }
    private val colorGate2500 by lazy { getColor(R.color.colorMapGate2500) }
    private val colorGate1500 by lazy { getColor(R.color.colorMapGate1500) }
    private val colorTarget   by lazy { getColor(R.color.colorMapTarget) }
    private val colorTrack    by lazy { getColor(R.color.colorMapTrack) }
    private val colorLockIn   by lazy { getColor(R.color.colorMapLane) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().let { cfg ->
            cfg.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
            cfg.userAgentValue = packageName
            cfg.osmdroidTileCache = java.io.File(cacheDir, "osmdroid")
        }

        binding = ActivityScoreViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ble = (application as FlySightApp).bleManager

        binding.btnHeaderBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnDisconnect.setOnClickListener { ble.disconnect(); finish() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.contentPanel.visibility == View.VISIBLE -> returnToBrowse()
                    pathStack.size > 1 -> { pathStack.removeLast(); loadDirectory() }
                    else -> finish()
                }
            }
        })

        fileAdapter = FileListAdapter(onClick = { entry ->
            val entryPath = if (currentPath.isEmpty()) entry.name else "$currentPath/${entry.name}"
            if (entry.isDirectory) { pathStack.addLast(entryPath); loadDirectory() }
        })
        binding.recyclerFiles.layoutManager = LinearLayoutManager(this)
        binding.recyclerFiles.adapter = fileAdapter

        val map = binding.mapView
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(14.0)
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint) = false
            override fun longPressHelper(p: GeoPoint): Boolean {
                setTarget(p.latitude, p.longitude)
                return true
            }
        }))

        binding.btnCalculate.setOnClickListener { calculateScore() }

        binding.btnViewTrackDetails.setOnClickListener {
            val pts = loadedPoints ?: return@setOnClickListener
            TrackCache.points = pts
            startActivity(Intent(this, FileViewActivity::class.java).apply {
                putExtra(FileViewActivity.EXTRA_NAME, trackTitle)
                putExtra(FileViewActivity.EXTRA_PATH, "")
                putExtra(FileViewActivity.EXTRA_SIZE, 0L)
                putExtra(FileViewActivity.EXTRA_FROM_CACHE, true)
            })
        }

        lifecycleScope.launch {
            viewModel.loadState.collectLatest { state ->
                when (state) {
                    is LoadState.Idle    -> Unit
                    is LoadState.Loading -> updateProgress(state)
                    is LoadState.Loaded  -> onTrackLoaded(state.points)
                    is LoadState.Failed  -> {
                        Toast.makeText(this@ScoreViewActivity, state.msg, Toast.LENGTH_LONG).show()
                        returnToBrowse()
                    }
                }
            }
        }

        finishOnBleDisconnect(ble)

        pathStack.addLast("")
        loadDirectory()
        showBrowse()
    }

    // ── Browse ────────────────────────────────────────────────────────────────

    private fun showBrowse() {
        binding.browsingPanel.visibility       = View.VISIBLE
        binding.loadingPanel.visibility        = View.GONE
        binding.contentPanel.visibility        = View.GONE
        binding.btnViewTrackDetails.visibility = View.GONE
        binding.tvTitle.text    = "Run Score"
        binding.tvSubtitle.text = "Select a track"
    }

    private fun returnToBrowse() {
        loadedPoints = null
        viewModel.reset()
        pathStack.clear()
        pathStack.addLast("")
        loadDirectory()
        showBrowse()
    }

    private fun loadDirectory() {
        binding.tvPath.text = if (currentPath.isEmpty()) "/ root" else "/$currentPath"
        binding.tvEmpty.visibility          = View.GONE
        binding.browsingProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val all = ble.listDir(currentPath)

                val trackCsv = all.firstOrNull { it.name.equals("TRACK.CSV", ignoreCase = true) }
                if (trackCsv != null) {
                    val trackPath = if (currentPath.isEmpty()) trackCsv.name
                                    else "$currentPath/${trackCsv.name}"
                    pathStack.removeLast()
                    binding.browsingProgress.visibility = View.GONE
                    beginDownload(trackPath, trackCsv.name, trackCsv.size)
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
                Toast.makeText(this@ScoreViewActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.browsingProgress.visibility = View.GONE
            }
        }
    }

    // ── Loading ───────────────────────────────────────────────────────────────

    private fun beginDownload(path: String, name: String, size: Long) {
        val parts = path.split("/").filter { it.isNotEmpty() }
        trackTitle = when {
            parts.size >= 3 -> "${parts[parts.size - 3]} · ${parts[parts.size - 2]}"
            parts.size >= 2 -> parts[parts.size - 2]
            else            -> name
        }
        binding.tvTitle.text    = trackTitle
        binding.tvSubtitle.text = "Downloading…"
        binding.tvStatus.text   = "Downloading $name…"
        binding.progressBar.isIndeterminate = true
        binding.tvProgressBytes.visibility  = View.GONE
        binding.browsingPanel.visibility    = View.GONE
        binding.loadingPanel.visibility     = View.VISIBLE
        binding.contentPanel.visibility     = View.GONE
        viewModel.load(path, size, ble)
    }

    private fun updateProgress(state: LoadState.Loading) {
        if (state.total > 0) {
            binding.progressBar.isIndeterminate = false
            binding.progressBar.max      = state.total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            binding.progressBar.progress = state.received.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            binding.tvProgressBytes.visibility = View.VISIBLE
            binding.tvProgressBytes.text = "${state.received / 1024} KB / ${state.total / 1024} KB"
        } else {
            binding.progressBar.isIndeterminate = true
            binding.tvProgressBytes.visibility  = View.GONE
        }
    }

    // ── Content ───────────────────────────────────────────────────────────────

    private fun onTrackLoaded(points: List<DataPoint>) {
        loadedPoints = points

        val exitPt = DataProcessor.interpolateAt(points, 0.0)
        binding.mapView.controller.setZoom(15.0)
        binding.mapView.controller.setCenter(GeoPoint(exitPt.lat, exitPt.lon))

        // Auto-populate DZ elevation from the last GPS point (ground level)
        binding.etDzElev.setText("%.0f".format(points.last().hMSL))

        // Place exit marker immediately — don't wait for Calculate
        placeExitMarker(exitPt)

        binding.tvTitle.text    = trackTitle
        binding.tvSubtitle.text = "Set target & calculate"
        binding.scoreCard.visibility          = View.GONE
        binding.btnViewTrackDetails.visibility = View.VISIBLE
        binding.btnCalculate.isEnabled        = targetLat != null

        binding.browsingPanel.visibility = View.GONE
        binding.loadingPanel.visibility  = View.GONE
        binding.contentPanel.visibility  = View.VISIBLE
    }

    private fun placeExitMarker(exitPt: DataPoint) {
        val map = binding.mapView
        exitMarker?.let { map.overlays.remove(it) }
        exitMarker = makeMarker(map, GeoPoint(exitPt.lat, exitPt.lon), "Exit", colorExit)
        map.overlays.add(exitMarker)
        map.invalidate()
    }

    private fun setTarget(lat: Double, lon: Double) {
        targetLat = lat
        targetLon = lon

        val map = binding.mapView
        targetMarker?.let { map.overlays.remove(it) }
        targetMarker = makeMarker(map, GeoPoint(lat, lon), "Target", colorTarget)
        map.overlays.add(targetMarker)
        map.invalidate()

        binding.tvTargetCoords.text = "%.6f, %.6f".format(lat, lon)
        binding.tvTargetCoords.setTextColor(getColor(R.color.colorTextPrimary))

        if (loadedPoints != null) binding.btnCalculate.isEnabled = true
    }

    private fun calculateScore() {
        val points = loadedPoints ?: return
        val tgtLat = targetLat   ?: return
        val tgtLon = targetLon   ?: return

        val dzElevM = binding.etDzElev.text?.toString()?.toDoubleOrNull()
            ?: points.last().hMSL
        val score   = FlySightCalc.scoreJump(points, dzElevM)

        if (score == null) {
            Toast.makeText(
                this,
                "Could not score jump — track must cross 2500 m and 1500 m AGL",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        binding.tvDistValue.text  = "%.0f".format(score.distanceM)
        binding.tvTimeValue.text  = "%.2f".format(score.timeSec)
        binding.tvSpeedValue.text = "%.1f".format(score.speedKmh)

        drawOverlays(points, tgtLat, tgtLon, dzElevM)

        // Penalty — computed after drawOverlays so lockIn/extended are available
        val lockInT  = FlySightCalc.laneLockInTime(points) ?: 9.0
        val lockIn   = DataProcessor.interpolateAt(points, lockInT)
        val extended = FlySightCalc.extendLane(lockIn.lat, lockIn.lon, tgtLat, tgtLon, 8047.0)
        val pct      = FlySightCalc.penaltyPercent(
            points, lockInT, lockIn.lat, lockIn.lon, extended.lat, extended.lon, dzElevM)

        val factor = 1.0 - pct / 100.0
        binding.tvPenaltyValue.text  = "$pct%"
        binding.tvPenaltyValue.setTextColor(
            if (pct == 0) getColor(R.color.colorPrimary) else getColor(R.color.colorDanger))
        binding.tvAdjDistValue.text  = "%.0f".format(score.distanceM * factor)
        binding.tvAdjTimeValue.text  = "%.2f".format(score.timeSec * factor)
        binding.tvAdjSpeedValue.text = "%.1f".format(score.speedKmh  * factor)

        binding.scoreCard.visibility = View.VISIBLE
    }

    private fun drawOverlays(
        points:  List<DataPoint>,
        tgtLat:  Double,
        tgtLon:  Double,
        dzElevM: Double
    ) {
        val map = binding.mapView
        trackPolyline?.let  { map.overlays.remove(it) }
        lanePolyline?.let   { map.overlays.remove(it) }
        lockInMarker?.let   { map.overlays.remove(it) }
        laneLeft150?.let    { map.overlays.remove(it) }
        laneRight150?.let   { map.overlays.remove(it) }
        laneLeft300?.let    { map.overlays.remove(it) }
        laneRight300?.let   { map.overlays.remove(it) }
        exitMarker?.let     { map.overlays.remove(it) }
        gate2500Marker?.let { map.overlays.remove(it) }
        gate1500Marker?.let { map.overlays.remove(it) }

        val exitPt = DataProcessor.interpolateAt(points, 0.0)
        val pt2500 = findGateCrossing(points, dzElevM, 2500.0)
        val pt1500 = findGateCrossing(points, dzElevM, 1500.0)

        // Track: exit → 1500 m AGL (red)
        val trackGeoPoints = mutableListOf<GeoPoint>()
        for (p in points) {
            if (p.t < 0.0) continue
            if (p.hMSL - dzElevM < 1500.0) break
            trackGeoPoints.add(GeoPoint(p.lat, p.lon))
        }
        pt1500?.let { trackGeoPoints.add(GeoPoint(it.lat, it.lon)) }

        if (trackGeoPoints.size >= 2) {
            trackPolyline = Polyline().also { poly ->
                poly.outlinePaint.color       = colorTrack
                poly.outlinePaint.strokeWidth = 4f
                poly.setPoints(trackGeoPoints)
                map.overlays.add(poly)
            }
        }

        // Lane: lock-in (9 s after velD reaches 10 m/s) → target → 5 miles extended (dashed blue)
        val lockInT  = FlySightCalc.laneLockInTime(points) ?: 9.0
        val lockIn   = DataProcessor.interpolateAt(points, lockInT)
        val extended = FlySightCalc.extendLane(lockIn.lat, lockIn.lon, tgtLat, tgtLon, 8047.0)
        lanePolyline = Polyline().also { poly ->
            poly.outlinePaint.color       = getColor(R.color.colorMapLane)
            poly.outlinePaint.strokeWidth = 3f
            poly.outlinePaint.pathEffect  = DashPathEffect(floatArrayOf(30f, 15f), 0f)
            poly.setPoints(listOf(
                GeoPoint(lockIn.lat,   lockIn.lon),
                GeoPoint(tgtLat,       tgtLon),
                GeoPoint(extended.lat, extended.lon)
            ))
            map.overlays.add(poly)
        }

        lockInMarker = makeMarker(map, GeoPoint(lockIn.lat, lockIn.lon), "Lane lock", colorLockIn)
        map.overlays.add(lockInMarker)

        // Parallel boundary lines at ±150 m and ±300 m from the lane centre
        val latA    = Math.toRadians(lockIn.lat); val lonA = Math.toRadians(lockIn.lon)
        val latB    = Math.toRadians(tgtLat);     val lonB = Math.toRadians(tgtLon)
        val laneBrg = atan2(sin(lonB - lonA) * cos(latB),
                            cos(latA) * sin(latB) - sin(latA) * cos(latB) * cos(lonB - lonA))

        fun offsetLane(bearing: Double, distM: Double): List<GeoPoint> {
            fun off(lat: Double, lon: Double) =
                FlySightCalc.pointAtBearing(lat, lon, bearing, distM).let { GeoPoint(it.lat, it.lon) }
            return listOf(
                off(lockIn.lat,   lockIn.lon),
                off(tgtLat,       tgtLon),
                off(extended.lat, extended.lon)
            )
        }

        fun parallelLine(bearing: Double, distM: Double, alpha: Int, width: Float, dashOn: Float, dashOff: Float): Polyline =
            Polyline().also { poly ->
                val base = getColor(R.color.colorMapLane)
                poly.outlinePaint.color       = Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
                poly.outlinePaint.strokeWidth = width
                poly.outlinePaint.pathEffect  = DashPathEffect(floatArrayOf(dashOn, dashOff), 0f)
                poly.setPoints(offsetLane(bearing, distM))
                map.overlays.add(poly)
            }

        val perpRight = laneBrg + PI / 2
        val perpLeft  = laneBrg - PI / 2
        val half = FlySightCalc.LANE_WIDTH / 2
        laneLeft150  = parallelLine(perpLeft,  half + 150.0, 160, 1.5f, 16f, 12f)
        laneRight150 = parallelLine(perpRight, half + 150.0, 160, 1.5f, 16f, 12f)
        laneLeft300  = parallelLine(perpLeft,  half + 300.0, 160, 1.0f, 10f, 14f)
        laneRight300 = parallelLine(perpRight, half + 300.0, 160, 1.0f, 10f, 14f)

        // Labeled circle markers
        exitMarker = makeMarker(map, GeoPoint(exitPt.lat, exitPt.lon), "Exit", colorExit)
        map.overlays.add(exitMarker)

        pt2500?.let { pt ->
            gate2500Marker = makeMarker(map, GeoPoint(pt.lat, pt.lon), "2500m", colorGate2500)
            map.overlays.add(gate2500Marker)
        }
        pt1500?.let { pt ->
            gate1500Marker = makeMarker(map, GeoPoint(pt.lat, pt.lon), "1500m", colorGate1500)
            map.overlays.add(gate1500Marker)
        }

        map.invalidate()

        val allPts = trackGeoPoints + listOf(GeoPoint(tgtLat, tgtLon), GeoPoint(extended.lat, extended.lon))
        if (allPts.size >= 2) {
            val bbox = BoundingBox.fromGeoPoints(allPts)
            map.post { map.zoomToBoundingBox(bbox, true, 80) }
        }
    }

    // ── Marker helpers ────────────────────────────────────────────────────────

    private fun makeMarker(map: MapView, point: GeoPoint, label: String, color: Int): Marker {
        val (bmp, anchorV) = makeCircleLabel(label, color)
        return Marker(map).apply {
            position = point
            icon     = BitmapDrawable(resources, bmp)
            setAnchor(0.5f, anchorV)
        }
    }

    /**
     * Draws a bold label above a small filled circle with white outline.
     * Returns the bitmap and the anchorV that pins the circle center to the geo coordinate.
     */
    private fun makeCircleLabel(label: String, color: Int): Pair<Bitmap, Float> {
        val d       = resources.displayMetrics.density
        val circleR = (7 * d).toInt()
        val gap     = (3 * d).toInt()
        val padH    = (4 * d).toInt()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = 10f * d
        paint.typeface = Typeface.DEFAULT_BOLD

        val fm    = paint.fontMetrics
        val textH = (fm.descent - fm.ascent).toInt()
        val textW = paint.measureText(label).toInt()

        val bmpW = maxOf(textW + padH * 2, circleR * 2 + 2)
        val bmpH = textH + gap + circleR * 2

        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val c   = Canvas(bmp)

        val textX = (bmpW - textW) / 2f
        val textY = -fm.ascent   // baseline so text top sits at y = 0

        // Dark stroke → white fill for contrast on any map tile colour
        paint.style       = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color       = Color.BLACK
        c.drawText(label, textX, textY, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        c.drawText(label, textX, textY, paint)

        // Filled circle
        val cx = bmpW / 2f
        val cy = textH.toFloat() + gap + circleR

        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawCircle(cx, cy, circleR.toFloat(), paint)

        // White border around circle
        paint.style       = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color       = Color.WHITE
        c.drawCircle(cx, cy, circleR.toFloat(), paint)

        return Pair(bmp, cy / bmpH)
    }

    // ── Gate crossing helper ──────────────────────────────────────────────────

    private fun findGateCrossing(points: List<DataPoint>, dzElevM: Double, gateAGL: Double): DataPoint? {
        for (i in 1 until points.size) {
            val p1 = points[i - 1]; val p2 = points[i]
            if (p1.hMSL - dzElevM >= gateAGL && p2.hMSL - dzElevM < gateAGL) {
                val span = p1.hMSL - p2.hMSL
                val a    = if (span != 0.0) (p1.hMSL - (gateAGL + dzElevM)) / span else 0.0
                return DataProcessor.interpolateAt(listOf(p1, p2), p1.t + a * (p2.t - p1.t))
            }
        }
        return null
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() { super.onResume(); binding.mapView.onResume() }
    override fun onPause()  { super.onPause();  binding.mapView.onPause()  }
}
