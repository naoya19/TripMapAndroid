package com.patipan.tripmap.dxf

import android.content.Context
import com.patipan.tripmap.data.SurfaceKind
import com.patipan.tripmap.tracking.*
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** แปลงเส้นทาง+จุดทางแยก+ช่วงถนน ให้เป็นไฟล์ DXF พร้อมเปิดใน AutoCAD โดยตรง
 *
 *  เขียนเป็น ASCII รูปแบบ R12 (AC1009) ซึ่งเปิดได้ทุกเวอร์ชันตั้งแต่ AutoCAD 2000 ถึงปัจจุบัน
 *  พิกัดเป็นระบบ UTM เมตร 2 มิติ (ไม่มี Z ตามที่ตกลงกัน)
 */
object DxfExporter {
    data class Options(
        val roadWidthMeters: Double = 7.0,
        val lanes: Int = 2,
        val branchWidthMeters: Double = 6.0,
        val branchLengthMeters: Double = 40.0,
        val cornerSmoothingMeters: Double = 1.2,
        /** ชั้นที่ผู้ใช้เปิดไว้ในแผงชั้นข้อมูล — ปิด = ไม่เขียนลง DXF */
        val includeCenterline: Boolean = true,
        val includeRoadEdge: Boolean = true,
        val includeLaneDivider: Boolean = true,
        val includeChainage: Boolean = true,
        val includeJunctions: Boolean = true,
        val includeNotes: Boolean = true
    )

    data class Result(val file: File, val utmZoneLabel: String)

    /** ช่วงของเส้นทางหนึ่งช่วง พร้อมช่วงถนนที่ครอบคลุม (null = ไม่ได้บันทึกค่าถนน) */
    private data class Span(
        val segment: RoadSegment?,
        val fromMeters: Double,
        val toMeters: Double,
        val points: List<TrackPoint>
    )

    /** ช่วงของเส้นทางหนึ่งช่วง พร้อมแขนแยกที่อยู่ในช่วงนั้น */
    private data class Branch(val centerline: List<RoadGeometry.Pt>, val chainage: Double)

    /** ชื่อ layer ของช่วงถนน: ROAD_SEG_01, ROAD_SEG_02, …
     *  ชื่อช่วงจริงจะไปอยู่ใน TEXT แทน เพราะชื่อ layer ของ DXF จำกัด 31 ตัวอักษรและห้ามมีอักษรพิเศษ
     */
    fun segmentLayerName(index: Int) = "ROAD_SEG_%02d".format(index + 1)

    fun export(
        context: Context,
        points: List<TrackPoint>,
        junctions: List<JunctionPoint>,
        startChainage: StartChainageConfig,
        fileName: String,
        roadSegments: List<RoadSegment> = emptyList(),
        options: Options = Options(),
        intervalMeters: Int = 100
    ): Result {
        require(points.size >= 2) { "ต้องมีอย่างน้อย 2 จุดในเส้นทางถึงจะสร้าง DXF ได้" }

        val maxChain = points.maxOf { it.chainageMeters }
        val segments = roadSegments
            .sortedBy { it.startChainageMeters }
            .filter { it.startChainageMeters < maxChain }

        val centroidLat = points.sumOf { it.latitude } / points.size
        val centroidLon = points.sumOf { it.longitude } / points.size
        val zone = UtmProjection.zoneFor(centroidLat, centroidLon)
        fun project(lat: Double, lon: Double): RoadGeometry.Pt {
            val xy = UtmProjection.project(lat, lon, zone)
            return RoadGeometry.Pt(xy[0], xy[1])
        }

        val routeXy = points.map { project(it.latitude, it.longitude) }
        val baseHalfWidth = options.roadWidthMeters / 2
        val spans = buildSpans(points, segments, maxChain)

        // ── แขนแยก: จัดเข้าช่วงตามระยะของจุดทางแยก ──
        val branches: List<Branch> = junctions.flatMap { j ->
            val jXy = project(j.latitude, j.longitude)
            val (basePt, tangent) = RoadGeometry.nearestPointAndTangent(routeXy, jXy)
            val normal = RoadGeometry.Pt(-tangent.y, tangent.x)
            val sides = when (j.side) {
                JunctionSide.RIGHT -> listOf(-1.0)
                JunctionSide.LEFT -> listOf(1.0)
                JunctionSide.BOTH -> listOf(1.0, -1.0)
            }
            sides.map { s ->
                Branch(
                    centerline = listOf(
                        basePt,
                        RoadGeometry.Pt(
                            basePt.x + normal.x * s * options.branchLengthMeters,
                            basePt.y + normal.y * s * options.branchLengthMeters
                        )
                    ),
                    chainage = j.nearestChainageMeters
                )
            }
        }

        val junctionLabels = mutableListOf<Pair<RoadGeometry.Pt, String>>()
        val junctionNotes = mutableListOf<Pair<RoadGeometry.Pt, String>>()
        for (j in junctions) {
            val jXy = project(j.latitude, j.longitude)
            val (basePt, _) = RoadGeometry.nearestPointAndTangent(routeXy, jXy)
            junctionLabels += basePt to "${j.type.label()} (${sideLabel(j.side)})"
            val summary = j.effective(segments).summary()
            if (summary.isNotBlank()) junctionNotes += RoadGeometry.Pt(basePt.x + 3, basePt.y - 1.2) to summary
        }

        // ── เตรียมชั้น ──
        val writer = DxfWriter()
        writer.defineLayer("ROUTE_CENTERLINE", 5)
        writer.defineLayer("ROAD_EDGE", 7)
        // สีเดิม 8 (เทาเข้ม) แทบมองไม่เห็นบนพื้นหลังดำของโปรแกรมดู DXF บนมือถือหลายตัว — เปลี่ยนเป็น
        // 3 (เขียว) ให้ตัดกับเส้นอื่นชัดเจน (เส้นกึ่งกลาง=5 น้ำเงิน, เส้นแบ่งเลน=2 เหลือง)
        writer.defineLayer("SHOULDER_EDGE", 3)
        writer.defineLayer("LANE_DIVIDER", 2, "DASHED")
        writer.defineLayer("CHAINAGE_LEADER", 1)
        writer.defineLayer("CHAINAGE_TEXT", 1)
        writer.defineLayer("JUNCTION", 4)
        writer.defineLayer("SURVEY_NOTE", 6)
        segments.forEachIndexed { index, seg -> writer.defineLayer(segmentLayerName(index), seg.colorIndex) }

        val allX = routeXy.map { it.x } + branches.map { it.centerline[1].x }
        val allY = routeXy.map { it.y } + branches.map { it.centerline[1].y }
        writer.writeHeader(allX.min(), allY.min(), allX.max(), allY.max())
        writer.writeTables()
        writer.writeEmptyBlocks()
        writer.beginEntities()

        // ── เส้นกึ่งกลาง (ทั้งเส้นหลักและแขนแยก) ──
        if (options.includeCenterline) {
            writer.writePolyline("ROUTE_CENTERLINE", routeXy.map { doubleArrayOf(it.x, it.y) })
            branches.forEach { b ->
                writer.writePolyline("ROUTE_CENTERLINE", b.centerline.map { doubleArrayOf(it.x, it.y) })
            }
        }

        // ── ช่วงถนน: แต่ละช่วงได้ความกว้างของตัวเอง ──
        spans.forEach { span ->
            val seg = span.segment
            val layer = seg?.let { segmentLayerName(segments.indexOf(it)) } ?: "ROAD_EDGE"
            val attrs = seg?.attributes()
                ?: RoadAttributes(lanes = options.lanes, widthMeters = options.roadWidthMeters, surface = SurfaceKind.UNKNOWN)

            // ความกว้างช่องทางเดินรถ (ไม่รวมไหล่ทาง) — ใช้วางขอบ/เส้นแบ่งด้วย
            val carriageway = attrs.carriagewayWidthMeters ?: options.roadWidthMeters
            val totalWidth = attrs.totalWidthMeters ?: options.roadWidthMeters
            val halfWidth = (totalWidth / 2).coerceIn(1.0, 30.0)
            val halfCarriage = (carriageway / 2).coerceIn(0.5, 30.0)

            val centerLine = span.points.map { project(it.latitude, it.longitude) }
            if (centerLine.size < 2) return@forEach

            val spanBranches = branches.filter {
                it.chainage >= span.fromMeters && it.chainage <= span.toMeters
            }

            if (options.includeRoadEdge) {
                // ── ขอบถนน: ลองผ่าน JTS ก่อน (โค้งมุมสวย) ──
                val polygons = buildList {
                    add(RoadGeometry.roadPolygon(centerLine, halfWidth))
                    spanBranches.forEach { add(RoadGeometry.roadPolygon(it.centerline, halfWidth)) }
                }
                val smoothed = RoadGeometry.smooth(RoadGeometry.unionAll(polygons), options.cornerSmoothingMeters)
                val surfacePolys = RoadGeometry.surfacePolygons(smoothed)

                val freeEnds = buildList {
                    if (span.fromMeters <= 0.0) add(centerLine.first())
                    if (span.toMeters >= maxChain) add(centerLine.last())
                    spanBranches.forEach { add(it.centerline.last()) }
                }
                val capRadius = halfWidth + options.cornerSmoothingMeters + 3.0
                val chains = RoadGeometry.trimCaps(surfacePolys, freeEnds, capRadius)

                // ── ตรวจว่าผล JTS ดูสมเหตุสมผลก่อนวาดจริง ──
                // ปัญหาที่พบ: กับเส้นทางยาวมาก (เช่น 40+ กม.) union/buffer/trim บางครั้งพังแบบเงียบ ๆ
                // แล้วคืน polygon เศษเล็ก ๆ ที่ผิดรูป (เช่น สี่เหลี่ยมใกล้จุด (0,0) ไม่กี่สิบจุด) แทนที่จะเป็น
                // ขอบถนนจริงหลายพันจุดตลอดเส้นทาง — เดิมเช็คแค่ "วาดอะไรได้บ้างไหม" (drawn>0) ซึ่งเศษพัง ๆ
                // นี้ก็นับว่า "วาดได้" ทำให้ตาข่ายนิรภัยไม่ทำงาน สุดท้ายขอบถนนทั้งเส้นหายไปเงียบ ๆ
                // เปลี่ยนมาเช็คจำนวนจุดรวมเทียบกับเส้นกึ่งกลางแทน ถ้าน้อยผิดปกติให้ถือว่าพังจริง
                val totalChainPoints = chains.sumOf { it.size }
                val looksValid = totalChainPoints >= centerLine.size

                if (looksValid) {
                    chains.forEach { chain ->
                        if (chain.size > 1) {
                            writer.writePolyline(layer, chain.map { doubleArrayOf(it.x, it.y) })
                        }
                    }
                } else {
                    // ── ตาข่ายนิรภัย: ถ้า JTS ไม่ได้ผลจริง (หรือได้ผลเพี้ยน) ใช้การออฟเซ็ตตรง ──
                    RoadGeometry.edgeLines(centerLine, halfWidth).forEach { edge ->
                        if (edge.size > 1) {
                            writer.writePolyline(layer, edge.map { doubleArrayOf(it.x, it.y) })
                        }
                    }
                    spanBranches.forEach { b ->
                        RoadGeometry.edgeLines(b.centerline, halfWidth).forEach { edge ->
                            if (edge.size > 1) {
                                writer.writePolyline(layer, edge.map { doubleArrayOf(it.x, it.y) })
                            }
                        }
                    }
                }
            }

            // ── ขอบไหล่ทาง: เส้นคั่นระหว่างช่องทางเดินรถกับไหล่ทาง ──
            if (options.includeRoadEdge && attrs.shoulderEachSide > 0) {
                RoadGeometry.shoulderEdgeOffsets(carriageway, attrs.shoulderEachSide).forEach { off ->
                    val edge = RoadGeometry.offsetPolyline(centerLine, off)
                    if (edge.size > 1) {
                        writer.writePolyline("SHOULDER_EDGE", edge.map { doubleArrayOf(it.x, it.y) })
                    }
                }
            }

            // ── เส้นแบ่งช่องทาง: อยู่กลางช่องทางเดินรถ จำนวนเลน−1 เส้น ──
            if (options.includeLaneDivider && (attrs.lanes ?: 0) >= 2) {
                RoadGeometry.laneDividerOffsets(attrs.lanes ?: 2, carriageway).forEach { off ->
                    val line = RoadGeometry.offsetPolyline(centerLine, off)
                    if (line.size > 1) {
                        // แก้แล้ว — เดิมใช้ `layer` (= ชั้นของช่วงถนน เช่น ROAD_SEG_01) ทำให้เส้นแบ่งเลน
                        // ไปรวมกับเส้นขอบถนนในชั้นเดียวกัน (สีเดียวกัน) ทั้งที่ประกาศชั้น "LANE_DIVIDER"
                        // ไว้ในตาราง layer แล้วแต่ไม่เคยถูกใช้จริงเลยสักที่
                        writer.writePolyline("LANE_DIVIDER", line.map { doubleArrayOf(it.x, it.y) }, linetype = "DASHED")
                    }
                }
            }

            if (seg != null && options.includeNotes) {
                val mid = pointAtChainage(span.points, (span.fromMeters + span.toMeters) / 2.0)
                val midXy = mid?.let { project(it.latitude, it.longitude) }
                if (midXy != null) {
                    seg.dxfLines(startChainage).forEachIndexed { i, line ->
                        writer.writeText("SURVEY_NOTE", line, midXy.x + 4, midXy.y + 4 - i * 1.8, height = 1.8)
                    }
                }
            }
        }

        // ── ป้ายระยะ กม. ──
        if (options.includeChainage) {
            val minChain = points.minOf { it.chainageMeters }
            val labelStep = intervalMeters.coerceIn(100, 1_000)
            var d = minChain
            var labelIndex = 0
            while (d <= maxChain) {
                val nearest = points.minByOrNull { abs(it.chainageMeters - d) }
                if (nearest != null) {
                    val p = project(nearest.latitude, nearest.longitude)
                    val (_, tangent) = RoadGeometry.nearestPointAndTangent(routeXy, p)
                    val normal = RoadGeometry.Pt(-tangent.y, tangent.x)
                    val side = if (labelIndex % 2 == 0) 1.0 else -1.0
                    val anchor = RoadGeometry.Pt(
                        p.x + normal.x * (baseHalfWidth + 1.0) * side,
                        p.y + normal.y * (baseHalfWidth + 1.0) * side
                    )
                    val textPos = RoadGeometry.Pt(
                        p.x + normal.x * (baseHalfWidth + 6.0) * side,
                        p.y + normal.y * (baseHalfWidth + 6.0) * side
                    )
                    writer.writeLine("CHAINAGE_LEADER", anchor.x, anchor.y, textPos.x, textPos.y)
                    writer.writeText(
                        "CHAINAGE_TEXT",
                        chainageLabel(startChainage.displayMeters(d)),
                        textPos.x, textPos.y
                    )
                    labelIndex++
                }
                d += labelStep
            }
        }

        // ── จุดทางแยก ──
        if (options.includeJunctions) {
            junctionLabels.forEach { (pt, label) ->
                writer.writeText("JUNCTION", label, pt.x + 3, pt.y + 3, height = 3.0)
            }
            if (options.includeNotes) {
                junctionNotes.forEach { (pt, text) ->
                    writer.writeText("SURVEY_NOTE", text, pt.x + 3, pt.y, height = 1.6)
                }
            }
        }

        writer.endEntities()
        writer.writeEof()

        val outDir = File(context.cacheDir, "trip-reports").apply { mkdirs() }
        val outFile = File(outDir, fileName)
        writer.save(outFile)
        return Result(outFile, zone.label)
    }

    /** แบ่งเส้นทางเป็นช่วงตามที่ผู้ใช้บันทึกไว้ — ช่วงก่อนแรกและหลังสุดที่ไม่มีข้อมูลถือว่าไม่มีช่วง */
    private fun buildSpans(
        points: List<TrackPoint>,
        segments: List<RoadSegment>,
        maxChain: Double
    ): List<Span> {
        if (segments.isEmpty()) {
            return listOf(Span(null, points.first().chainageMeters, maxChain, points))
        }
        val spans = mutableListOf<Span>()
        var from = 0.0
        segments.forEach { seg ->
            val segStart = seg.startChainageMeters.toDouble().coerceIn(0.0, maxChain)
            if (segStart > from) {
                val lead = sliceByChainage(points, from, segStart)
                if (lead.size >= 2) spans += Span(null, from, segStart, lead)
            }
            val to = seg.endChainageMeters.toDouble().coerceAtMost(maxChain)
            if (to > segStart) {
                val slice = sliceByChainage(points, segStart, to)
                if (slice.size >= 2) spans += Span(seg, segStart, to, slice)
            }
            from = max(from, to)
        }
        if (from < maxChain) {
            val tail = points.filter { it.chainageMeters > from }
            if (tail.size >= 2) spans += Span(null, from, maxChain, tail)
        }
        return spans
    }

    private fun sideLabel(side: JunctionSide) = when (side) {
        JunctionSide.LEFT -> "ซ้าย"
        JunctionSide.RIGHT -> "ขวา"
        JunctionSide.BOTH -> "ทั้งสองด้าน"
    }

    private fun chainageLabel(meters: Int): String {
        val safe = meters.coerceAtLeast(0)
        return "กม.${safe / 1000}+${(safe % 1000).toString().padStart(3, '0')}"
    }
}