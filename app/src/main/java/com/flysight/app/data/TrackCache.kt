package com.flysight.app.data

/** Transient in-memory cache for passing already-parsed DataPoints between activities. */
object TrackCache {
    var points: List<DataPoint>? = null
}
