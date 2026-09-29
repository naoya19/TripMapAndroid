package com.patipan.tripmap.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.patipan.tripmap.TripMapApplication
import com.patipan.tripmap.data.TripEntity
import com.patipan.tripmap.data.TripWithPoints
import com.patipan.tripmap.data.JunctionEntity
import com.patipan.tripmap.share.TripArchive
import com.patipan.tripmap.tracking.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
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

    fun openTrip(id: Long) {
        viewModelScope.launch { mutableSelectedTrip.value = dao.tripWithPoints(id) }
    }

    fun renameTrip(id: Long, name: String) {
        val cleanName = name.trim().take(80)
        viewModelScope.launch {
            dao.updateTripName(id, cleanName)
            if (mutableSelectedTrip.value?.trip?.id == id) {
                mutableSelectedTrip.value = dao.tripWithPoints(id)
            }
        }
    }

    fun deleteTrip(id: Long) {
        viewModelScope.launch {
            dao.deleteTrip(id)
            if (mutableSelectedTrip.value?.trip?.id == id) mutableSelectedTrip.value = null
        }
    }

    fun deleteJunction(tripId: Long, junctionId: Long) {
        viewModelScope.launch {
            dao.deleteJunction(junctionId)
            if (mutableSelectedTrip.value?.trip?.id == tripId) mutableSelectedTrip.value = dao.tripWithPoints(tripId)
        }
    }

    fun addJunction(tripId: Long, junction: JunctionPoint) {
        viewModelScope.launch {
            dao.insertJunction(junction.toEntity(tripId))
            if (mutableSelectedTrip.value?.trip?.id == tripId) mutableSelectedTrip.value = dao.tripWithPoints(tripId)
        }
    }

    fun addJunctions(tripId: Long, junctions: List<JunctionPoint>) {
        viewModelScope.launch {
            if (junctions.isNotEmpty()) dao.insertJunctions(junctions.map { it.toEntity(tripId) })
            if (mutableSelectedTrip.value?.trip?.id == tripId) mutableSelectedTrip.value = dao.tripWithPoints(tripId)
        }
    }

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
            val junctions = data.junctions.map { e ->
                JunctionPoint(
                    id = e.id, latitude = e.latitude, longitude = e.longitude,
                    type = runCatching { JunctionType.valueOf(e.type) }.getOrDefault(JunctionType.THREE_WAY),
                    side = runCatching { JunctionSide.valueOf(e.side) }.getOrDefault(JunctionSide.RIGHT),
                    source = runCatching { JunctionSource.valueOf(e.source) }.getOrDefault(JunctionSource.MANUAL),
                    nearestChainageMeters = e.nearestChainageMeters, timestamp = e.timestamp
                )
            }
            runCatching {
                withContext(Dispatchers.IO) { TripArchive.write(context, uri, data.trip, points, junctions) }
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

    fun closeTrip() { mutableSelectedTrip.value = null }

    private fun JunctionPoint.toEntity(tripId: Long) = JunctionEntity(
        tripId = tripId, latitude = latitude, longitude = longitude,
        type = type.name, side = side.name, source = source.name,
        nearestChainageMeters = nearestChainageMeters, timestamp = timestamp
    )
}