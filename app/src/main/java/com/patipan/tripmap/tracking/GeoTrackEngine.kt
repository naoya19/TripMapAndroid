package com.patipan.tripmap.tracking

import kotlin.math.*

data class GeoPoint(val lat: Double, val lng: Double)
data class EngineUpdate(val chainageMeters: Double, val traveledMeters: Double, val accepted: Boolean)

class GeoTrackEngine {
    private val route = mutableListOf<GeoPoint>()
    private val cumulative = mutableListOf<Double>()
    private var chainage = 0.0
    private var traveled = 0.0
    private var lastRaw: GeoPoint? = null
    private var reverseVotes = 0

    fun reset() {
        route.clear(); cumulative.clear(); chainage = 0.0; traveled = 0.0
        lastRaw = null; reverseVotes = 0
    }

    fun add(point: GeoPoint, accuracyMeters: Float): EngineUpdate {
        if (accuracyMeters > 20f) return EngineUpdate(chainage, traveled, false)
        val previousRaw = lastRaw
        if (previousRaw != null) {
            val movement = distance(previousRaw, point)
            if (movement < max(2.5, accuracyMeters.toDouble() * 0.20)) {
                return EngineUpdate(chainage, traveled, false)
            }
            traveled += movement
        }
        lastRaw = point

        if (route.isEmpty()) {
            route += point; cumulative += 0.0
            return EngineUpdate(chainage, traveled, true)
        }
        if (route.size == 1) {
            append(point)
            chainage = cumulative.last()
            return EngineUpdate(chainage, traveled, true)
        }

        val projection = closestProjection(point)
        val endDistance = distance(route.last(), point)
        val gate = max(12.0, accuracyMeters.toDouble() * 1.5)
        val nearRoute = projection.crossTrackMeters <= gate
        val behindEnd = cumulative.last() - projection.alongMeters

        when {
            nearRoute && behindEnd > 6.0 -> {
                reverseVotes++
                if (reverseVotes >= 2) chainage = projection.alongMeters.coerceAtLeast(0.0)
            }
            nearRoute && behindEnd <= 8.0 && endDistance >= 2.5 -> {
                reverseVotes = 0
                append(point)
                chainage = cumulative.last()
            }
            nearRoute -> {
                reverseVotes = 0
                chainage = projection.alongMeters.coerceIn(0.0, cumulative.last())
            }
            endDistance >= gate -> {
                reverseVotes = 0
                append(point)
                chainage = cumulative.last()
            }
        }
        return EngineUpdate(chainage, traveled, true)
    }

    private fun append(point: GeoPoint) {
        val next = cumulative.last() + distance(route.last(), point)
        route += point; cumulative += next
    }

    private data class Projection(val alongMeters: Double, val crossTrackMeters: Double)

    private fun closestProjection(point: GeoPoint): Projection {
        var best = Projection(0.0, Double.MAX_VALUE)
        for (i in 0 until route.lastIndex) {
            val a = route[i]; val b = route[i + 1]
            val refLat = Math.toRadians((a.lat + b.lat + point.lat) / 3.0)
            fun xy(p: GeoPoint): Pair<Double, Double> = Pair(
                Math.toRadians(p.lng - a.lng) * EARTH * cos(refLat),
                Math.toRadians(p.lat - a.lat) * EARTH
            )
            val (bx, by) = xy(b); val (px, py) = xy(point)
            val denominator = bx * bx + by * by
            val t = if (denominator == 0.0) 0.0 else ((px * bx + py * by) / denominator).coerceIn(0.0, 1.0)
            val dx = px - bx * t; val dy = py - by * t
            val cross = hypot(dx, dy)
            if (cross < best.crossTrackMeters) {
                best = Projection(cumulative[i] + distance(a, b) * t, cross)
            }
        }
        return best
    }

    companion object {
        private const val EARTH = 6_371_000.0
        fun distance(a: GeoPoint, b: GeoPoint): Double {
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLng = Math.toRadians(b.lng - a.lng)
            val lat1 = Math.toRadians(a.lat); val lat2 = Math.toRadians(b.lat)
            val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
            return 2 * EARTH * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
