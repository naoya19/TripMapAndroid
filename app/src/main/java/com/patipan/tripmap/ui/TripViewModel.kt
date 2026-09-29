package com.patipan.tripmap.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.patipan.tripmap.TripMapApplication
import com.patipan.tripmap.data.JunctionEntity
import com.patipan.tripmap.data.RoadSegmentEntity
import com.patipan.tripmap.data.TripEntity
import com.patipan.tripmap.data.TripWithPoints
import com.patipan.tripmap.share.TripArchive
import com.patipan.tripmap.tracking.JunctionPoint
import com.patipan.tripmap.tracking.JunctionSide
import com.patipan.tripmap.tracking.JunctionSource
import com.patipan.tripmap.tracking.JunctionType
import com.patipan.tripmap.tracking.RoadSegment
import com.patipan.tripmap.tracking.TrackPoint
import com.patipan.tripmap.tracking.TrackingBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TripViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = (application as TripMapApplication).database.tripDao()

    val tracking = TrackingBus.state
    val trips = dao.observeTrips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<TripEntity>())

    private val mutableSelectedTrip = MutableStateFlow<TripWithPoints?>(null)
    val selectedTrip = mutableSelectedTrip.asStateFlow()

    private val mutableExportMessage = MutableStateFlow<String?>(null)
    val exportMessage = mutableExportMessage.asStateFlow()
    fun clearExportMessage() { mutableExportMessage.value = null }

    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    fun clearNotice() { mutableNotice.value = null }

    fun openTrip(id: Long) {
        viewModelScope.launch { mutableSelectedTrip.value = dao.tripWithPoints(id) }
    }

    fun closeTrip() { mutableSelectedTrip.value = null }

    fun renameTrip(id: Long, name: String) {
        val cleanName = name.trim().take(80)
        viewModelScope.launch {
            dao.updateTripName(id, cleanName)
            refreshSelected(id)
        }
    }

    fun deleteTrip(id: Long) {
        viewModelScope.launch {
            dao.deleteTrip(id)
            if (mutableSelectedTrip.value?.trip?.id == id) mutableSelectedTrip.value = null
        }
    }

    // ── จุดทางแยก ─────────────────────────────────────────────

    fun addJunction(tripId: Long, junction: JunctionPoint) {
        viewModelScope.launch {
            dao.insertJunction(junction.toEntity(tripId))
            refreshSelected(tripId)
        }
    }

    fun addJunctions(tripId: Long, junctions: List<JunctionPoint>) {
        viewModelScope.launch {
            if (junctions.isNotEmpty()) dao.insertJunctions(junctions.map { it.toEntity(tripId) })
            refreshSelected(tripId)
        }
    }

    fun deleteJunction(tripId: Long, junctionId: Long) {
        viewModelScope.launch {
            dao.deleteJunction(junctionId)
            refreshSelected(tripId)
        }
    }

    /** แก้คุณสมบัติจุดทางแยก — ประเภท ด้าน หมายเหตุ และค่าถนนที่วัดเฉพาะจุด */
    fun updateJunction(tripId: Long, junction: JunctionPoint) {
        viewModelScope.launch {
            dao.updateJunction(
                id = junction.id,
                type = junction.type.name,
                side = junction.side.name,
                note = junction.note,
                lanes = junction.lanes,
                width = junction.widthMeters,
                shoulder = junction.shoulderMeters,
                surface = junction.surface,
                photoRef = junction.photoRef
            )
            refreshSelected(tripId)
        }
    }

    // ── ช่วงของถนน (v1.5) ─────────────────────────────────────

    fun saveSegment(tripId: Long, segment: RoadSegment) {
        val start = minOf(segment.startChainageMeters, segment.endChainageMeters)
        val end = maxOf(segment.startChainageMeters, segment.endChainageMeters)
        if (end - start < 1) {
            mutableNotice.value = "ช่วงต้องยาวอย่างน้อย 1 เมตร"
            return
        }
        // ตรวจกับ entity ใน Room โดยตรง — ไม่ต้องแปลงเป็น domain เพราะต้องการแค่ name กับ id
        val existing = mutableSelectedTrip.value?.roadSegments.orEmpty()
        val conflicts = existing.filter {
            it.id != segment.id &&
                    it.startChainageMeters <= end &&
                    it.endChainageMeters >= start
        }
        if (conflicts.isNotEmpty()) {
            val names = conflicts.joinToString(" · ") { it.name.ifBlank { "ไม่มีชื่อ" } }
            mutableNotice.value = "ช่วงนี้ทับซ้อนกับ $names — ช่วงเดิมจะถูกแทนที่"
        }
        viewModelScope.launch {
            val entity = segment.copy(
                startChainageMeters = start,
                endChainageMeters = end,
                updatedAt = System.currentTimeMillis()
            ).toEntity(tripId)
            if (entity.id == 0L) {
                dao.insertSegment(entity)
            } else {
                dao.updateSegment(
                    id = entity.id,
                    name = entity.name,
                    start = entity.startChainageMeters,
                    end = entity.endChainageMeters,
                    lanes = entity.lanes,
                    width = entity.widthMeters,
                    shoulder = entity.shoulderMeters,
                    surface = entity.surface,
                    note = entity.note,
                    updatedAt = entity.updatedAt
                )
            }
            conflicts.forEach { dao.deleteSegment(it.id) }
            refreshSelected(tripId)
        }
    }

    fun deleteSegment(tripId: Long, segmentId: Long) {
        viewModelScope.launch {
            dao.deleteSegment(segmentId)
            refreshSelected(tripId)
        }
    }

    // ── import / export ──────────────────────────────────────

    fun exportTrip(context: Context, tripId: Long, uri: Uri) {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) { dao.tripWithPoints(tripId) }
            if (data == null) {
                mutableExportMessage.value = "ไม่พบทริปที่เลือก"
                return@launch
            }
            val points = data.points.sortedBy { it.sequence }.map {
                TrackPoint(it.latitude, it.longitude, it.accuracyMeters, it.timestamp, it.chainageMeters)
            }
            val junctions = data.junctions.map { it.toDomain() }
            runCatching {
                withContext(Dispatchers.IO) {
                    TripArchive.write(context, uri, data.trip, points, junctions)
                }
            }.onSuccess {
                mutableExportMessage.value = "บันทึกไฟล์ทริปเรียบร้อยแล้ว"
            }.onFailure { e ->
                mutableExportMessage.value = "บันทึกไฟล์ไม่สำเร็จ: ${e.message ?: "ไม่ทราบสาเหตุ"}"
            }
        }
    }

    fun importTrip(context: Context, uri: Uri, onDone: (Long) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val imported = TripArchive.read(context, uri)
                    TripArchive.insertIntoDatabase(context, imported)
                }
            }
            result.onSuccess { id -> onDone(id) }
                .onFailure { e -> onError(e.message ?: "นำเข้าไฟล์ไม่สำเร็จ") }
        }
    }

    // ── ภายใน ────────────────────────────────────────────────

    private suspend fun refreshSelected(tripId: Long) {
        if (mutableSelectedTrip.value?.trip?.id == tripId) {
            mutableSelectedTrip.value = dao.tripWithPoints(tripId)
        }
    }
}

// ── mapper: entity ↔ domain ──────────────────────────────────

fun JunctionPoint.toEntity(tripId: Long) = JunctionEntity(
    tripId = tripId,
    latitude = latitude,
    longitude = longitude,
    type = type.name,
    side = side.name,
    source = source.name,
    nearestChainageMeters = nearestChainageMeters,
    timestamp = timestamp,
    note = note,
    lanes = lanes,
    widthMeters = widthMeters,
    shoulderMeters = shoulderMeters,
    surface = surface,
    photoRef = photoRef
)

fun JunctionEntity.toDomain() = JunctionPoint(
    id = id,
    latitude = latitude,
    longitude = longitude,
    type = runCatching { JunctionType.valueOf(type) }.getOrDefault(JunctionType.THREE_WAY),
    side = runCatching { JunctionSide.valueOf(side) }.getOrDefault(JunctionSide.RIGHT),
    source = runCatching { JunctionSource.valueOf(source) }.getOrDefault(JunctionSource.MANUAL),
    nearestChainageMeters = nearestChainageMeters,
    timestamp = timestamp,
    note = note,
    lanes = lanes,
    widthMeters = widthMeters,
    shoulderMeters = shoulderMeters,
    surface = surface,
    photoRef = photoRef
)

fun RoadSegment.toEntity(tripId: Long) = RoadSegmentEntity(
    id = id,
    tripId = tripId,
    name = name,
    startChainageMeters = startChainageMeters,
    endChainageMeters = endChainageMeters,
    lanes = lanes,
    widthMeters = widthMeters,
    shoulderMeters = shoulderMeters,
    surface = surface,
    note = note,
    colorIndex = colorIndex,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun RoadSegmentEntity.toDomain() = RoadSegment(
    id = id,
    name = name,
    startChainageMeters = startChainageMeters,
    endChainageMeters = endChainageMeters,
    lanes = lanes,
    widthMeters = widthMeters,
    shoulderMeters = shoulderMeters,
    surface = surface,
    note = note,
    colorIndex = colorIndex,
    createdAt = createdAt,
    updatedAt = updatedAt
)