package com.flysight.app

import kotlin.math.*

/**
 * Mirrors DataPoint from datapoint.cpp / mainwindow.cpp.
 * Raw GPS fields are populated by the CSV parser; all other fields are
 * computed by DataProcessor before the list is handed to the UI or FlySightCalc.
 */
data class DataPoint(
    // ── Raw GPS fields ────────────────────────────────────────────────────────
    val dateTimeMs: Long   = 0L,
    val lat:        Double = 0.0,   // degrees
    val lon:        Double = 0.0,   // degrees
    val hMSL:       Double = 0.0,   // meters above MSL
    val velN:       Double = 0.0,   // m/s northward
    val velE:       Double = 0.0,   // m/s eastward
    val velD:       Double = 0.0,   // m/s downward (positive = descending)
    val hAcc:       Double = 0.0,   // horizontal accuracy, m
    val vAcc:       Double = 0.0,   // vertical accuracy, m
    val sAcc:       Double = 0.0,   // speed accuracy, m/s
    val numSV:      Int    = 0,

    // ── Time ──────────────────────────────────────────────────────────────────
    val t: Double = 0.0,            // seconds relative to exit (negative = before exit)

    // ── Cartesian position (meters from exit point) ───────────────────────────
    val x: Double = 0.0,            // east
    val y: Double = 0.0,            // north
    val z: Double = 0.0,            // altitude above ground

    // ── Cumulative distances (meters, zeroed at exit) ─────────────────────────
    val dist2D: Double = 0.0,
    val dist3D: Double = 0.0,

    // ── Wind-adjusted velocity (m/s); equals velE/velN without wind ───────────
    val vx: Double = 0.0,           // eastward
    val vy: Double = 0.0,           // northward

    // ── Acceleration in body frame (m/s²) — least-squares slope, 9-pt window ─
    val ax:   Double = 0.0,         // along-track (forward)
    val ay:   Double = 0.0,         // cross-track (sideways)
    val az:   Double = 0.0,         // vertical (slope of velD)
    val amag: Double = 0.0,         // total magnitude

    // ── Aerodynamics ──────────────────────────────────────────────────────────
    val lift: Double = 0.0,
    val drag: Double = 0.0,

    // ── Heading ───────────────────────────────────────────────────────────────
    val heading: Double = 0.0,      // degrees, cumulative (no 360 wrap)
    val cAcc:    Double = 0.0,      // heading accuracy, degrees
    val theta:   Double = 0.0,      // heading relative to course
    val omega:   Double = 0.0,      // heading rate, degrees/s

    // ── Dynamics ──────────────────────────────────────────────────────────────
    val curv:  Double = 0.0,        // slope of diveAngle
    val accel: Double = 0.0         // slope of totalSpeed
) {
    // ── Computed properties (match DataPoint static helpers in mainwindow.cpp) ─

    /** Horizontal speed, km/h */
    val hSpeed: Double get() = sqrt(velN * velN + velE * velE) * 3.6

    /** Vertical speed, km/h */
    val vSpeed: Double get() = velD * 3.6

    /** Total speed using wind-adjusted vx/vy, km/h */
    val totalSpeed: Double get() = sqrt(vx * vx + vy * vy + velD * velD) * 3.6

    /** Dive angle: 0° = horizontal, 90° = vertical */
    val diveAngle: Double get() = Math.toDegrees(atan2(velD, sqrt(velN * velN + velE * velE)))

    /** Glide ratio (horizontal / vertical, dimensionless) */
    val glideRatio: Double get() = if (velD > 1e-6) sqrt(velN * velN + velE * velE) / velD else 0.0

    /** Course bearing from wind-adjusted velocity, degrees */
    val course: Double get() = Math.toDegrees(atan2(vx, vy))
}
