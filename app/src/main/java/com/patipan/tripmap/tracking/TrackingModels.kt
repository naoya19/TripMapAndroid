package com.patipan.tripmap.tracking

import kotlin.math.*

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestamp: Long,
    val chainageMeters: Double
)

enum class ChainageDirection { LT, RT }

enum class VoiceMode { THAI, ENGLISH, OFF }

/** 3 แยก หรือ 4 แยก ตามแนวเส้นทาง */
enum class JunctionType { THREE_WAY, FOUR_WAY }

/** ด้านของแขนแยกเทียบทิศทางเดินทาง ณ จุดนั้น — BOTH ใช้กับ 4-way เสมอ */
enum class JunctionSide { LEFT, RIGHT, BOTH }

/** ที่มาของข้อมูลจุดแยก เผื่อภายหลังต้องตรวจสอบย้อนหลัง */
enum class JunctionSource { OSM, MANUAL }

data class JunctionPoint(
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val type: JunctionType,
    val side: JunctionSide,
    val source: JunctionSource = JunctionSource.MANUAL,
    val nearestChainageMeters: Double,
    val timestamp: Long = System.currentTimeMillis()
)

/** ระยะกม. ของจุดที่ใกล้ [latitude]/[longitude] ที่สุดในเส้นทางที่บันทึกไว้แล้ว */
fun nearestChainageMeters(points: List<TrackPoint>, latitude: Double, longitude: Double): Double {
    if (points.isEmpty()) return 0.0
    return points.minByOrNull { haversineMeters(it.latitude, it.longitude, latitude, longitude) }
        ?.chainageMeters ?: 0.0
}

fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val earth = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * earth * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

data class StartChainageConfig(
    val meters: Int = 0,
    val direction: ChainageDirection = ChainageDirection.LT
) {
    fun displayMeters(routeMeters: Double): Int =
        (meters + if (direction == ChainageDirection.LT) routeMeters else -routeMeters)
            .toInt().coerceAtLeast(0)
}

data class TrackingState(
    val isTracking: Boolean = false,
    val startedAt: Long? = null,
    val chainageMeters: Double = 0.0,
    val traveledDistanceMeters: Double = 0.0,
    val accuracyMeters: Float? = null,
    val speedKmh: Float = 0f,
    val points: List<TrackPoint> = emptyList(),
    val junctions: List<JunctionPoint> = emptyList(),
    val lastSpokenText: String? = null,
    val ttsStatus: String = "กำลังเตรียมเสียงภาษาไทย",
    val startChainage: StartChainageConfig = StartChainageConfig(),
    val voiceMode: VoiceMode = VoiceMode.THAI
)
