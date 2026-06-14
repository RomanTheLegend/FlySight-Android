package com.flysight.app.data

import kotlin.math.*

/**
 * Full post-processing pipeline for raw GPS DataPoints.
 * Mirrors mainwindow.cpp init() call sequence:
 *   initTime → initAltitude → initAcceleration → initExit → updateVelocity → initAerodynamics
 */
object DataProcessor {

    private const val A_GRAVITY   = 9.80665    // m/s²
    private const val SLOPE_HALF  = 4          // ±4 samples → 9-point window (mainwindow.cpp)

    // Aerodynamic defaults from mainwindow.cpp constructor
    private const val MASS_KG     = 70.0
    private const val PLANFORM_M2 = 2.0

    // Atmospheric constants for initAerodynamics
    private const val SL_PRESSURE = 101325.0   // Pa
    private const val SL_TEMP     = 288.15     // K
    private const val LAPSE_RATE  = 0.0065     // K/m
    private const val MM_AIR      = 0.0289644  // kg/mol
    private const val GAS_CONST   = 8.31446    // J/(mol·K)

    fun process(raw: List<DataPoint>): List<DataPoint> {
        if (raw.size < 2) return raw
        val ground = raw.last().hMSL                // Automatic ground reference (last point)
        var pts = initTime(raw)
        pts = initAltitude(pts, ground)
        pts = initAcceleration(pts)
        pts = initExit(pts)
        pts = updateVelocity(pts)
        pts = initAerodynamics(pts)
        return pts
    }

    // ── initTime ──────────────────────────────────────────────────────────────
    // t = (dateTimeMs – firstMs) / 1000.0

    private fun initTime(pts: List<DataPoint>): List<DataPoint> {
        val startMs = pts.first().dateTimeMs
        return pts.map { it.copy(t = (it.dateTimeMs - startMs) / 1000.0) }
    }

    // ── initAltitude ──────────────────────────────────────────────────────────
    // z = hMSL – ground

    private fun initAltitude(pts: List<DataPoint>, ground: Double): List<DataPoint> =
        pts.map { it.copy(z = it.hMSL - ground) }

    // ── initAcceleration ─────────────────────────────────────────────────────
    // Least-squares slopes of velN, velE, velD → ax, ay (body-frame), az, amag
    // Mirrors mainwindow.cpp initAcceleration() using northSpeedRaw / eastSpeedRaw

    private fun initAcceleration(pts: List<DataPoint>): List<DataPoint> =
        pts.mapIndexed { i, dp ->
            val accelN = slope(pts, i) { it.velN }
            val accelE = slope(pts, i) { it.velE }
            val azVal  = slope(pts, i) { it.velD }
            val vh = sqrt(dp.velN * dp.velN + dp.velE * dp.velE)
            val ax: Double; val ay: Double
            if (vh > 1e-6) {
                ax = (accelN * dp.velN + accelE * dp.velE) / vh
                ay = (accelE * dp.velN - accelN * dp.velE) / vh
            } else {
                ax = 0.0; ay = 0.0
            }
            dp.copy(
                ax   = ax,
                ay   = ay,
                az   = azVal,
                amag = sqrt(accelN * accelN + accelE * accelE + azVal * azVal)
            )
        }

    // ── initExit ─────────────────────────────────────────────────────────────
    // Find when velD crosses A_GRAVITY, check vAcc < 10 and az ≥ A_GRAVITY/5.
    // Re-assign t so that t=0 is the exit moment.
    // Mirrors mainwindow.cpp initExit().

    private fun initExit(pts: List<DataPoint>): List<DataPoint> {
        var exitMs = pts.first().dateTimeMs.toDouble()

        for (i in 1 until pts.size) {
            val p1 = pts[i - 1]; val p2 = pts[i]
            val dv = p2.velD - p1.velD
            if (dv == 0.0) continue
            val a = (A_GRAVITY - p1.velD) / dv
            if (a < 0.0 || a > 1.0) continue
            val vAcc = p1.vAcc + a * (p2.vAcc - p1.vAcc)
            if (vAcc > 10.0) continue
            val az = p1.az + a * (p2.az - p1.az)
            if (az < A_GRAVITY / 5.0) continue
            // Back-extrapolate to t=0 vertical speed: exit = crossing – velD/az
            exitMs = p1.dateTimeMs + a * (p2.dateTimeMs - p1.dateTimeMs) -
                     A_GRAVITY / az * 1000.0
            break
        }

        return pts.map { it.copy(t = (it.dateTimeMs - exitMs) / 1000.0) }
    }

    // ── updateVelocity ───────────────────────────────────────────────────────
    // Computes x, y, dist2D, dist3D, vx, vy, heading, cAcc, theta, curv, accel, omega.
    // Mirrors mainwindow.cpp updateVelocity() (no wind adjustment).

    private fun updateVelocity(pts: List<DataPoint>): List<DataPoint> {
        val dp0 = interpolateAt(pts, 0.0)   // exit point

        // Pass 1: x, y relative to exit; cumulative dist2D, dist3D; vx = velE, vy = velN
        var dist2D = 0.0; var dist3D = 0.0
        val pass1 = ArrayList<DataPoint>(pts.size)
        for (i in pts.indices) {
            val dp = pts[i]
            val dist = haversine(dp0.lat, dp0.lon, dp.lat, dp.lon)
            val bear = bearing(dp0.lat, dp0.lon, dp.lat, dp.lon)
            if (i > 0) {
                val prev = pts[i - 1]
                val dh = haversine(prev.lat, prev.lon, dp.lat, dp.lon)
                val dz = abs(dp.hMSL - prev.hMSL)
                dist2D += dh; dist3D += sqrt(dh * dh + dz * dz)
            }
            pass1.add(dp.copy(
                x = dist * sin(bear), y = dist * cos(bear),
                dist2D = dist2D, dist3D = dist3D,
                vx = dp.velE, vy = dp.velN
            ))
        }

        // Subtract exit-point cumulative values so dist2D/dist3D = 0 at exit
        val exitDp = interpolateAt(pass1, 0.0)

        // Pass 2: heading (cumulative, no 360 wrap), cAcc, theta; adjust distances
        var prevHeading = Double.NaN
        val pass2 = ArrayList<DataPoint>(pts.size)
        for (dp in pass1) {
            val rawH = Math.toDegrees(atan2(dp.vx, dp.vy))
            val h = when {
                prevHeading.isNaN()          -> rawH
                rawH < prevHeading - 180.0   -> rawH + 360.0
                rawH >= prevHeading + 180.0  -> rawH - 360.0
                else                         -> rawH
            }
            prevHeading = h
            val velMs = sqrt(dp.vx * dp.vx + dp.vy * dp.vy + dp.velD * dp.velD)
            pass2.add(dp.copy(
                x      = dp.x      - exitDp.x,
                y      = dp.y      - exitDp.y,
                dist2D = dp.dist2D - exitDp.dist2D,
                dist3D = dp.dist3D - exitDp.dist3D,
                heading = h,
                cAcc   = if (velMs > 1e-6) dp.sAcc / velMs else 0.0,
                theta  = h   // theta0 = 0 (no course override)
            ))
        }

        // Pass 3: slopes that require the full pass2 list
        return pass2.mapIndexed { i, dp ->
            dp.copy(
                curv  = slope(pass2, i) { it.diveAngle },
                accel = slope(pass2, i) { it.totalSpeed },
                omega = slope(pass2, i) { it.course }
            )
        }
    }

    // ── initAerodynamics ─────────────────────────────────────────────────────
    // Mirrors mainwindow.cpp initAerodynamics().
    // Uses wind-adjusted vy/vx for slope computation; without wind vx=velE, vy=velN.

    private fun initAerodynamics(pts: List<DataPoint>): List<DataPoint> =
        pts.mapIndexed { i, dp ->
            val accelN = slope(pts, i) { it.vy }
            val accelE = slope(pts, i) { it.vx }
            var accelD = slope(pts, i) { it.velD }
            accelD -= A_GRAVITY                     // subtract gravity

            val vel = sqrt(dp.vx * dp.vx + dp.vy * dp.vy + dp.velD * dp.velD)
            if (vel < 1e-6) return@mapIndexed dp

            // Project acceleration onto velocity direction → drag component
            val proj  = (accelN * dp.vy + accelE * dp.vx + accelD * dp.velD) / vel
            val dragN = proj * dp.vy  / vel
            val dragE = proj * dp.vx  / vel
            val dragD = proj * dp.velD / vel

            val accelDrag = sqrt(dragN * dragN + dragE * dragE + dragD * dragD)
            val accelLift = sqrt(
                (accelN - dragN).pow(2) + (accelE - dragE).pow(2) + (accelD - dragD).pow(2)
            )

            // Standard atmosphere (ISA)
            val airPressure = SL_PRESSURE *
                (1.0 - LAPSE_RATE * dp.hMSL / SL_TEMP)
                    .pow(A_GRAVITY * MM_AIR / GAS_CONST / LAPSE_RATE)
            val temperature  = SL_TEMP - LAPSE_RATE * dp.hMSL
            val airDensity   = airPressure / (GAS_CONST / MM_AIR) / temperature
            val dynPressure  = airDensity * vel * vel / 2.0

            if (dynPressure <= 0.0) return@mapIndexed dp
            dp.copy(
                lift = MASS_KG * accelLift / dynPressure / PLANFORM_M2,
                drag = MASS_KG * accelDrag / dynPressure / PLANFORM_M2
            )
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Least-squares slope of valueFunc over a ±4 sample window around [center].
     * Direct port of MainWindow::getSlope() from mainwindow.cpp.
     */
    internal fun slope(pts: List<DataPoint>, center: Int, valueFunc: (DataPoint) -> Double): Double {
        val iMin = maxOf(0, center - SLOPE_HALF)
        val iMax = minOf(pts.lastIndex, center + SLOPE_HALF)
        var sumx = 0.0; var sumy = 0.0; var sumxx = 0.0; var sumxy = 0.0
        for (i in iMin..iMax) {
            val dp = pts[i]; val y = valueFunc(dp)
            sumx += dp.t; sumy += y; sumxx += dp.t * dp.t; sumxy += dp.t * y
        }
        val n = (iMax - iMin + 1).toDouble()
        val denom = sumxx - sumx * sumx / n
        return if (abs(denom) < 1e-10) 0.0 else (sumxy - sumx * sumy / n) / denom
    }

    /** Haversine great-circle distance in meters (replaces GeographicLib Geodesic) */
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R    = 6371009.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a    = sin(dLat / 2).pow(2) +
                   cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }

    /** Forward azimuth in radians from (lat1,lon1) to (lat2,lon2) */
    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon  = Math.toRadians(lon2 - lon1)
        val lat1R = Math.toRadians(lat1); val lat2R = Math.toRadians(lat2)
        return atan2(
            sin(dLon) * cos(lat2R),
            cos(lat1R) * sin(lat2R) - sin(lat1R) * cos(lat2R) * cos(dLon)
        )
    }

    /**
     * Linear interpolation of a DataPoint at time [t].
     * Mirrors MainWindow::interpolateDataT() from mainwindow.cpp.
     */
    internal fun interpolateAt(pts: List<DataPoint>, t: Double): DataPoint {
        val i1 = pts.indexOfLast  { it.t <= t }
        val i2 = pts.indexOfFirst { it.t >  t }
        if (i1 < 0) return pts.first()
        if (i2 < 0) return pts.last()
        val p1 = pts[i1]; val p2 = pts[i2]
        val a = if (p2.t != p1.t) (t - p1.t) / (p2.t - p1.t) else 0.0
        return DataPoint(
            dateTimeMs = (p1.dateTimeMs + a * (p2.dateTimeMs - p1.dateTimeMs)).toLong(),
            lat    = p1.lat    + a * (p2.lat    - p1.lat),
            lon    = p1.lon    + a * (p2.lon    - p1.lon),
            hMSL   = p1.hMSL   + a * (p2.hMSL   - p1.hMSL),
            velN   = p1.velN   + a * (p2.velN   - p1.velN),
            velE   = p1.velE   + a * (p2.velE   - p1.velE),
            velD   = p1.velD   + a * (p2.velD   - p1.velD),
            hAcc   = p1.hAcc   + a * (p2.hAcc   - p1.hAcc),
            vAcc   = p1.vAcc   + a * (p2.vAcc   - p1.vAcc),
            sAcc   = p1.sAcc   + a * (p2.sAcc   - p1.sAcc),
            numSV  = if (a < 0.5) p1.numSV else p2.numSV,
            t      = t,
            x      = p1.x      + a * (p2.x      - p1.x),
            y      = p1.y      + a * (p2.y      - p1.y),
            z      = p1.z      + a * (p2.z      - p1.z),
            dist2D = p1.dist2D + a * (p2.dist2D  - p1.dist2D),
            dist3D = p1.dist3D + a * (p2.dist3D  - p1.dist3D),
            vx     = p1.vx     + a * (p2.vx     - p1.vx),
            vy     = p1.vy     + a * (p2.vy     - p1.vy)
        )
    }
}
