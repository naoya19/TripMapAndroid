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
        val withId = if (junction.id == 0L) junction.copy(id = System.nanoTime()) else junction
        mutableState.value = current.copy(junctions = current.junctions + withId)
    }

    /** เพิ่มจุดทางแยก โดยข้ามจุดที่ใกล้กับจุดที่มีอยู่แล้วมาก (กันซ้ำเวลาผู้ใช้กดซิงก์ OSM หลายครั้ง) */
    fun addJunctions(newJunctions: List<JunctionPoint>) {
        if (newJunctions.isEmpty()) return
        val current = mutableState.value
        val toAdd = newJunctions
            .filter { candidate ->
                current.junctions.none { haversineMeters(it.latitude, it.longitude, candidate.latitude, candidate.longitude) < 15.0 }
            }
            .map { if (it.id == 0L) it.copy(id = System.nanoTime()) else it }
        if (toAdd.isNotEmpty()) mutableState.value = current.copy(junctions = current.junctions + toAdd)
    }

    fun removeJunction(id: Long) {
        val current = mutableState.value
        mutableState.value = current.copy(junctions = current.junctions.filterNot { it.id == id })
    }

    /** แก้คุณสมบัติจุดทางแยก — ใช้ตอนอยู่ในโหมดวาด (ยังไม่ได้หยุดทริป) */
    fun updateJunction(updated: JunctionPoint) {
        val current = mutableState.value
        mutableState.value = current.copy(
            junctions = current.junctions.map { if (it.id == updated.id) updated else it }
        )
    }

    // ── ช่วงของถนน (v1.5) ─────────────────────────────────────

    fun addSegment(segment: RoadSegment) {
        val current = mutableState.value
        val withId = if (segment.id == 0L) segment.copy(id = System.nanoTime()) else segment
        mutableState.value = current.copy(roadSegments = current.roadSegments + withId)
    }

    fun updateSegment(updated: RoadSegment) {
        val current = mutableState.value
        if (current.roadSegments.none { it.id == updated.id }) return
        mutableState.value = current.copy(
            roadSegments = current.roadSegments.map {
                if (it.id == updated.id) updated.copy(updatedAt = System.currentTimeMillis()) else it
            }
        )
    }

    fun removeSegment(id: Long) {
        val current = mutableState.value
        mutableState.value = current.copy(roadSegments = current.roadSegments.filterNot { it.id == id })
    }

    /** ลบช่วงที่ทับซ้อนกับ [start]–[end] ออกก่อนเพิ่มช่วงใหม่ — กันข้อมูลซ้อนกันตอน export */
    fun replaceSegmentRange(segment: RoadSegment) {
        val current = mutableState.value
        val keep = current.roadSegments.filterNot {
            it.id != segment.id &&
                    it.startChainageMeters <= segment.endChainageMeters &&
                    it.endChainageMeters >= segment.startChainageMeters
        }
        val withId = if (segment.id == 0L) segment.copy(id = System.nanoTime()) else segment
        mutableState.value = current.copy(roadSegments = keep + withId)
    }
}