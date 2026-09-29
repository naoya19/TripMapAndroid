package com.patipan.tripmap.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object TrackingBus {
    private val mutableState = MutableStateFlow(TrackingState())
    val state = mutableState.asStateFlow()
    fun update(value: TrackingState) { mutableState.value = value }

    /** เพิ่มจุดทางแยกที่ผู้ใช้แตะระบุเอง หรือที่ดึงมาจาก OSM ระหว่างทริปที่กำลังบันทึก */
    fun addJunction(junction: JunctionPoint) {
        val current = mutableState.value
        mutableState.value = current.copy(junctions = current.junctions + junction)
    }

    /** เพิ่มจุดทางแยก โดยข้ามจุดที่ใกล้กับจุดที่มีอยู่แล้วมาก (กันซ้ำเวลาผู้ใช้กดซิงก์ OSM หลายครั้ง) */
    fun addJunctions(newJunctions: List<JunctionPoint>) {
        if (newJunctions.isEmpty()) return
        val current = mutableState.value
        val toAdd = newJunctions.filter { candidate ->
            current.junctions.none { haversineMeters(it.latitude, it.longitude, candidate.latitude, candidate.longitude) < 15.0 }
        }
        if (toAdd.isNotEmpty()) mutableState.value = current.copy(junctions = current.junctions + toAdd)
    }

    fun removeJunction(id: Long) {
        val current = mutableState.value
        mutableState.value = current.copy(junctions = current.junctions.filterNot { it.id == id })
    }
}
