package com.patipan.tripmap.share

import android.content.Context
import android.net.Uri
import com.patipan.tripmap.TripMapApplication
import com.patipan.tripmap.data.JunctionEntity
import com.patipan.tripmap.data.RoadSegmentEntity
import com.patipan.tripmap.data.TrackPointEntity
import com.patipan.tripmap.data.TripEntity
import com.patipan.tripmap.tracking.*
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable Trip Map package. One .tripmap file contains the complete saved trip. */
data class ImportedTrip(
    val trip: TripEntity,
    val points: List<TrackPoint>,
    val junctions: List<JunctionPoint>,
    val roadSegments: List<RoadSegment> = emptyList()
)

object TripArchive {
    private const val VERSION = "1"

    fun write(
        context: Context,
        uri: Uri,
        trip: TripEntity,
        points: List<TrackPoint>,
        junctions: List<JunctionPoint>,
        roadSegments: List<RoadSegment> = emptyList()
    ) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            ZipOutputStream(out).use { zip ->
                val meta = Properties().apply {
                    setProperty("format", "TripMap")
                    setProperty("version", VERSION)
                    setProperty("name", trip.name)
                    setProperty("startedAt", trip.startedAt.toString())
                    setProperty("endedAt", trip.endedAt.toString())
                    setProperty("netDistanceMeters", trip.netDistanceMeters.toString())
                    setProperty("traveledDistanceMeters", trip.traveledDistanceMeters.toString())
                    setProperty("durationMillis", trip.durationMillis.toString())
                    setProperty("startChainageMeters", trip.startChainageMeters.toString())
                    setProperty("chainageDirection", trip.chainageDirection)
                }
                zip.putNextEntry(ZipEntry("trip.properties"))
                val metaBytes = ByteArrayOutputStream().also { baos ->
                    OutputStreamWriter(baos, StandardCharsets.UTF_8).use { meta.store(it, "Trip Map portable trip") }
                }.toByteArray()
                zip.write(metaBytes)
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("points.csv"))
                zip.write("sequence,latitude,longitude,accuracyMeters,timestamp,chainageMeters\n".toByteArray(StandardCharsets.UTF_8))
                points.forEachIndexed { index, p ->
                    val line = listOf(index, p.latitude, p.longitude, p.accuracyMeters, p.timestamp, p.chainageMeters).joinToString(",") + "\n"
                    zip.write(line.toByteArray(StandardCharsets.UTF_8))
                }
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("junctions.csv"))
                zip.write("id,latitude,longitude,type,side,source,nearestChainageMeters,timestamp,note,lanes,widthMeters,shoulderMeters,surface,photoRef\n".toByteArray(StandardCharsets.UTF_8))
                junctions.forEach { j ->
                    val line = listOf(
                        j.id, j.latitude, j.longitude, j.type.name, j.side.name, j.source.name,
                        j.nearestChainageMeters, j.timestamp,
                        csvText(j.note), j.lanes ?: "", j.widthMeters ?: "", j.shoulderMeters ?: "",
                        j.surface, csvText(j.photoRef ?: "")
                    ).joinToString(",") + "\n"
                    zip.write(line.toByteArray(StandardCharsets.UTF_8))
                }
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("road_segments.csv"))
                zip.write("name,startChainageMeters,endChainageMeters,lanes,widthMeters,shoulderMeters,surface,note,colorIndex\n".toByteArray(StandardCharsets.UTF_8))
                roadSegments.forEach { s ->
                    val line = listOf(
                        csvText(s.name), s.startChainageMeters, s.endChainageMeters, s.lanes,
                        s.widthMeters ?: "", s.shoulderMeters ?: "", s.surface,
                        csvText(s.note), s.colorIndex
                    ).joinToString(",") + "\n"
                    zip.write(line.toByteArray(StandardCharsets.UTF_8))
                }
                zip.closeEntry()
            }
        } ?: error("ไม่สามารถบันทึกไฟล์ได้")
    }

    fun read(context: Context, uri: Uri): ImportedTrip {
        var meta: Properties? = null
        val points = mutableListOf<TrackPoint>()
        val junctions = mutableListOf<JunctionPoint>()
        val segments = mutableListOf<RoadSegment>()

        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val bytes = ByteArrayOutputStream().also { baos ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n <= 0) break
                            baos.write(buffer, 0, n)
                        }
                    }.toByteArray()
                    val text = String(bytes, StandardCharsets.UTF_8)
                    when (entry.name) {
                        "trip.properties" -> Properties().also { it.load(text.reader()); meta = it }
                        "points.csv" -> text.lineSequence().drop(1).forEach { line ->
                            val v = line.split(',')
                            if (v.size >= 6) points += TrackPoint(
                                v[1].toDouble(), v[2].toDouble(), v[3].toFloat(), v[4].toLong(), v[5].toDouble()
                            )
                        }
                        "junctions.csv" -> text.lineSequence().drop(1).forEach { line ->
                            val v = splitCsv(line)
                            if (v.size >= 8) {
                                val type = runCatching { JunctionType.valueOf(v[3]) }.getOrDefault(JunctionType.THREE_WAY)
                                val side = runCatching { JunctionSide.valueOf(v[4]) }.getOrDefault(JunctionSide.RIGHT)
                                val source = runCatching { JunctionSource.valueOf(v[5]) }.getOrDefault(JunctionSource.MANUAL)
                                junctions += JunctionPoint(
                                    id = v[0].toLong(), latitude = v[1].toDouble(), longitude = v[2].toDouble(),
                                    type = type, side = side, source = source,
                                    nearestChainageMeters = v[6].toDouble(), timestamp = v[7].toLong(),
                                    note = v.getOrNull(8)?.takeIf { it.isNotEmpty() }.orEmpty(),
                                    lanes = v.getOrNull(9)?.toIntOrNull(),
                                    widthMeters = v.getOrNull(10)?.toDoubleOrNull(),
                                    shoulderMeters = v.getOrNull(11)?.toDoubleOrNull(),
                                    surface = v.getOrNull(12)?.takeIf { it.isNotEmpty() } ?: com.patipan.tripmap.data.SurfaceKind.UNKNOWN,
                                    photoRef = v.getOrNull(13)?.takeIf { it.isNotEmpty() }
                                )
                            }
                        }
                        "road_segments.csv" -> text.lineSequence().drop(1).forEach { line ->
                            val v = splitCsv(line)
                            if (v.size >= 9) {
                                segments += RoadSegment(
                                    name = v[0], startChainageMeters = v[1].toInt(), endChainageMeters = v[2].toInt(),
                                    lanes = v[3].toInt(), widthMeters = v[4].toDoubleOrNull(),
                                    shoulderMeters = v[5].toDoubleOrNull(),
                                    surface = v[6].takeIf { it.isNotEmpty() } ?: com.patipan.tripmap.data.SurfaceKind.UNKNOWN,
                                    note = v[7], colorIndex = v[8].toInt()
                                )
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("ไม่สามารถเปิดไฟล์ได้")

        val p = meta ?: error("ไฟล์นี้ไม่ใช่ไฟล์ Trip Map หรือไฟล์เสียหาย")
        if (p.getProperty("format") != "TripMap") error("ไฟล์นี้ไม่ใช่ไฟล์ Trip Map")
        val trip = TripEntity(
            name = p.getProperty("name", ""),
            startedAt = p.getProperty("startedAt").toLong(),
            endedAt = p.getProperty("endedAt").toLong(),
            netDistanceMeters = p.getProperty("netDistanceMeters").toDouble(),
            traveledDistanceMeters = p.getProperty("traveledDistanceMeters").toDouble(),
            durationMillis = p.getProperty("durationMillis").toLong(),
            startChainageMeters = p.getProperty("startChainageMeters", "0").toInt(),
            chainageDirection = p.getProperty("chainageDirection", "LT")
        )
        return ImportedTrip(trip, points, junctions, segments)
    }

    fun insertIntoDatabase(context: Context, imported: ImportedTrip): Long {
        val dao = (context.applicationContext as TripMapApplication).database.tripDao()
        var id = 0L
        kotlinx.coroutines.runBlocking {
            id = dao.insertTrip(imported.trip)
            dao.insertPoints(imported.points.mapIndexed { index, p ->
                TrackPointEntity(0, id, index, p.latitude, p.longitude, p.accuracyMeters, p.timestamp, p.chainageMeters)
            })
            if (imported.junctions.isNotEmpty()) dao.insertJunctions(imported.junctions.map { j ->
                JunctionEntity(
                    0, id, j.latitude, j.longitude, j.type.name, j.side.name, j.source.name,
                    j.nearestChainageMeters, j.timestamp,
                    note = j.note, lanes = j.lanes, widthMeters = j.widthMeters,
                    shoulderMeters = j.shoulderMeters, surface = j.surface, photoRef = j.photoRef
                )
            })
            if (imported.roadSegments.isNotEmpty()) imported.roadSegments.forEach { s ->
                dao.insertSegment(RoadSegmentEntity(
                    0, id, s.name, s.startChainageMeters, s.endChainageMeters, s.lanes,
                    s.widthMeters, s.shoulderMeters, s.surface, s.note, s.colorIndex,
                    s.createdAt, s.updatedAt
                ))
            }
        }
        return id
    }

    /** escape ค่าที่มี comma, quote หรือขึ้นบรรทัดใหม่ เพื่อไม่ให้ CSV แตกคอลัมน์ */
    private fun csvText(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    /** split CSV ที่รองรับค่าที่ถูกครอบด้วย " และ "" escape */
    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}