package com.patipan.tripmap.tracking

import com.patipan.tripmap.data.SurfaceKind
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

/** 3 แยก, 4 แยก, T หรือกลับซ้าย — ขยายจากเดิมเพื่อรองรับงานสำรวจ */
enum class JunctionType { THREE_WAY, FOUR_WAY, T, LEFT_TURN }

/** ด้านของแขนแยกเทียบทิศทางเดินทาง ณ จุดนั้น — BOTH ใช้กับ 4-way เสมอ */
enum class JunctionSide { LEFT, RIGHT, BOTH }

/** ที่มาของข้อมูลจุดแยก เผื่อภายหลังต้องตรวจสอบย้อนหลัง */
enum class JunctionSource { OSM, MANUAL }

fun JunctionType.label() = when (this) {
    JunctionType.THREE_WAY -> "3 แยก"
    JunctionType.FOUR_WAY -> "4 แยก"
    JunctionType.T -> "ทางแยกรูปตัวที"
    JunctionType.LEFT_TURN -> "กลับซ้าย"
}

data class JunctionPoint(
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val type: JunctionType,
    val side: JunctionSide,
    val source: JunctionSource = JunctionSource.MANUAL,
    val nearestChainageMeters: Double,
    val timestamp: Long = System.currentTimeMillis(),
    // ── v1.5 ──
    val note: String = "",
    /** null = ยังไม่ได้วัดเฉพาะจุดนี้ → ใช้ค่าของช่วงถนนที่ครอบคลุมแทน */
    val lanes: Int? = null,
    val widthMeters: Double? = null,
    val shoulderMeters: Double? = null,
    val surface: String = SurfaceKind.UNKNOWN,
    val photoRef: String? = null
)

/** ช่วงของถนน — ข้อมูลความกว้าง/เลน/ไหล่ทาง/ผิว ที่ใช้ตลอดช่วง ไม่ใช่เฉพาะจุด
 *
 *  อ้างอิงด้วยระยะตามเส้นทาง (chainageMeters) ไม่ใช่พิกัด lat/lon เพราะช่วงต้องอยู่บนเส้นทาง
 *  และทริปถัดไปอาจเริ่ม กม. ไม่เหมือนเดิม — อ้างด้วยระยะจึงตัดช่วงตอน export ได้ตรงเสมอ
 */
data class RoadSegment(
    val id: Long = 0,
    val name: String = "",
    val startChainageMeters: Int,
    val endChainageMeters: Int,
    val lanes: Int = 2,
    val widthMeters: Double? = null,
    val shoulderMeters: Double? = null,
    val surface: String = SurfaceKind.UNKNOWN,
    val note: String = "",
    val colorIndex: Int = 5,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val lengthMeters: Int get() = (endChainageMeters - startChainageMeters).coerceAtLeast(0)
    val widthTotalMeters: Double? get() = attributes().totalWidthMeters

    fun contains(routeMeters: Double) =
        routeMeters >= startChainageMeters && routeMeters <= endChainageMeters

    fun attributes() = RoadAttributes(
        lanes = lanes,
        widthMeters = widthMeters,
        shoulderMeters = shoulderMeters,
        surface = surface,
        segmentName = name.ifBlank { null }
    )
}

/** ค่าถนนที่รวมแล้ว — ใช้ทั้งกับช่วงถนนและจุดทางแยก */
data class RoadAttributes(
    val lanes: Int? = null,
    val widthMeters: Double? = null,
    val shoulderMeters: Double? = null,
    val surface: String = SurfaceKind.UNKNOWN,
    val segmentName: String? = null
) {
    val isEmpty: Boolean
        get() = lanes == null && widthMeters == null && shoulderMeters == null &&
                surface == SurfaceKind.UNKNOWN

    /** ความกว้างรวมช่องทางเดินรถ + ไหล่ทางสองข้าง
     *
     *  ถ้าไม่ได้วัดความกว้างจริง จะประมาณจากจำนวนเลน × 3.5 ม. (มาตรฐานถนนชั้นที่ 2 ของไทย)
     *  ผลลัพธ์ยังใช้เป็นค่าประมาณการแนว DXF ได้ แต่ควรกรอกความกว้างจริงถ้าตรวจวัดแล้ว
     */
    val totalWidthMeters: Double?
        get() {
            val base = widthMeters ?: lanes?.let { it * 3.5 }
            return base?.let { it + (shoulderMeters ?: 0.0) * 2 }
        }

    fun summary(): String = buildList {
        lanes?.let { add("$it เลน") }
        widthMeters?.let { add("กว้าง ${trimNum(it)} ม.") }
            ?: lanes?.let { add("≈${trimNum(it * 3.5)} ม.") }
        shoulderMeters?.takeIf { it > 0 }?.let { add("ไหล่ทาง ${trimNum(it)} ม.") }
        if (surface != SurfaceKind.UNKNOWN) add(SurfaceKind.label(surface))
    }.joinToString(" · ").ifBlank { "ยังไม่มีข้อมูล" }

    private fun trimNum(v: Double) =
        if (v % 1.0 == 0.0) v.toInt().toString() else String.format("%.1f", v)
}

// ── การหาช่วง ──────────────────────────────────────────────

fun List<RoadSegment>.at(routeMeters: Double): RoadSegment? =
    firstOrNull { it.contains(routeMeters) }

/** ช่วงที่ทับซ้อนกับ [start]–[end] — ใช้เตือนผู้ใช้ก่อนบันทึก */
fun List<RoadSegment>.conflictsWith(
    start: Int, end: Int, excludeId: Long = 0L
): List<RoadSegment> = filter {
    it.id != excludeId && it.startChainageMeters <= end && it.endChainageMeters >= start
}

fun List<RoadSegment>.sortedByRange(): List<RoadSegment> =
    sortedBy { it.startChainageMeters }

/** ค่าถนนที่ใช้จริง ณ จุดทางแยก — ค่าที่วัดเฉพาะจุดชนะค่าของช่วง */
fun JunctionPoint.effective(segments: List<RoadSegment>): RoadAttributes {
    val seg = segments.at(nearestChainageMeters)
    return RoadAttributes(
        lanes = lanes ?: seg?.lanes,
        widthMeters = widthMeters ?: seg?.widthMeters,
        shoulderMeters = shoulderMeters ?: seg?.shoulderMeters,
        surface = if (surface != SurfaceKind.UNKNOWN) surface else seg?.surface ?: SurfaceKind.UNKNOWN,
        segmentName = seg?.name?.ifBlank { null }
    )
}

/** รายละเอียดช่วงถนนสำหรับเขียนเป็น TEXT ใน DXF */
fun RoadSegment.dxfLines(startChainage: StartChainageConfig): List<String> = buildList {
    add(name.ifBlank { "ช่วง" })
    add(attributes().summary())
    add(
        "กม.${chainageText(startChainage.displayMeters(startChainageMeters.toDouble()))}" +
                " - ${chainageText(startChainage.displayMeters(endChainageMeters.toDouble()))}" +
                " (${String.format("%.0f", lengthMeters / 1000.0)} กม.)"
    )
    note.takeIf { it.isNotBlank() }?.let { add(it) }
}

private fun chainageText(meters: Int) =
    "${meters / 1000}+${(meters % 1000).toString().padStart(3, '0')}"

// ── ตำแหน่งบนเส้นทาง ─────────────────────────────────────────

/** จุดที่ใกล้ระยะ [meters] ที่สุด — ใช้ลากหัวช่วงบนแผนที่ */
fun pointAtChainage(points: List<TrackPoint>, meters: Double): TrackPoint? =
    if (points.isEmpty()) null else points.minByOrNull { abs(it.chainageMeters - meters) }

/** ตัดเฉพาะช่วง [startMeters]–[endMeters] ตามระยะตามเส้นทาง — ใช้ตอน export */
fun sliceByChainage(
    points: List<TrackPoint>, startMeters: Double, endMeters: Double
): List<TrackPoint> {
    val lo = minOf(startMeters, endMeters)
    val hi = maxOf(startMeters, endMeters)
    return points.filter { it.chainageMeters in lo..hi }
}

// ── เดิม (ไม่เปลี่ยน) ────────────────────────────────────────

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
    val roadSegments: List<RoadSegment> = emptyList(),
    val lastSpokenText: String? = null,
    val ttsStatus: String = "กำลังเตรียมเสียงภาษาไทย",
    val startChainage: StartChainageConfig = StartChainageConfig(),
    val voiceMode: VoiceMode = VoiceMode.THAI
)