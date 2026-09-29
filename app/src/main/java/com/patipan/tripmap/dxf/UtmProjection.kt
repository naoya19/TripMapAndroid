package com.patipan.tripmap.dxf

import kotlin.math.*

/**
 * แปลงพิกัด WGS84 (lat/lon) เป็นพิกัด UTM (เมตร) และเลือกโซนอัตโนมัติจากตำแหน่งจริง
 * (47N หรือ 48N สำหรับประเทศไทย, โซนอื่นถ้าอยู่นอกไทย) — ใช้สูตร Transverse Mercator
 * มาตรฐาน (Snyder) บน WGS84 ellipsoid โดยตรง ไม่ต้องพึ่ง library ภายนอก
 */
object UtmProjection {
    private const val A = 6_378_137.0             // WGS84 semi-major axis (m)
    private const val F = 1 / 298.257223563       // WGS84 flattening
    private const val K0 = 0.9996                  // UTM scale factor

    data class Zone(val number: Int, val northernHemisphere: Boolean) {
        val label: String get() = "$number${if (northernHemisphere) "N" else "S"}"
    }

    fun zoneFor(lat: Double, lon: Double): Zone {
        val number = floor((lon + 180) / 6).toInt() + 1
        return Zone(number, lat >= 0)
    }

    /** คืนพิกัด (easting, northing) หน่วยเมตร ตามโซนที่กำหนด */
    fun project(lat: Double, lon: Double, zone: Zone): DoubleArray {
        val e2 = F * (2 - F)
        val ePrime2 = e2 / (1 - e2)
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val lonOrigin = Math.toRadians(((zone.number - 1) * 6 - 180 + 3).toDouble())

        val n = A / sqrt(1 - e2 * sin(latRad).pow(2))
        val t = tan(latRad).pow(2)
        val c = ePrime2 * cos(latRad).pow(2)
        val aTerm = cos(latRad) * (lonRad - lonOrigin)

        val m = A * (
            (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256) * latRad
                - (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2 * e2 * e2 / 1024) * sin(2 * latRad)
                + (15 * e2 * e2 / 256 + 45 * e2 * e2 * e2 / 1024) * sin(4 * latRad)
                - (35 * e2 * e2 * e2 / 3072) * sin(6 * latRad)
            )

        val easting = K0 * n * (
            aTerm + (1 - t + c) * aTerm.pow(3) / 6 +
                (5 - 18 * t + t * t + 72 * c - 58 * ePrime2) * aTerm.pow(5) / 120
            ) + 500_000.0

        var northing = K0 * (
            m + n * tan(latRad) * (
                aTerm.pow(2) / 2 + (5 - t + 9 * c + 4 * c * c) * aTerm.pow(4) / 24 +
                    (61 - 58 * t + t * t + 600 * c - 330 * ePrime2) * aTerm.pow(6) / 720
                )
            )
        if (!zone.northernHemisphere) northing += 10_000_000.0

        return doubleArrayOf(easting, northing)
    }
}
