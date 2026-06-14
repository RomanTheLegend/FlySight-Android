package com.flysight.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.flysight.app.databinding.ActivityMapPickerBinding
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MapPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HAS_DZ  = "has_dz"
        const val EXTRA_DZ_LAT  = "dz_lat"
        const val EXTRA_DZ_LON  = "dz_lon"
        const val EXTRA_HAS_TGT = "has_tgt"
        const val EXTRA_TGT_LAT = "tgt_lat"
        const val EXTRA_TGT_LON = "tgt_lon"
        private const val LOC_PERM_REQ = 1001
    }

    private lateinit var binding: ActivityMapPickerBinding
    private lateinit var myLocationOverlay: MyLocationNewOverlay
    private var dzMarker:  Marker? = null
    private var tgtMarker: Marker? = null
    private var dzLat:  Double? = null
    private var dzLon:  Double? = null
    private var tgtLat: Double? = null
    private var tgtLon: Double? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().let { cfg ->
            cfg.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
            cfg.userAgentValue = packageName
            cfg.osmdroidTileCache = java.io.File(cacheDir, "osmdroid")
        }
        binding = ActivityMapPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnDone.setOnClickListener { returnResult() }
        binding.fabMyLocation.setOnClickListener {
            val loc = myLocationOverlay.myLocation
            if (loc != null) {
                binding.mapView.controller.animateTo(loc)
            } else {
                centerOnCurrentLocation()
            }
        }

        if (intent.getBooleanExtra(EXTRA_HAS_DZ, false)) {
            dzLat = intent.getDoubleExtra(EXTRA_DZ_LAT, 0.0)
            dzLon = intent.getDoubleExtra(EXTRA_DZ_LON, 0.0)
        }
        if (intent.getBooleanExtra(EXTRA_HAS_TGT, false)) {
            tgtLat = intent.getDoubleExtra(EXTRA_TGT_LAT, 0.0)
            tgtLon = intent.getDoubleExtra(EXTRA_TGT_LON, 0.0)
        }

        val map = binding.mapView
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(14.0)

        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map).apply {
            setPersonIcon(makeLocationDot(48))
            setDirectionIcon(makeLocationDot(48))
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            myLocationOverlay.enableMyLocation()
        }
        map.overlays.add(myLocationOverlay)

        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint) = false
            override fun longPressHelper(p: GeoPoint): Boolean {
                showCoordTypeDialog(p)
                return true
            }
        }))

        dzLat?.let  { lat -> dzLon?.let  { lon -> placeMarker(GeoPoint(lat, lon), isDz = true)  } }
        tgtLat?.let { lat -> tgtLon?.let { lon -> placeMarker(GeoPoint(lat, lon), isDz = false) } }

        val initialCenter = dzLat?.let { GeoPoint(it, dzLon!!) }
            ?: tgtLat?.let { GeoPoint(it, tgtLon!!) }

        if (initialCenter != null) {
            map.controller.setCenter(initialCenter)
        } else {
            centerOnCurrentLocation()
        }
    }

    private fun centerOnCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION),
                LOC_PERM_REQ
            )
            binding.mapView.controller.setZoom(3.0)
            binding.mapView.controller.setCenter(GeoPoint(0.0, 0.0))
            return
        }
        applyLastKnownLocation()
    }

    @Suppress("MissingPermission")
    private fun applyLastKnownLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val loc: Location? = runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        }.getOrNull()

        if (loc != null) {
            binding.mapView.controller.setZoom(14.0)
            binding.mapView.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
        } else {
            binding.mapView.controller.setZoom(3.0)
            binding.mapView.controller.setCenter(GeoPoint(0.0, 0.0))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOC_PERM_REQ && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            myLocationOverlay.enableMyLocation()
            applyLastKnownLocation()
        }
    }

    private fun showCoordTypeDialog(point: GeoPoint) {
        AlertDialog.Builder(this)
            .setTitle("Set coordinates")
            .setItems(arrayOf("Set as DZ coordinates", "Set as Target coordinates")) { _, which ->
                when (which) {
                    0 -> { dzLat  = point.latitude; dzLon  = point.longitude; placeMarker(point, isDz = true)  }
                    1 -> { tgtLat = point.latitude; tgtLon = point.longitude; placeMarker(point, isDz = false) }
                }
            }
            .show()
    }

    private fun placeMarker(point: GeoPoint, isDz: Boolean) {
        val map = binding.mapView
        if (isDz) {
            dzMarker?.let { map.overlays.remove(it) }
            dzMarker = Marker(map).apply {
                position = point
                title    = "DZ"
                snippet  = "%.6f, %.6f".format(point.latitude, point.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            map.overlays.add(dzMarker)
        } else {
            tgtMarker?.let { map.overlays.remove(it) }
            tgtMarker = Marker(map).apply {
                position = point
                title    = "Target"
                snippet  = "%.6f, %.6f".format(point.latitude, point.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            map.overlays.add(tgtMarker)
        }
        map.invalidate()
    }

    private fun returnResult() {
        val data = Intent().apply {
            putExtra(EXTRA_HAS_DZ, dzLat != null)
            dzLat?.let { putExtra(EXTRA_DZ_LAT, it) }
            dzLon?.let { putExtra(EXTRA_DZ_LON, it) }
            putExtra(EXTRA_HAS_TGT, tgtLat != null)
            tgtLat?.let { putExtra(EXTRA_TGT_LAT, it) }
            tgtLon?.let { putExtra(EXTRA_TGT_LON, it) }
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun makeLocationDot(sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c   = Canvas(bmp)
        val r   = sizePx / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        c.drawCircle(r, r, r, paint)
        paint.color = Color.rgb(0x22, 0x88, 0xFF)
        c.drawCircle(r, r, r * 0.70f, paint)
        return bmp
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            myLocationOverlay.enableMyLocation()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        myLocationOverlay.disableMyLocation()
    }
}
