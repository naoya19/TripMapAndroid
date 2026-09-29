package com.patipan.tripmap.osm

import com.patipan.tripmap.tracking.JunctionPoint
import com.patipan.tripmap.tracking.JunctionSide
import com.patipan.tripmap.tracking.JunctionSource
import com.patipan.tripmap.tracking.JunctionType
import com.patipan.tripmap.tracking.TrackPoint
import com.patipan.tripmap.tracking.haversineMeters
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min

/**
 * ดึงข้อมูลทางแยกจาก OpenStreetMap (Overpass API) ให้ตรงกับเส้นทางที่บันทึกไว้
 *
 * Heuristic: ขอถนน (way ที่มี tag highway) ในกรอบพิกัดรอบเส้นทาง แล้วนับว่าแต่ละ node
 * มีกี่ way ที่แตกต่างกันมาบรรจบ (node degree) — node ที่มี degree >= 3 และอยู่ใกล้เส้นทาง
 * ถือว่าเป็นทางแยก: degree 3 = 3 แยก, degree >= 4 = 4 แยก
 *
 * นี่เป็น heuristic ที่ตรงกับ use case นี้ ไม่ใช่การวิเคราะห์ topology แบบเต็มรูปแบบ —
 * ความแม่นยำขึ้นกับว่าถนนใน OSM ตรงจุดนั้นถูกตัดแบ่ง (split) ที่ทางแยกจริงหรือไม่ ซึ่งเป็น
 * ธรรมเนียมทั่วไปของข้อมูล OSM แต่ไม่ได้การันตีทุกจุด
 *
 * ต้องเรียกจาก background thread (เช่น Dispatchers.IO) เพราะทำ network I/O แบบ blocking
 */
object OsmJunctionLookup {
    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    private const val MATCH_RADIUS_METERS = 15.0
    private const val BBOX_MARGIN_DEGREES = 0.003 // ~300 ม.

    sealed class Result {
        data class Success(val junctions: List<JunctionPoint>) : Result()
        data class Failure(val message: String) : Result()
    }

    fun fetchJunctions(points: List<TrackPoint>): Result {
        if (points.size < 2) return Result.Success(emptyList())
        return try {
            val minLat = points.minOf { it.latitude } - BBOX_MARGIN_DEGREES
            val maxLat = points.maxOf { it.latitude } + BBOX_MARGIN_DEGREES
            val minLon = points.minOf { it.longitude } - BBOX_MARGIN_DEGREES
            val maxLon = points.maxOf { it.longitude } + BBOX_MARGIN_DEGREES

            val query = """
                [out:json][timeout:25];
                way["highway"]($minLat,$minLon,$maxLat,$maxLon);
                out body;
                >;
                out skel qt;
            """.trimIndent()

            val json = postOverpass(query) ?: return Result.Failure("เชื่อมต่อ OSM ไม่สำเร็จ (ไม่มีอินเทอร์เน็ต หรือ Overpass ไม่ตอบสนอง)")
            val (ways, nodes) = parseElements(json)

            val waysAtNode = HashMap<Long, MutableSet<Long>>()
            for (way in ways) {
                for (nodeId in way.nodeIds.toSet()) {
                    waysAtNode.getOrPut(nodeId) { mutableSetOf() }.add(way.id)
                }
            }
            val degreeByNode = waysAtNode.mapValues { it.value.size }

            val candidates = nodes.filter { (degreeByNode[it.id] ?: 0) >= 3 }
            val junctions = mutableListOf<JunctionPoint>()
            for (node in candidates) {
                val nearest = points.minByOrNull { haversineMeters(it.latitude, it.longitude, node.lat, node.lon) } ?: continue
                if (haversineMeters(nearest.latitude, nearest.longitude, node.lat, node.lon) > MATCH_RADIUS_METERS) continue
                val degree = degreeByNode[node.id] ?: 0
                val type = if (degree >= 4) JunctionType.FOUR_WAY else JunctionType.THREE_WAY
                val side = if (type == JunctionType.FOUR_WAY) JunctionSide.BOTH else branchSide(points, node)
                junctions += JunctionPoint(
                    id = node.id,
                    latitude = node.lat, longitude = node.lon,
                    type = type, side = side, source = JunctionSource.OSM,
                    nearestChainageMeters = nearest.chainageMeters
                )
            }
            Result.Success(dedupe(junctions))
        } catch (e: Exception) {
            Result.Failure(e.message ?: "เกิดข้อผิดพลาดไม่ทราบสาเหตุ")
        }
    }

    private fun dedupe(list: List<JunctionPoint>): List<JunctionPoint> {
        val kept = mutableListOf<JunctionPoint>()
        for (j in list.sortedByDescending { it.type == JunctionType.FOUR_WAY }) {
            if (kept.none { haversineMeters(it.latitude, it.longitude, j.latitude, j.longitude) < MATCH_RADIUS_METERS }) {
                kept += j
            }
        }
        return kept
    }

    /** ประมาณด้านของแขนแยกจากทิศทางเส้นทาง ณ จุดที่ใกล้ node ที่สุด เทียบกับตำแหน่ง node */
    private fun branchSide(points: List<TrackPoint>, node: OsmNode): JunctionSide {
        val idx = points.indices.minByOrNull { haversineMeters(points[it].latitude, points[it].longitude, node.lat, node.lon) } ?: return JunctionSide.RIGHT
        val a = points[max(0, idx - 1)]
        val b = points[min(points.lastIndex, idx + 1)]
        val tangentLat = b.latitude - a.latitude
        val tangentLon = b.longitude - a.longitude
        val toNodeLat = node.lat - points[idx].latitude
        val toNodeLon = node.lon - points[idx].longitude
        val cross = tangentLon * toNodeLat - tangentLat * toNodeLon
        return if (cross > 0) JunctionSide.LEFT else JunctionSide.RIGHT
    }

    private data class OsmWay(val id: Long, val nodeIds: List<Long>)
    private data class OsmNode(val id: Long, val lat: Double, val lon: Double)

    private fun parseElements(json: JSONObject): Pair<List<OsmWay>, List<OsmNode>> {
        val elements = json.optJSONArray("elements") ?: return emptyList<OsmWay>() to emptyList()
        val ways = mutableListOf<OsmWay>()
        val nodes = mutableListOf<OsmNode>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            when (el.optString("type")) {
                "way" -> {
                    val nodesArray = el.optJSONArray("nodes") ?: continue
                    val ids = (0 until nodesArray.length()).map { nodesArray.getLong(it) }
                    ways += OsmWay(el.getLong("id"), ids)
                }
                "node" -> {
                    if (el.has("lat") && el.has("lon")) {
                        nodes += OsmNode(el.getLong("id"), el.getDouble("lat"), el.getDouble("lon"))
                    }
                }
            }
        }
        return ways to nodes
    }

    private fun postOverpass(query: String): JSONObject? {
        val connection = (URL(OVERPASS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        return try {
            OutputStreamWriter(connection.outputStream).use { it.write("data=" + URLEncoder.encode(query, "UTF-8")) }
            if (connection.responseCode !in 200..299) return null
            val body = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            JSONObject(body)
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
