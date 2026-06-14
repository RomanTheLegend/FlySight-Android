package com.flysight.app

import android.content.Intent
import android.graphics.Bitmap
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
import com.flysight.app.ble.BleManager
import com.flysight.app.ble.BleState
import com.flysight.app.calc.FlySightCalc
import com.flysight.app.databinding.ActivityScoreViewBinding
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
    private var trackPolyline:  Polyline? = null
    private var lanePolyline:   Polyline? = null
    private var exitMarker:     Marker?   = null
    private var gate2500Marker: Marker?   = null
    private var gate1500Marker: Marker?   = null

    // High-visibility colors against OSM's green/orange/tan/navy palette
    private val colorExit     = Color.parseColor("#FFD600")  // yellow
    private val colorGate2500 = Color.parseColor("#FF00AA")  // magenta
    private val colorGate1500 = Color.parseColor("#00E5FF")  // cyan
    private val colorTarget   = Color.WHITE
    private val colorTrack    = Color.parseColor("#FF3333")  // red

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

        lifecycleScope.launch {
            ble.state.collectLatest { if (it == BleState.Disconnected) finish() }
        }

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
            points, lockIn.lat, lockIn.lon, extended.lat, extended.lon, dzElevM)

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
            poly.outlinePaint.color       = Color.parseColor("#5B8CCC")
            poly.outlinePaint.strokeWidth = 3f
            poly.outlinePaint.pathEffect  = DashPathEffect(floatArrayOf(30f, 15f), 0f)
            poly.setPoints(listOf(
                GeoPoint(lockIn.lat,   lockIn.lon),
                GeoPoint(tgtLat,       tgtLon),
                GeoPoint(extended.lat, extended.lon)
            ))
            map.overlays.add(poly)
        }

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
