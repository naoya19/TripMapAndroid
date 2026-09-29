package com.patipan.tripmap.dxf

import java.io.File

/**
 * เขียนไฟล์ DXF แบบ ASCII รูปแบบ R12 (AC1009) ซึ่งเปิดได้กับ AutoCAD แทบทุกเวอร์ชัน
 * รองรับเท่าที่งานนี้ต้องใช้: LAYER, เส้นประ (LTYPE), text style ฟอนต์ไทย, POLYLINE (2D),
 * LINE, TEXT — เขียนด้วยมือแบบ group-code ตรงไปตรงมา ไม่พึ่ง library ภายนอก
 */
class DxfWriter {
    private val sb = StringBuilder()
    private val layers = linkedMapOf<String, Pair<Int, String>>() // name -> (colorIndex, linetype)

    private fun code(code: Int, value: String) { sb.append(code).append('\n').append(value).append('\n') }
    private fun code(code: Int, value: Double) { sb.append(code).append('\n').append(value).append('\n') }
    private fun code(code: Int, value: Int) { sb.append(code).append('\n').append(value).append('\n') }

    fun defineLayer(name: String, colorIndex: Int, linetype: String = "CONTINUOUS") {
        layers[name] = colorIndex to linetype
    }

    fun writeHeader(minX: Double = 0.0, minY: Double = 0.0, maxX: Double = 0.0, maxY: Double = 0.0) {
        code(0, "SECTION"); code(2, "HEADER")
        // $ACADVER เป็นตัวที่สำคัญที่สุด — ถ้าไม่มี AutoCAD ตัวจริงจะไม่รู้ว่าไฟล์เป็น DXF เวอร์ชันไหน
        // และมักจะเปิดไม่ได้เลย (ต่างจาก viewer อื่นที่เดาโครงสร้างเอาเองได้) ในขณะที่ viewer ทั่วไปเปิดได้อยู่แล้ว
        code(9, "\$ACADVER"); code(1, "AC1009") // R12
        code(9, "\$DWGCODEPAGE"); code(3, "ANSI_874") // Windows Thai code page; avoids UTF-8 corruption in AutoCAD R12 DXF
        code(9, "\$INSUNITS"); code(70, 6) // 6 = เมตร
        code(9, "\$EXTMIN"); code(10, minX); code(20, minY)
        code(9, "\$EXTMAX"); code(10, maxX); code(20, maxY)
        code(0, "ENDSEC")
    }

    /** ต้องมี section นี้ประกาศไว้เสมอแม้จะว่างเปล่า — AutoCAD คาดหวัง HEADER, TABLES, BLOCKS, ENTITIES ตามลำดับ */
    fun writeEmptyBlocks() {
        code(0, "SECTION"); code(2, "BLOCKS")
        code(0, "ENDSEC")
    }

    fun writeTables() {
        code(0, "SECTION"); code(2, "TABLES")

        code(0, "TABLE"); code(2, "LTYPE"); code(70, 2)
        code(0, "LTYPE"); code(2, "CONTINUOUS"); code(70, 0); code(3, "Solid line"); code(72, 65); code(73, 0); code(40, 0.0)
        code(0, "LTYPE"); code(2, "DASHED"); code(70, 0); code(3, "Dashed line"); code(72, 65); code(73, 2); code(40, 1.5)
        code(49, 1.0); code(74, 0); code(49, -0.5); code(74, 0)
        code(0, "ENDTAB")

        code(0, "TABLE"); code(2, "LAYER"); code(70, layers.size)
        layers.forEach { (name, props) ->
            code(0, "LAYER"); code(2, name); code(70, 0); code(62, props.first); code(6, props.second)
        }
        code(0, "ENDTAB")

        code(0, "TABLE"); code(2, "STYLE"); code(70, 1)
        // ใช้ Tahoma เพราะมีตัวอักษรไทย — ป้องกันป้าย กม. ขึ้นเป็น ?? เหมือนฟอนต์ shx เริ่มต้น
        code(0, "STYLE"); code(2, "THAI"); code(70, 0); code(40, 0.0); code(41, 1.0); code(50, 0.0); code(71, 0); code(42, 2.5); code(3, "Tahoma.ttf")
        code(0, "ENDTAB")

        code(0, "ENDSEC")
    }

    fun beginEntities() { code(0, "SECTION"); code(2, "ENTITIES") }
    fun endEntities() { code(0, "ENDSEC") }

    fun writePolyline(layer: String, points: List<DoubleArray>, closed: Boolean = false, linetype: String? = null) {
        if (points.size < 2) return
        code(0, "POLYLINE"); code(8, layer)
        linetype?.let { code(6, it) }
        code(66, 1); code(10, 0.0); code(20, 0.0); code(30, 0.0); code(70, if (closed) 1 else 0)
        points.forEach { p ->
            code(0, "VERTEX"); code(8, layer); code(10, p[0]); code(20, p[1]); code(30, 0.0)
        }
        code(0, "SEQEND"); code(8, layer)
    }

    fun writeLine(layer: String, x1: Double, y1: Double, x2: Double, y2: Double) {
        code(0, "LINE"); code(8, layer); code(10, x1); code(20, y1); code(11, x2); code(21, y2)
    }

    fun writeText(layer: String, text: String, x: Double, y: Double, height: Double = 2.5, style: String = "THAI") {
        code(0, "TEXT"); code(8, layer); code(10, x); code(20, y); code(40, height); code(1, text); code(7, style)
    }

    fun writeEof() { code(0, "EOF") }

    fun save(file: File) { file.outputStream().use { it.write(sb.toString().toByteArray(java.nio.charset.Charset.forName("windows-874"))) } }
}
