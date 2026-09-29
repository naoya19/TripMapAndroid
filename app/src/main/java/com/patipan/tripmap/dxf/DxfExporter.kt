package com.patipan.tripmap.dxf

import android.content.Context
import com.patipan.tripmap.tracking.ChainageDirection
import com.patipan.tripmap.tracking.JunctionPoint
import com.patipan.tripmap.tracking.JunctionSide
import com.patipan.tripmap.tracking.JunctionType
import com.patipan.tripmap.tracking.StartChainageConfig
import com.patipan.tripmap.tracking.TrackPoint
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** แปลงเส้นทาง+จุดทางแยกที่บันทึกไว้ ให้เป็นไฟล์ DXF พร้อมเปิดใน AutoCAD โดยตรงบนมือถือ */
object DxfExporter {
    data class Options(
        val roadWidthMeters: Double = 7.0,
        val lanes: Int = 2,
        val branchWidthMeters: Double = 6.0,
        val branchLengthMeters: Double = 40.0,
        val cornerSmoothingMeters: Double = 1.2
    )

    data class Result(val file: File, val utmZoneLabel: String)

    fun export(
        context: Context,
        points: List<TrackPoint>,
        junctions: List<JunctionPoint>,
        startChainage: StartChainageConfig,
        fileName: String,
        options: Options = Options(),
        intervalMeters: Int = 100
    ): Result {
        require(points.size >= 2) { "ต้องมีอย่างน้อย 2 จุดในเส้นทางถึงจะสร้าง DXF ได้" }

        val centroidLat = points.sumOf { it.latitude } / points.size
        val centroidLon = points.sumOf { it.longitude } / points.size
        val zone = UtmProjection.zoneFor(centroidLat, centroidLon)
        fun project(lat: Double, lon: Double): RoadGeometry.Pt {
            val xy = UtmProjection.project(lat, lon, zone)
            return RoadGeometry.Pt(xy[0], xy[1])
        }

        val routeXy = points.map { project(it.latitude, it.longitude) }
        val halfWidth = options.roadWidthMeters / 2

        val branchCenterlines = mutableListOf<List<RoadGeometry.Pt>>()
        val junctionLabels = mutableListOf<Pair<RoadGeometry.Pt, String>>()
        for (j in junctions) {
            val jXy = project(j.latitude, j.longitude)
            val (basePt, tangent) = RoadGeometry.nearestPointAndTangent(routeXy, jXy)
            val normal = RoadGeometry.Pt(-tangent.y, tangent.x)
            val sides = when (j.side) {
                // normal = (-tangent.y, tangent.x) ชี้ไปทางซ้ายของทิศทางเดินทาง (rotate ทวนเข็ม 90°)
                // ดังนั้น RIGHT ต้องใช้ด้านตรงข้าม normal (-1) ส่วน LEFT ใช้ทิศเดียวกับ normal (+1)
                JunctionSide.RIGHT -> listOf(-1.0)
                JunctionSide.LEFT -> listOf(1.0)
                JunctionSide.BOTH -> listOf(1.0, -1.0)
            }
            sides.forEach { s ->
                val end = RoadGeometry.Pt(basePt.x + normal.x * s * options.branchLengthMeters, basePt.y + normal.y * s * options.branchLengthMeters)
                branchCenterlines += listOf(basePt, end)
            }
            junctionLabels += basePt to if (j.type == JunctionType.THREE_WAY) "3-way" else "4-way"
        }

        val mainPoly = RoadGeometry.roadPolygon(routeXy, halfWidth)
        val branchPolys = branchCenterlines.map { RoadGeometry.roadPolygon(it, options.branchWidthMeters / 2) }
        val merged = RoadGeometry.unionAll(listOf(mainPoly) + branchPolys)
        val smoothed = RoadGeometry.smooth(merged, options.cornerSmoothingMeters)
        val surfacePolys = RoadGeometry.surfacePolygons(smoothed)

        val freeEnds = listOf(routeXy.first(), routeXy.last()) + branchCenterlines.map { it.last() }
        val capRadius = max(halfWidth, options.branchWidthMeters / 2) + options.cornerSmoothingMeters + 3.0
        val edgeChains = RoadGeometry.trimCaps(surfacePolys, freeEnds, capRadius)

        val writer = DxfWriter()
        writer.defineLayer("ROUTE_CENTERLINE", 8)
        writer.defineLayer("ROAD_EDGE", 7)
        writer.defineLayer("LANE_DIVIDER", 2, "DASHED")
        writer.defineLayer("CHAINAGE_LEADER", 1)
        writer.defineLayer("CHAINAGE_TEXT", 1)
        writer.defineLayer("JUNCTION", 4)
        val allX = routeXy.map { it.x } + branchCenterlines.flatten().map { it.x }
        val allY = routeXy.map { it.y } + branchCenterlines.flatten().map { it.y }
        writer.writeHeader(allX.min(), allY.min(), allX.max(), allY.max())
        writer.writeTables()
        writer.writeEmptyBlocks()
        writer.beginEntities()

        edgeChains.forEach { chain -> writer.writePolyline("ROAD_EDGE", chain.map { doubleArrayOf(it.x, it.y) }) }
        writer.writePolyline("ROUTE_CENTERLINE", routeXy.map { doubleArrayOf(it.x, it.y) })
        if (options.lanes >= 2) writer.writePolyline("LANE_DIVIDER", routeXy.map { doubleArrayOf(it.x, it.y) }, linetype = "DASHED")
        branchCenterlines.forEach { bc -> writer.writePolyline("ROUTE_CENTERLINE", bc.map { doubleArrayOf(it.x, it.y) }) }

        val maxChain = points.maxOf { it.chainageMeters }
        var d = points.minOf { it.chainageMeters }
        var labelIndex = 0
        val labelStep = intervalMeters.coerceIn(100, 1_000)
        while (d <= maxChain) {
            val nearest = points.minByOrNull { abs(it.chainageMeters - d) }
            if (nearest != null) {
                val p = project(nearest.latitude, nearest.longitude)
                val (_, tangent) = RoadGeometry.nearestPointAndTangent(routeXy, p)
                val normal = RoadGeometry.Pt(-tangent.y, tangent.x)
                val side = if (labelIndex % 2 == 0) 1.0 else -1.0
                val anchor = RoadGeometry.Pt(p.x + normal.x * (halfWidth + 1.0) * side, p.y + normal.y * (halfWidth + 1.0) * side)
                val textPos = RoadGeometry.Pt(p.x + normal.x * (halfWidth + 6.0) * side, p.y + normal.y * (halfWidth + 6.0) * side)
                writer.writeLine("CHAINAGE_LEADER", anchor.x, anchor.y, textPos.x, textPos.y)
                writer.writeText("CHAINAGE_TEXT", chainageLabel(startChainage.displayMeters(d)), textPos.x, textPos.y)
                labelIndex++
            }
            d += labelStep
        }

        junctionLabels.forEach { (pt, label) -> writer.writeText("JUNCTION", label, pt.x + 3, pt.y + 3, height = 3.0) }

        writer.endEntities()
        writer.writeEof()

        val outDir = File(context.cacheDir, "trip-reports").apply { mkdirs() }
        val outFile = File(outDir, fileName)
        writer.save(outFile)
        return Result(outFile, zone.label)
    }

    private fun chainageLabel(meters: Int): String {
        val safe = meters.coerceAtLeast(0)
        return "กม.${safe / 1000}+${(safe % 1000).toString().padStart(3, '0')}"
    }
}
