package com.patipan.tripmap.dxf

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.MultiLineString
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.precision.GeometryPrecisionReducer
import org.locationtech.jts.geom.PrecisionModel
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** ตรรกะเรขาคณิตของถนน — พอร์ตมาจาจต้นแบบ Python (shapely) ให้ทำงานเหมือนกันทุกประการ */
object RoadGeometry {
    private val gf = GeometryFactory()

    /**
     * ความละเอียดพิกัดสำหรับ union — 1 มิลลิเมตร
     *
     * ต่ำกว่าความแม่น GPS มือถือ (3–5 ม.) มาก จึงไม่กระทบงานสำรวจ
     * แต่พอที่จะทำให้พิกัดที่ควรตรงกันหมดกลายเป็นตรงกันจริง
     */
    private val PRECISION_MODEL = PrecisionModel(0.001)

    data class Pt(val x: Double, val y: Double)

    /** ออฟเซ็ตเส้น polyline แบบ mitered (normal เฉลี่ยของสองช่วงที่ติดกันในแต่ละจุด) */
    fun offsetPolyline(points: List<Pt>, distance: Double): List<Pt> {
        val n = points.size
        if (n < 2) return points
        val segDirs = (0 until n - 1).map { i ->
            val dx = points[i + 1].x - points[i].x
            val dy = points[i + 1].y - points[i].y
            val len = hypot(dx, dy).let { if (it < 1e-9) 1.0 else it }
            Pt(dx / len, dy / len)
        }
        val segNormals = segDirs.map { Pt(-it.y, it.x) }
        val vertexNormals = MutableList(n) { Pt(0.0, 0.0) }
        vertexNormals[0] = segNormals.first()
        vertexNormals[n - 1] = segNormals.last()
        for (i in 1 until n - 1) {
            val a = segNormals[i - 1]; val b = segNormals[i]
            val sx = a.x + b.x; val sy = a.y + b.y
            val len = hypot(sx, sy)
            vertexNormals[i] = if (len > 1e-9) Pt(sx / len, sy / len) else segNormals[i - 1]
        }
        return points.indices.map { i ->
            Pt(points[i].x + vertexNormals[i].x * distance, points[i].y + vertexNormals[i].y * distance)
        }
    }

    fun roadPolygon(points: List<Pt>, halfWidth: Double): Polygon {
        val left = offsetPolyline(points, halfWidth)
        val right = offsetPolyline(points, -halfWidth).reversed()
        val ringPts = (left + right + left.first()).map { Coordinate(it.x, it.y) }
        return gf.createPolygon(ringPts.toTypedArray())
    }

    /**
     * รวม polygon หลายชิ้นเข้าด้วยกัน
     *
     * พิกัดจาก GPS มี noise ทำให้เส้นขอบถนนที่ตัดกันตรงมุมกลายเป็น non-noded intersection
     * ซึ่ง JTS union() ไม่ยอมรับ — ต้องลดความละเอียดพิกัดให้ตรงกันก่อน (snap ให้ตรงกันภายใน 1 มม.)
     *
     * ห่อ runCatching ทุกขั้น เพื่อถ้าชิ้นใดพังก็ข้างไป แทนที่จะทำให้ทั้ง export ล้ม
     */
    fun unionAll(polys: List<Polygon>): Geometry {
        if (polys.isEmpty()) return gf.createPolygon()

        val reduced = polys.mapNotNull { p ->
            runCatching { GeometryPrecisionReducer.reduce(p, PRECISION_MODEL) }.getOrNull()
        }
        if (reduced.isEmpty()) return polys.first()
        if (reduced.size == 1) return reduced.first()

        var result = reduced.first()
        for (p in reduced.drop(1)) {
            result = runCatching { result.union(p) }.getOrElse { result }
        }
        // buffer(0) ซ่อม polygon ที่เสียรูปจากการรวม — อาจคืน GeometryCollection ที่ surfacePolygons ต้องรองรับ
        return runCatching { result.buffer(0.0) }.getOrDefault(result)
    }

    /** ขยายแล้วหดกลับด้วย round join — มนมุมตรงรอยต่อทางแยกเล็กน้อย เหมือนต้นแบบ Python */
    fun smooth(geom: Geometry, distance: Double): Geometry {
        if (distance <= 0.0) return geom
        return runCatching {
            val params = BufferParameters().apply { joinStyle = BufferParameters.JOIN_ROUND }
            val expanded = BufferOp.bufferOp(geom, distance, params)
            BufferOp.bufferOp(expanded, -distance, params)
        }.getOrDefault(geom)
    }

    /**
     * ดึงเฉพาะ polygon ออกมา
     *
     * ✅ แก้แล้ว — รองรับ GeometryCollection ด้วย
     *
     * เดิมรองรับแค่ Polygon / MultiPolygon แล้ว return emptyList() ที่อื่น
     * ซึ่งเป็นสาเหตุที่ทำให้ union() และ buffer(0) ที่คืน GeometryCollection
     * ทำให้ไม่มีเส้นขอบถนนถูกเขียนเลย
     */
    fun surfacePolygons(geom: Geometry): List<Polygon> {
        val out = mutableListOf<Polygon>()
        fun collect(g: Geometry) {
            when (g) {
                is Polygon -> if (!g.isEmpty) out += g
                is MultiPolygon -> for (i in 0 until g.numGeometries) collect(g.getGeometryN(i))
                is GeometryCollection -> for (i in 0 until g.numGeometries) collect(g.getGeometryN(i))
                else -> Unit
            }
        }
        collect(geom)
        return out
    }

    /**
     * ตัดขอบถนนตรงจุดปลายอิสระ (ต้นทาง/ปลายทาง/ปลายทางแยก) ออก ไม่ให้มีเส้นปิดหัว-ท้าย
     *
     * ✅ แก้แล้ว — ตัดทีละวง ไม่ union วงกลมทั้งหมดเข้าด้วยกัน
     *
     * เดิม union วงกลมทุกวงเป็นก้อนเดียวก่อน แล้ว difference ครั้งเดียว
     * ซึ่งบนพิกัด GPS ที่มี noise และเส้นยาวหลายกิโลเมตร มักพัง
     * แล้วพังแบบเงียบ ๆ (โค้ดเดิม `?: continue` ทำให้ไม่ได้เส้นไม่เลย)
     */
    fun trimCaps(polygons: List<Polygon>, freeEnds: List<Pt>, radius: Double): List<List<Pt>> {
        fun allRings() = polygons.map { p -> p.exteriorRing.coordinates.map { Pt(it.x, it.y) } }
        if (polygons.isEmpty()) return emptyList()
        if (freeEnds.isEmpty() || radius <= 0.0) return allRings()

        val chains = mutableListOf<List<Pt>>()
        for (poly in polygons) {
            var current: Geometry = poly.exteriorRing
            var failed = false
            for (end in freeEnds) {
                if (current.isEmpty) { failed = true; break }
                val cutter = runCatching {
                    gf.createPoint(Coordinate(end.x, end.y)).buffer(radius)
                }.getOrNull() ?: continue
                val next = runCatching { current.difference(cutter) }.getOrNull()
                if (next == null) { failed = true; break }
                current = next
            }
            // ตัดไม่สำเร็จ → คืนวงเต็ม ดีกว่าวาดไม่ออกเลย
            if (failed) {
                chains += poly.exteriorRing.coordinates.map { Pt(it.x, it.y) }
            } else {
                collectLines(current, chains)
            }
        }
        return chains
    }

    /** แปลงผลของ difference() ให้เป็นเส้นทั้งหมด ไม่ว่าจะมาเป็นชนิดใด */
    private fun collectLines(geom: Geometry, out: MutableList<List<Pt>>) {
        when (geom) {
            is LineString -> if (geom.length > 0.5) out += geom.coordinates.map { Pt(it.x, it.y) }
            is MultiLineString -> for (i in 0 until geom.numGeometries) collectLines(geom.getGeometryN(i), out)
            is GeometryCollection -> for (i in 0 until geom.numGeometries) collectLines(geom.getGeometryN(i), out)
            else -> Unit
        }
    }

    /**
     * เส้นขอบถนนจากการออฟเซ็ตโดยตรง ไม่ผ่าน JTS — ใช้เป็นตาข่ายนิรภัยเมื่อ geometry ซับซ้อนพัง
     *
     * ไม่ได้โค้งตรงมุมเท่า buffer/smooth แต่แนวโน้มถูกต้องเสมอ
     */
    fun edgeLines(points: List<Pt>, halfWidth: Double): List<List<Pt>> {
        if (points.size < 2 || halfWidth <= 0.0) return emptyList()
        val left = offsetPolyline(points, halfWidth)
        val right = offsetPolyline(points, -halfWidth)
        return listOf(left, right)
    }

    /** จุด+ทิศทาง (tangent หนึ่งหน่วย) ของจุดบนเส้นทางที่ใกล้ [target] ที่สุด */
    fun nearestPointAndTangent(route: List<Pt>, target: Pt): Pair<Pt, Pt> {
        val idx = route.indices.minByOrNull { i ->
            val dx = route[i].x - target.x; val dy = route[i].y - target.y; dx * dx + dy * dy
        } ?: 0
        val a = route[max(0, idx - 1)]
        val b = route[min(route.lastIndex, idx + 1)]
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = hypot(dx, dy).let { if (it < 1e-9) 1.0 else it }
        return route[idx] to Pt(dx / len, dy / len)
    }

    // ════════════════════════════════════════════════════════════
    //  v1.5 — ตัวช่วยสำหรับช่องทาง/ไหล่ทาง
    // ════════════════════════════════════════════════════════════

    /**
     * เส้นแบ่งช่องทางเดินรถ — อยู่กลางถนนเสมอ ถ้าเลขคี่เท่าไหร่ก็มีเส้นนั้น−1 เส้น
     *
     * @param laneCount จำนวนช่องทางต่อทิศทาง
     * @param carriagewayWidth ความกว้างช่องทางเดินรถรวม (ไม่รวมไหล่ทาง)
     */
    fun laneDividerOffsets(laneCount: Int, carriagewayWidth: Double): List<Double> {
        val n = laneCount.coerceAtLeast(2)
        val laneW = carriagewayWidth / n
        // เส้นแบ่งอยู่ที่ระยะเท่ากับขอบถนน + k × ความกว้างช่องทาง
        return (1 until n).map { k -> -carriagewayWidth / 2 + k * laneW }
    }

    /**
     * เส้นขอบไหล่ทาง — เส้นคั่นระหว่างช่องทางเดินรถกับไหล่ทาง
     *
     * @param carriagewayWidth ความกว้างช่องทางเดินรถรวม
     * @param shoulderWidth ระยะไหล่ทางต่อข้าง
     */
    fun shoulderEdgeOffsets(carriagewayWidth: Double, shoulderWidth: Double): List<Double> {
        if (shoulderWidth <= 0.0) return emptyList()
        val half = carriagewayWidth / 2
        return listOf(-(half + shoulderWidth), half + shoulderWidth)
    }
}