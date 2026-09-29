package com.patipan.tripmap.share

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.patipan.tripmap.tracking.TrackPoint
import com.patipan.tripmap.tracking.StartChainageConfig
import com.patipan.tripmap.tracking.JunctionPoint
import com.patipan.tripmap.tracking.JunctionType
import com.patipan.tripmap.tracking.JunctionSide
import com.patipan.tripmap.tracking.JunctionSource
import com.patipan.tripmap.dxf.DxfExporter
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

enum class ReportFormat { PNG, PDF, KMZ, DXF }

data class ExportSelection(
    val startRouteMeters: Int = 0,
    val endRouteMeters: Int = Int.MAX_VALUE,
    val intervalMeters: Int = 1_000
)

data class TripReport(
    val startedAt: Long,
    val durationMillis: Long,
    val netDistanceMeters: Double,
    val traveledDistanceMeters: Double,
    val points: List<TrackPoint>,
    val name: String = "",
    val startChainage: StartChainageConfig = StartChainageConfig(),
    val junctions: List<JunctionPoint> = emptyList(),
    val selection: ExportSelection = ExportSelection()
)

object TripReportExporter {
    fun share(context: Context, report: TripReport, format: ReportFormat) {
        val prepared = prepareReport(report)
        val dir = File(context.cacheDir, "trip-reports").apply { mkdirs() }
        val safeBase = report.name.ifBlank { "trip-route" }.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40).ifBlank { "trip-route" }
        val file = when (format) {
            ReportFormat.PNG -> File(dir, "$safeBase.png").also { writePng(it, prepared) }
            ReportFormat.PDF -> File(dir, "$safeBase.pdf").also { writePdf(it, prepared) }
            ReportFormat.KMZ -> File(dir, "$safeBase.kmz").also { writeKmz(it, prepared) }
            ReportFormat.DXF -> DxfExporter.export(context, prepared.points, prepared.junctions, prepared.startChainage, "$safeBase.dxf", intervalMeters = prepared.selection.intervalMeters).file
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = when (format) {
            ReportFormat.PNG -> "image/png"
            ReportFormat.PDF -> "application/pdf"
            ReportFormat.KMZ -> "application/vnd.google-earth.kmz"
            // Do NOT use image/vnd.dxf: Telegram/LINE can treat DXF as an image and convert it.
            ReportFormat.DXF -> "application/octet-stream"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, if (format == ReportFormat.DXF) "Trip Map DXF — เปิดเป็นไฟล์ .dxf" else "ไฟล์จาก Trip Map")
            clipData = android.content.ClipData.newRawUri("Trip Map file", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "แชร์ทริป"))
    }

    private fun prepareReport(report: TripReport): TripReport {
        val max = report.points.maxOfOrNull { it.chainageMeters.toInt() } ?: 0
        val start = report.selection.startRouteMeters.coerceIn(0, max)
        val end = report.selection.endRouteMeters.coerceIn(start, max)
        val points = slicePoints(report.points, start.toDouble(), end.toDouble())
        val junctions = report.junctions.filter { it.nearestChainageMeters in start.toDouble()..end.toDouble() }
        return report.copy(points = points, junctions = junctions, selection = report.selection.copy(startRouteMeters = start, endRouteMeters = end))
    }

    private fun slicePoints(points: List<TrackPoint>, start: Double, end: Double): List<TrackPoint> {
        if (points.isEmpty()) return emptyList()
        val ordered = points.sortedBy { it.chainageMeters }
        fun interpolate(target: Double): TrackPoint {
            val exact = ordered.minByOrNull { abs(it.chainageMeters - target) }!!
            val hi = ordered.firstOrNull { it.chainageMeters >= target } ?: exact
            val lo = ordered.lastOrNull { it.chainageMeters <= target } ?: exact
            if (hi === lo || abs(hi.chainageMeters - lo.chainageMeters) < 0.001) return exact.copy(chainageMeters = target)
            val t = ((target - lo.chainageMeters) / (hi.chainageMeters - lo.chainageMeters)).coerceIn(0.0, 1.0)
            return TrackPoint(
                latitude = lo.latitude + (hi.latitude - lo.latitude) * t,
                longitude = lo.longitude + (hi.longitude - lo.longitude) * t,
                accuracyMeters = (lo.accuracyMeters + (hi.accuracyMeters - lo.accuracyMeters) * t).toFloat(),
                timestamp = (lo.timestamp + ((hi.timestamp - lo.timestamp) * t)).toLong(),
                chainageMeters = target
            )
        }
        val result = ordered.filter { it.chainageMeters in start..end }.toMutableList()
        if (result.isEmpty() || abs(result.first().chainageMeters - start) > 0.01) result.add(0, interpolate(start))
        if (abs(result.last().chainageMeters - end) > 0.01) result.add(interpolate(end))
        return result.distinctBy { "%.3f".format(java.util.Locale.US, it.chainageMeters) }
    }

    private fun writePng(file: File, report: TripReport) {
        val sections = reportSections(report)
        val bitmap = Bitmap.createBitmap(1080, (360 + sections.size * 920).coerceAtMost(16_000), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        sections.forEachIndexed { index, section -> drawPage(canvas, report, section, if (index == 0) 0 else index * 920, 920, index == 0) }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 96, it) }
        bitmap.recycle()
    }

    private fun writePdf(file: File, report: TripReport) {
        val document = PdfDocument()
        reportSections(report).forEachIndexed { index, section ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(1080, 1440, index + 1).create())
            drawPage(page.canvas, report, section, 0, 1440, true); document.finishPage(page)
        }
        FileOutputStream(file).use(document::writeTo); document.close()
    }

    /** Google Earth KMZ: the original GPS track plus chainage pins every 100 metres, and any junction points. */
    private fun writeKmz(file: File, report: TripReport) {
        val points = report.points
        fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        fun label(routeMeters: Int) = chainage(report.startChainage.displayMeters(routeMeters.toDouble()).toDouble(), report.startChainage.direction.name)
        val maxChain = points.maxOfOrNull { it.chainageMeters.toInt() } ?: 0
        val pins = buildString {
            for (meters in report.selection.startRouteMeters..maxChain step report.selection.intervalMeters.coerceIn(100, 1_000)) {
                val point = points.minByOrNull { abs(it.chainageMeters - meters) } ?: continue
                append("<Placemark><name>${escape(label(meters))}</name><description>Chainage ${escape(label(meters))}</description><Point><coordinates>${point.longitude},${point.latitude},0</coordinates></Point></Placemark>")
            }
        }
        val junctionPins = buildString {
            report.junctions.forEach { j ->
                val typeLabel = if (j.type == JunctionType.THREE_WAY) "3-way" else "4-way"
                val sideLabel = when (j.side) { JunctionSide.LEFT -> "left"; JunctionSide.RIGHT -> "right"; JunctionSide.BOTH -> "both" }
                val sourceLabel = if (j.source == JunctionSource.OSM) "osm" else "manual"
                val chainageLabel = escape(label((j.nearestChainageMeters).toInt()))
                append("<Placemark><name>${escape(typeLabel)} ($chainageLabel)</name><styleUrl>#junction</styleUrl>")
                append("<ExtendedData>")
                append("<Data name=\"junction_type\"><value>$typeLabel</value></Data>")
                append("<Data name=\"branch_side\"><value>$sideLabel</value></Data>")
                append("<Data name=\"source\"><value>$sourceLabel</value></Data>")
                append("<Data name=\"nearest_chainage\"><value>${escape(chainageLabel)}</value></Data>")
                append("</ExtendedData>")
                append("<Point><coordinates>${j.longitude},${j.latitude},0</coordinates></Point></Placemark>")
            }
        }
        val coordinates = points.joinToString(" ") { "${it.longitude},${it.latitude},0" }
        val title = escape(report.name.ifBlank { "Trip Map route" })
        val kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>$title</name>
<Style id="route"><LineStyle><color>ffff8000</color><width>6</width></LineStyle></Style>
<Style id="chainage"><IconStyle><scale>1.1</scale></IconStyle></Style>
<Style id="junction"><IconStyle><color>ff0080ff</color><scale>1.2</scale></IconStyle></Style>
<Placemark><name>เส้นทางจริง</name><styleUrl>#route</styleUrl><LineString><tessellate>1</tessellate><coordinates>$coordinates</coordinates></LineString></Placemark>
<Folder><name>หลัก กม. ตามช่วงที่เลือก (${report.startChainage.direction.name})</name>$pins</Folder>
${if (report.junctions.isNotEmpty()) "<Folder><name>ทางแยก</name>$junctionPins</Folder>" else ""}
</Document></kml>"""
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("doc.kml")); zip.write(kml.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
    }

    private fun reportSections(report: TripReport): List<IntRange> {
        val start = report.selection.startRouteMeters
        val end = report.selection.endRouteMeters.coerceAtLeast(start + 1)
        val step = report.selection.intervalMeters.coerceIn(100, 1_000)
        return (start..end step step).map { it..min(end, it + step - 1) }
    }

    private fun drawPage(canvas: Canvas, report: TripReport, section: IntRange, yOffset: Int, height: Int, includeHeader: Boolean) {
        canvas.save(); canvas.translate(0f, yOffset.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans", Typeface.NORMAL) }
        fun text(value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
            paint.style = Paint.Style.FILL; paint.textSize = size; paint.color = color
            paint.typeface = Typeface.create("sans", if (bold) Typeface.BOLD else Typeface.NORMAL)
            canvas.drawText(value, x, y, paint)
        }
        text(report.name.ifBlank { "Trip Map — รายงานทริป" }, 64f, 72f, 42f, Color.rgb(44,44,43), true)
        text("ช่วง ${chainage(report.startChainage.displayMeters(section.first.toDouble()).toDouble(), report.startChainage.direction.name)} – ${chainage(report.startChainage.displayMeters(section.last.toDouble()).toDouble(), report.startChainage.direction.name)}", 64f, 116f, 27f, Color.DKGRAY)
        if (includeHeader) text("ระยะสุทธิ %.2f กม.   ระยะเคลื่อนที่รวม %.2f กม.".format(report.netDistanceMeters/1000, report.traveledDistanceMeters/1000), 64f, 154f, 24f, Color.DKGRAY)
        val bounds = RectF(64f, 190f, 1016f, (height - 110).toFloat())
        paint.color = Color.rgb(244,246,243); canvas.drawRoundRect(bounds, 20f, 20f, paint)
        drawRoute(canvas, report.points.filter { it.chainageMeters.toInt() in section }, bounds, paint, report.startChainage, section, report.selection.intervalMeters)
        text("ป้ายตามช่วงที่เลือก · สลับบน/ล่างพร้อมเส้นโยง", 64f, (height - 55).toFloat(), 22f, Color.GRAY)
        canvas.restore()
    }

    private fun drawRoute(canvas: Canvas, points: List<TrackPoint>, box: RectF, paint: Paint, config: StartChainageConfig, section: IntRange, intervalMeters: Int) {
        if (points.isEmpty()) return
        val minLat = points.minOf { it.latitude }; val maxLat = points.maxOf { it.latitude }
        val minLng = points.minOf { it.longitude }; val maxLng = points.maxOf { it.longitude }
        val latRange = max(0.00001, maxLat-minLat); val lngRange = max(0.00001, maxLng-minLng)
        fun xy(p: TrackPoint): PointF = PointF(
            box.left + 70 + ((p.longitude-minLng)/lngRange*(box.width()-140)).toFloat(),
            box.bottom - 70 - ((p.latitude-minLat)/latRange*(box.height()-140)).toFloat()
        )
        val path = Path(); points.forEachIndexed { i,p -> val q=xy(p); if(i==0) path.moveTo(q.x,q.y) else path.lineTo(q.x,q.y) }
        paint.style=Paint.Style.STROKE; paint.strokeCap=Paint.Cap.ROUND; paint.strokeJoin=Paint.Join.ROUND; paint.strokeWidth=18f; paint.color=Color.WHITE; canvas.drawPath(path,paint)
        paint.strokeWidth=11f; paint.color=Color.rgb(39,131,222); canvas.drawPath(path,paint)
        val maxChain = points.maxOf { it.chainageMeters }.toInt()
        for (m in section.first..min(maxChain, section.last) step intervalMeters.coerceIn(100, 1_000)) {
            val point = points.minByOrNull { abs(it.chainageMeters-m) } ?: continue
            val q=xy(point); paint.style=Paint.Style.FILL; paint.color=Color.WHITE; canvas.drawCircle(q.x,q.y,8f,paint)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=3f; paint.color=Color.rgb(39,131,222); canvas.drawCircle(q.x,q.y,8f,paint)
            val top = ((m - section.first) / intervalMeters.coerceAtLeast(100)) % 2 == 0
            val labelY = (q.y + if (top) -30f else 42f).coerceIn(box.top + 24f, box.bottom - 12f)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=2f; paint.color=Color.rgb(24,93,157); canvas.drawLine(q.x, q.y, q.x, labelY - 6f, paint)
            paint.style=Paint.Style.FILL; paint.textSize=17f; paint.typeface=Typeface.DEFAULT_BOLD; paint.color=Color.rgb(24,93,157)
            canvas.drawText(chainage(config.displayMeters(m.toDouble()).toDouble(), config.direction.name), q.x+12, labelY, paint)
        }
    }

    private fun chainage(meters: Double, suffix: String = ""): String {
        val rounded = (meters/100).roundToInt()*100
        return "กม.${rounded/1000}+${(rounded%1000).toString().padStart(3,'0')}" + if (suffix.isBlank()) "" else " $suffix"
    }
}
