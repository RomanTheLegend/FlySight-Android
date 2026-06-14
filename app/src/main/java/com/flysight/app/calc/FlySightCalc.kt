package com.flysight.app.calc

import com.flysight.app.DataPoint
import com.flysight.app.DataProcessor
import kotlin.math.*

object FlySightCalc {

    private const val A_GRAVITY      = 9.80665  // m/s²
    private const val SCORE_ALT_A_M  = 2500.0   // competition gate A, meters AGL
    private const val SCORE_ALT_B_M  = 1500.0   // competition gate B, meters AGL

    data class TargetCoordinates(val lat: Double, val lon: Double)

    data class JumpScore(
        val timeSec:   Double,
        val distanceM: Double,
        val speedKmh:  Double
    )

    // ── Exit detection ────────────────────────────────────────────────────────

    /**
     * Returns the index of the point just before velD first crosses A_GRAVITY,
     * subject to vAcc < 10 m and az ≥ A_GRAVITY/5 quality checks.
     * Returns -1 if no valid exit is found.
     *
     * Direct port of the exit-scan loop in mainwindow.cpp initExit().
     * Note: DataProcessor already bakes t=0 at the exit; call this only when you
     * need the raw index (e.g., to slice pre-/post-exit sub-lists).
     */
    fun detectExit(points: List<DataPoint>): Int {
        for (i in 1 until points.size) {
            val p1 = points[i - 1]; val p2 = points[i]
            val dv = p2.velD - p1.velD
            if (dv == 0.0) continue
            val a = (A_GRAVITY - p1.velD) / dv
            if (a < 0.0 || a > 1.0) continue
            val vAcc = p1.vAcc + a * (p2.vAcc - p1.vAcc)
            if (vAcc > 10.0) continue
            val az = p1.az + a * (p2.az - p1.az)
            if (az < A_GRAVITY / 5.0) continue
            return i - 1
        }
        return -1
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    /**
     * Scores a competition jump between 2500 m and 1500 m AGL.
     *
     * Ported from activelook_mode1.c COMPETITION_RUN score capture:
     *   - Point A = interpolated crossing at 2500 m AGL (descending)
     *   - Point B = interpolated crossing at 1500 m AGL (descending)
     *   - Time     = t(B) − t(A)
     *   - Distance = straight-line distance A→B using x,y coordinates
     *   - Speed    = Distance / Time (km/h)
     *
     * [points] must already be processed by DataProcessor (x, y, t fields populated).
     * Returns null if either altitude gate is not crossed.
     */
    fun scoreJump(points: List<DataPoint>, dzElevM: Double): JumpScore? {
        var ptA: DataPoint? = null

        for (i in 1 until points.size) {
            val p1 = points[i - 1]; val p2 = points[i]
            val agl1 = p1.hMSL - dzElevM
            val agl2 = p2.hMSL - dzElevM

            if (ptA == null && agl1 >= SCORE_ALT_A_M && agl2 < SCORE_ALT_A_M) {
                ptA = interpolateCrossing(p1, p2, SCORE_ALT_A_M + dzElevM)
            }

            if (ptA != null && agl1 >= SCORE_ALT_B_M && agl2 < SCORE_ALT_B_M) {
                val ptB    = interpolateCrossing(p1, p2, SCORE_ALT_B_M + dzElevM)
                val time   = ptB.t - ptA.t
                if (time <= 0.0) return null
                val dist   = sqrt((ptB.x - ptA.x).pow(2) + (ptB.y - ptA.y).pow(2))
                return JumpScore(time, dist, dist / time * 3.6)
            }
        }
        return null
    }

    // ── Distances ─────────────────────────────────────────────────────────────

    /** Cumulative 3D distance over the point range (meters). Uses pre-computed dist3D. */
    fun totalDistance(points: List<DataPoint>): Double {
        if (points.size < 2) return 0.0
        return points.last().dist3D - points.first().dist3D
    }

    /** Cumulative horizontal distance over the point range (meters). Uses pre-computed dist2D. */
    fun horizontalDistance(points: List<DataPoint>): Double {
        if (points.size < 2) return 0.0
        return points.last().dist2D - points.first().dist2D
    }

    /** Net altitude loss (positive = descended). */
    fun verticalDistance(points: List<DataPoint>): Double {
        if (points.isEmpty()) return 0.0
        return points.first().hMSL - points.last().hMSL
    }

    // ── Speed ─────────────────────────────────────────────────────────────────

    fun maxHorizontalSpeed(points: List<DataPoint>): Double =
        points.maxOfOrNull { it.hSpeed } ?: 0.0

    fun maxVerticalSpeed(points: List<DataPoint>): Double =
        points.maxOfOrNull { it.vSpeed } ?: 0.0

    fun maxTotalSpeed(points: List<DataPoint>): Double =
        points.maxOfOrNull { it.totalSpeed } ?: 0.0

    // ── Glide ─────────────────────────────────────────────────────────────────

    fun bestGlideRatio(points: List<DataPoint>): Double =
        points.maxOfOrNull { it.glideRatio } ?: 0.0

    // ── Lane ─────────────────────────────────────────────────────────────────

    /**
     * Returns the lane lock-in time (seconds, relative to exit):
     * 9 seconds after vertical speed (velD) first crosses 10 m/s descending.
     * Interpolates between adjacent points for sub-sample precision.
     * Returns null if 10 m/s is never reached in the track.
     */
    fun laneLockInTime(points: List<DataPoint>): Double? {
        for (i in 1 until points.size) {
            val p1 = points[i - 1]; val p2 = points[i]
            if (p1.velD < 10.0 && p2.velD >= 10.0) {
                val a = (10.0 - p1.velD) / (p2.velD - p1.velD)
                return p1.t + a * (p2.t - p1.t) + 9.0
            }
        }
        return null
    }

    /**
     * Projects a point [extDistM] meters beyond [tgtLat/Lon] along the
     * [startLat/Lon] → [tgtLat/Lon] great-circle bearing.
     *
     * Port of ExtendLane() from activelook_mode1.c.
     * Default extension is 5 km (FREEFALL_LOCK_TIME_MS lane definition).
     */
    fun extendLane(
        startLat: Double, startLon: Double,
        tgtLat:   Double, tgtLon:   Double,
        extDistM: Double = 5000.0
    ): TargetCoordinates {
        val R    = 6371100.0
        val latA = Math.toRadians(startLat); val lonA = Math.toRadians(startLon)
        val latB = Math.toRadians(tgtLat);   val lonB = Math.toRadians(tgtLon)

        // Spherical bearing from start → target
        val dLon    = lonB - lonA
        val y       = sin(dLon) * cos(latB)
        val x       = cos(latA) * sin(latB) - sin(latA) * cos(latB) * cos(dLon)
        val bearing = atan2(y, x)

        // Destination-point formula: extend extDistM past target along same bearing
        val dr   = extDistM / R
        val latC = asin(sin(latB) * cos(dr) + cos(latB) * sin(dr) * cos(bearing))
        val lonC = lonB + atan2(
            sin(bearing) * sin(dr) * cos(latB),
            cos(dr) - sin(latB) * sin(latC)
        )

        return TargetCoordinates(Math.toDegrees(latC), Math.toDegrees(lonC))
    }

    /**
     * Signed cross-track deviation from the lane line in meters.
     * Positive = right of lane (pilot must steer left).
     * Negative = left of lane (pilot must steer right).
     *
     * [laneLat/Lon]    = lane lock-in point (10 s after exit).
     * [laneExtLat/Lon] = extended endpoint returned by [extendLane].
     * [curLat/Lon]     = current GNSS position.
     *
     * Port of CrossTrackMeters() + BearingDeg() from activelook_mode1.c.
     */
    fun crossTrackDeviationM(
        laneLat:    Double, laneLon:    Double,
        laneExtLat: Double, laneExtLon: Double,
        curLat:     Double, curLon:     Double
    ): Double {
        val bToExt = bearingEquiDeg(laneLat, laneLon, laneExtLat, laneExtLon)
        val bToCur = bearingEquiDeg(laneLat, laneLon, curLat,     curLon)
        val dist   = haversineM(laneLat, laneLon, curLat, curLon)
        return dist * sin(Math.toRadians(bToCur - bToExt))
    }

    // ── Penalties ─────────────────────────────────────────────────────────────

    /**
     * Returns the penalty percentage (0, 10, 20, or 50) for a lane violation.
     *
     * Window for 10 % / 20 % tiers: t = 10.0 s after exit → 1500 m AGL crossing.
     * Window for 50 % tier:          t = 10.0 s after exit → end of track
     *                                 (proxy for parachute deployment).
     *
     * Cross-track deviation is unsigned distance from the lane centreline.
     *   < 150 m at any point inside the competition window → 10 %
     *   150 – 300 m inside the competition window         → 20 %
     *   > 300 m anywhere from 10 s to end of track        → 50 %
     */
    fun penaltyPercent(
        points:     List<DataPoint>,
        lockInLat:  Double, lockInLon:  Double,
        laneExtLat: Double, laneExtLon: Double,
        dzElevM:    Double
    ): Int {
        val tWindowEnd = gateAGLTime(points, dzElevM, 1500.0)

        var maxDevWindow    = 0.0
        var maxDevFullTrack = 0.0

        for (p in points) {
            if (p.t < 10.0) continue
            val dev = Math.abs(crossTrackDeviationM(
                lockInLat, lockInLon, laneExtLat, laneExtLon, p.lat, p.lon))
            if (tWindowEnd == null || p.t <= tWindowEnd) {
                if (dev > maxDevWindow) maxDevWindow = dev
            }
            if (dev > maxDevFullTrack) maxDevFullTrack = dev
        }

        return when {
            maxDevFullTrack > 300.0  -> 50
            maxDevWindow   >= 150.0  -> 20
            maxDevWindow   > 0.0     -> 10
            else                     -> 0
        }
    }

    /** Interpolated time (relative to exit) when hMSL descends through [gateAGL] AGL. */
    private fun gateAGLTime(points: List<DataPoint>, dzElevM: Double, gateAGL: Double): Double? {
        for (i in 1 until points.size) {
            val p1 = points[i - 1]; val p2 = points[i]
            if (p1.hMSL - dzElevM >= gateAGL && p2.hMSL - dzElevM < gateAGL) {
                val span = p1.hMSL - p2.hMSL
                val a    = if (span != 0.0) (p1.hMSL - (gateAGL + dzElevM)) / span else 0.0
                return p1.t + a * (p2.t - p1.t)
            }
        }
        return null
    }

    // ── Comparison ────────────────────────────────────────────────────────────

    fun compareTracks(a: List<DataPoint>, b: List<DataPoint>): TrackComparison =
        TrackComparison(
            deltaDistance = horizontalDistance(a) - horizontalDistance(b),
            deltaMaxSpeed = maxTotalSpeed(a) - maxTotalSpeed(b),
            deltaScore    = 0.0     // use scoreJump() directly; requires dzElevM
        )

    data class TrackComparison(
        val deltaDistance: Double,
        val deltaMaxSpeed: Double,
        val deltaScore:    Double
    )

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Linear interpolation of the DataPoint at which hMSL == targetHMSL. */
    private fun interpolateCrossing(p1: DataPoint, p2: DataPoint, targetHMSL: Double): DataPoint {
        val span = p1.hMSL - p2.hMSL
        val a    = if (span != 0.0) (p1.hMSL - targetHMSL) / span else 0.0
        return DataProcessor.interpolateAt(listOf(p1, p2), p1.t + a * (p2.t - p1.t))
    }

    /**
     * Equirectangular bearing in degrees from (lat1,lon1) to (lat2,lon2).
     * Port of BearingDeg() from activelook_mode1.c.
     * Uses a flat-earth approximation; accurate for the short inter-point
     * distances encountered in competition scoring.
     */
    private fun bearingEquiDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val dlat    = lat2 - lat1
        val dlon    = (lon2 - lon1) * cos(lat1Rad)
        return Math.toDegrees(atan2(dlon, dlat))
    }

    /** Haversine great-circle distance in meters. */
    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R    = 6371009.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a    = sin(dLat / 2).pow(2) +
                   cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }
}
