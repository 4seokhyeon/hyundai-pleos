package com.shsh.pleos.playground.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Device last-known fix, used locally; not sent to the public-data APIs. */
data class FacilityReference(val latitude: Double, val longitude: Double, val measuredAtMillis: Long) {
    fun distanceMetersTo(lat: Double?, lon: Double?): Double? {
        if (!validFacilityCoordinates(latitude, longitude) || !validFacilityCoordinates(lat, lon)) return null
        val toRadians = Math.PI / 180.0
        val dLat = (lat!! - latitude) * toRadians
        val dLon = (lon!! - longitude) * toRadians
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(latitude * toRadians) * cos(lat * toRadians) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6_371_000.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

fun validFacilityCoordinates(lat: Double?, lon: Double?): Boolean = lat != null && lon != null && lat.isFinite() && lon.isFinite()
    && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

data class FacilityDistance<T>(val facility: T, val meters: Double?)
