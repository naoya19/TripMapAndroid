package com.patipan.tripmap.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.patipan.tripmap.TripMapApplication
import com.patipan.tripmap.data.TripEntity
import com.patipan.tripmap.data.TripWithPoints
import com.patipan.tripmap.data.JunctionEntity
import com.patipan.tripmap.tracking.JunctionPoint
import com.patipan.tripmap.share.TripArchive
import com.patipan.tripmap.tracking.TrackingBus
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class TripViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = (application as TripMapApplication).database.tripDao()
    val tracking = TrackingBus.state
    val trips = dao.observeTrips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<TripEntity>())

    private val mutableSelectedTrip = MutableStateFlow<TripWithPoints?>(null)
    val selectedTrip = mutableSelectedTrip.asStateFlow()

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
            dao.insertJunction(JunctionEntity(
                tripId = tripId, latitude = junction.latitude, longitude = junction.longitude,
                type = junction.type.name, side = junction.side.name, source = junction.source.name,
                nearestChainageMeters = junction.nearestChainageMeters, timestamp = junction.timestamp
            ))
            if (mutableSelectedTrip.value?.trip?.id == tripId) mutableSelectedTrip.value = dao.tripWithPoints(tripId)
        }
    }

    fun addJunctions(tripId: Long, junctions: List<JunctionPoint>) {
        viewModelScope.launch {
            if (junctions.isNotEmpty()) dao.insertJunctions(junctions.map { j ->
                JunctionEntity(tripId = tripId, latitude = j.latitude, longitude = j.longitude,
                    type = j.type.name, side = j.side.name, source = j.source.name,
                    nearestChainageMeters = j.nearestChainageMeters, timestamp = j.timestamp)
            })
            if (mutableSelectedTrip.value?.trip?.id == tripId) mutableSelectedTrip.value = dao.tripWithPoints(tripId)
        }
    }

    fun exportTrip(context: android.content.Context, tripId: Long, uri: android.net.Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val data = dao.tripWithPoints(tripId) ?: return@launch
            val points = data.points.sortedBy { it.sequence }.map { com.patipan.tripmap.tracking.TrackPoint(it.latitude, it.longitude, it.accuracyMeters, it.timestamp, it.chainageMeters) }
            val junctions = data.junctions.map { j -> com.patipan.tripmap.tracking.JunctionPoint(
                id = j.id, latitude = j.latitude, longitude = j.longitude,
                type = runCatching { com.patipan.tripmap.tracking.JunctionType.valueOf(j.type) }.getOrDefault(com.patipan.tripmap.tracking.JunctionType.THREE_WAY),
                side = runCatching { com.patipan.tripmap.tracking.JunctionSide.valueOf(j.side) }.getOrDefault(com.patipan.tripmap.tracking.JunctionSide.RIGHT),
                source = runCatching { com.patipan.tripmap.tracking.JunctionSource.valueOf(j.source) }.getOrDefault(com.patipan.tripmap.tracking.JunctionSource.MANUAL),
                nearestChainageMeters = j.nearestChainageMeters, timestamp = j.timestamp
            ) }
            TripArchive.write(context, uri, data.trip, points, junctions)
        }
    }

    fun importTrip(context: android.content.Context, uri: android.net.Uri, onDone: (Long) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val imported = TripArchive.read(context, uri)
                TripArchive.insertIntoDatabase(context, imported)
            }.onSuccess { id ->
                launch(kotlinx.coroutines.Dispatchers.Main) { onDone(id) }
            }.onFailure { e ->
                launch(kotlinx.coroutines.Dispatchers.Main) { onError(e.message ?: "นำเข้าไฟล์ไม่สำเร็จ") }
            }
        }
    }

    fun closeTrip() { mutableSelectedTrip.value = null }
}
