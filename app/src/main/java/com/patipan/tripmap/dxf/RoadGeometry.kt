package com.patipan.tripmap.dxf

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** ตรรกะเรขาคณิตของถนน — พอร์ตมาจากต้นแบบ Python (shapely) ให้ทำงานเหมือนกันทุกประการ */
object RoadGeometry {
    private val gf = GeometryFactory()

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
        return points.indices.map { i -> Pt(points[i].x + vertexNormals[i].x * distance, points[i].y + vertexNormals[i].y * distance) }
    }

    fun roadPolygon(points: List<Pt>, halfWidth: Double): Polygon {
        val left = offsetPolyline(points, halfWidth)
        val right = offsetPolyline(points, -halfWidth).reversed()
        val ringPts = (left + right + left.first()).map { Coordinate(it.x, it.y) }
        return gf.createPolygon(ringPts.toTypedArray())
    }

    fun unionAll(polys: List<Polygon>): Geometry {
        var result: Geometry = polys.first()
        for (p in polys.drop(1)) result = result.union(p)
        return result
    }

    /** ขยายแล้วหดกลับด้วย round join — มนมุมตรงรอยต่อทางแยกเล็กน้อย เหมือนต้นแบบ Python */
    fun smooth(geom: Geometry, distance: Double): Geometry {
        val params = BufferParameters().apply { joinStyle = BufferParameters.JOIN_ROUND }
        val expanded = BufferOp.bufferOp(geom, distance, params)
        return BufferOp.bufferOp(expanded, -distance, params)
    }

    fun surfacePolygons(geom: Geometry): List<Polygon> = when (geom) {
        is Polygon -> listOf(geom)
        is MultiPolygon -> (0 until geom.numGeometries).map { geom.getGeometryN(it) as Polygon }
        else -> emptyList()
    }

    /** ตัดขอบถนนตรงจุดปลายอิสระ (ต้นทาง/ปลายทาง/ปลายทางแยก) ออก ไม่ให้มีเส้นปิดหัว-ท้าย */
    fun trimCaps(polygons: List<Polygon>, freeEnds: List<Pt>, radius: Double): List<List<Pt>> {
        if (freeEnds.isEmpty()) return polygons.map { p -> p.exteriorRing.coordinates.map { Pt(it.x, it.y) } }
        var cutUnion: Geometry = gf.createPoint(Coordinate(freeEnds[0].x, freeEnds[0].y)).buffer(radius)
        for (pt in freeEnds.drop(1)) {
            cutUnion = cutUnion.union(gf.createPoint(Coordinate(pt.x, pt.y)).buffer(radius))
        }
        val chains = mutableListOf<List<Pt>>()
        for (poly in polygons) {
            val boundary: LineString = poly.exteriorRing
            val remainder = boundary.difference(cutUnion)
            for (i in 0 until remainder.numGeometries) {
                val g = remainder.getGeometryN(i)
                if (g is LineString && g.length > 0.5) {
                    chains += g.coordinates.map { Pt(it.x, it.y) }
                }
            }
        }
        return chains
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
}
