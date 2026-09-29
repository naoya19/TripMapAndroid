package com.patipan.tripmap.share

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.patipan.tripmap.data.JunctionEntity
import com.patipan.tripmap.data.TrackPointEntity
import com.patipan.tripmap.data.TripEntity
import com.patipan.tripmap.tracking.JunctionPoint
import com.patipan.tripmap.tracking.TrackPoint
import com.patipan.tripmap.tracking.StartChainageConfig
import java.io.BufferedReader
import java.io.InputStreamReader
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
    val junctions: List<JunctionPoint>
)

object TripArchive {
    private const val VERSION = "1"

    fun write(context: Context, uri: Uri, trip: TripEntity, points: List<TrackPoint>, junctions: List<JunctionPoint>) {
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
                val metaBytes = java.io.ByteArrayOutputStream().also { baos ->
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
                zip.write("id,latitude,longitude,type,side,source,nearestChainageMeters,timestamp\n".toByteArray(StandardCharsets.UTF_8))
                junctions.forEach { j ->
                    val line = listOf(j.id, j.latitude, j.longitude, j.type.name, j.side.name, j.source.name, j.nearestChainageMeters, j.timestamp).joinToString(",") + "\n"
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
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val bytes = java.io.ByteArrayOutputStream().also { baos ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n <= 0) break
                            baos.write(buffer, 0, n)
                        }
                    }.toByteArray()
                    val text = String(bytes, StandardCharsets.UTF_8)
                    when (entry.name) {
                        "trip.properties" -> Properties().also { it.load(java.io.StringReader(text)); meta = it }
                        "points.csv" -> text.lineSequence().drop(1).forEach { line ->
                            val v = line.split(',')
                            if (v.size >= 6) points += TrackPoint(v[1].toDouble(), v[2].toDouble(), v[3].toFloat(), v[4].toLong(), v[5].toDouble())
                        }
                        "junctions.csv" -> text.lineSequence().drop(1).forEach { line ->
                            val v = line.split(',')
                            if (v.size >= 8) {
                                val type = runCatching { com.patipan.tripmap.tracking.JunctionType.valueOf(v[3]) }.getOrDefault(com.patipan.tripmap.tracking.JunctionType.THREE_WAY)
                                val side = runCatching { com.patipan.tripmap.tracking.JunctionSide.valueOf(v[4]) }.getOrDefault(com.patipan.tripmap.tracking.JunctionSide.RIGHT)
                                val source = runCatching { com.patipan.tripmap.tracking.JunctionSource.valueOf(v[5]) }.getOrDefault(com.patipan.tripmap.tracking.JunctionSource.MANUAL)
                                junctions += JunctionPoint(v[0].toLong(), v[1].toDouble(), v[2].toDouble(), type, side, source, v[6].toDouble(), v[7].toLong())
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
        return ImportedTrip(trip, points, junctions)
    }

    fun insertIntoDatabase(context: Context, imported: ImportedTrip): Long {
        val dao = (context.applicationContext as com.patipan.tripmap.TripMapApplication).database.tripDao()
        var id = 0L
        kotlinx.coroutines.runBlocking {
            id = dao.insertTrip(imported.trip)
            dao.insertPoints(imported.points.mapIndexed { index, p ->
                TrackPointEntity(0, id, index, p.latitude, p.longitude, p.accuracyMeters, p.timestamp, p.chainageMeters)
            })
            if (imported.junctions.isNotEmpty()) dao.insertJunctions(imported.junctions.map { j ->
                JunctionEntity(0, id, j.latitude, j.longitude, j.type.name, j.side.name, j.source.name, j.nearestChainageMeters, j.timestamp)
            })
        }
        return id
    }
}
