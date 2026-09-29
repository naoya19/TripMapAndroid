package com.patipan.tripmap.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import com.patipan.tripmap.AppDiagnostics
import com.patipan.tripmap.data.SurfaceKind
import com.patipan.tripmap.data.TripEntity
import com.patipan.tripmap.data.TripWithPoints
import com.patipan.tripmap.share.*
import com.patipan.tripmap.tracking.*
import com.patipan.tripmap.osm.OsmJunctionLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.floor

// ════════════════════════════════════════════════════════════
//  ROOT
// ════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripMapRoot(
    viewModel: TripViewModel,
    locationGranted: Boolean,
    notice: String?,
    onClearNotice: () -> Unit,
    onSaveTripFile: (Long, String) -> Unit,
    onImportTripFile: () -> Unit,
    importMessage: String?,
    clearImportMessage: () -> Unit,
    requestPermissions: () -> Unit,
    startTrip: (StartChainageConfig, VoiceMode) -> Unit,
    stopTrip: () -> Unit
) {
    val tracking by viewModel.tracking.collectAsState()
    val trips by viewModel.trips.collectAsState()
    val selectedTrip by viewModel.selectedTrip.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

    if (selectedTrip != null) {
        HistoryDetailScreen(
            selectedTrip!!, viewModel::closeTrip, viewModel::renameTrip, viewModel::deleteTrip,
            viewModel::deleteJunction, viewModel::addJunction, viewModel::addJunctions,
            viewModel::updateJunction, viewModel::saveSegment, viewModel::deleteSegment,
            onSaveTripFile
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Map, null) }, label = { Text("ทริป") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.History, null) }, label = { Text("ประวัติ") })
            }
        }
    ) { padding ->
        if (tab == 0) TripScreen(
            Modifier.padding(padding), tracking, locationGranted,
            requestPermissions, startTrip, stopTrip, notice, onClearNotice
        ) else HistoryScreen(
            Modifier.padding(padding), trips, viewModel::openTrip,
            viewModel::renameTrip, viewModel::deleteTrip, onImportTripFile, importMessage, clearImportMessage
        )
    }
}

// ════════════════════════════════════════════════════════════
//  TRIP SCREEN
// ════════════════════════════════════════════════════════════

@Composable
private fun TripScreen(
    modifier: Modifier,
    state: TrackingState,
    locationGranted: Boolean,
    requestPermissions: () -> Unit,
    startTrip: (StartChainageConfig, VoiceMode) -> Unit,
    stopTrip: () -> Unit,
    notice: String?,
    onClearNotice: () -> Unit
) {
    val context = LocalContext.current
    var diagnosticsOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(false) }
    var layersOpen by remember { mutableStateOf(false) }
    var editJunction by remember { mutableStateOf<JunctionPoint?>(null) }
    var editSegment by remember { mutableStateOf<RoadSegment?>(null) }
    var segmentDraft by remember { mutableStateOf<SegmentDraft?>(null) }
    var layerState by remember { mutableStateOf(MapLayerState()) }

    var configuredStart by remember { mutableStateOf(StartChainageConfig()) }
    var configuredVoice by remember { mutableStateOf(VoiceMode.THAI) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(state.isTracking) {
        while (state.isTracking) { now = System.currentTimeMillis(); delay(1_000) }
    }
    LaunchedEffect(notice) {
        if (notice != null) onClearNotice()
    }

    val elapsed = state.startedAt?.let { (now - it).coerceAtLeast(0) } ?: 0L
    val active = activeConfig(state, configuredStart)
    val maxRoute = state.points.maxOfOrNull { it.chainageMeters } ?: 0.0
    val draft = segmentDraft

    Box(modifier.fillMaxSize()) {

        // ── แผนที่ ──
        RealtimeMap(
            points = state.points,
            junctions = state.junctions,
            roadSegments = state.roadSegments,
            locationGranted = locationGranted,
            config = active,
            drawMode = layerState.drawMode,
            bottomInset = when {
                layersOpen -> 430.dp
                panelOpen -> 336.dp
                else -> 112.dp
            },
            layerState = layerState,
            onMapTap = { latLng ->
                if (state.points.size > 1) {
                    val c = nearestChainageMeters(state.points, latLng.latitude, latLng.longitude)
                    segmentDraft = segmentDraft?.next(c, maxRoute) ?: SegmentDraft(c)
                }
            },
            onJunctionTap = { editJunction = it },
            onAddJunction = TrackingBus::addJunction,
            onRemoveJunction = TrackingBus::removeJunction,
            onSegmentTap = { seg -> editSegment = seg },
            onUndo = { segmentDraft = segmentDraft?.undo() }
        )

        // ── preview ของช่วงที่กำลังลาก ──
        if (draft != null && layerState.drawMode && layerState.showSegments) {
            DraftPreviewOverlay(
                points = state.points,
                draft = draft,
                config = active,
                onCancel = { segmentDraft = null }
            )
        }

        // ── HUD ──
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudChainage(
                    label = chainageLabel(active.displayMeters(state.chainageMeters), active.direction),
                    sub = if (layerState.drawMode) "โหมดสำรวจ" else "ระยะสุทธิ",
                    tracking = state.isTracking,
                    modifier = Modifier.weight(1f)
                )
                HudIconButton(
                    if (layerState.drawMode) Icons.Default.Edit else Icons.Default.Map,
                    "สลับโหมดวาด/ดู"
                ) { layerState = layerState.copy(drawMode = !layerState.drawMode); segmentDraft = null }
                HudIconButton(Icons.Default.Layers, "ชั้นข้อมูล") { layersOpen = true; panelOpen = false }
                HudIconButton(Icons.Default.Settings, "ตั้งค่าจุดเริ่มและเสียง") { settingsOpen = true }
                HudIconButton(Icons.Default.Info, "ตรวจสอบ Maps API") { diagnosticsOpen = true }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat("GPS", if (state.accuracyMeters == null) "รอ" else "±${state.accuracyMeters.toInt()} ม.", Modifier.weight(1f), hud = true)
                Stat("เคลื่อนที่รวม", "%.2f กม.".format(state.traveledDistanceMeters / 1000), Modifier.weight(1.4f), hud = true)
                Stat("ความเร็ว", "%.1f กม./ชม.".format(state.speedKmh), Modifier.weight(1.3f), hud = true)
                Stat("เวลา", durationText(elapsed), Modifier.weight(1f), hud = true)
            }
        }

        // ── แผงล่าง ──
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            AnimatedVisibility(visible = layersOpen) {
                LayersPanel(
                    layerState = layerState,
                    onToggle = { layerState = it },
                    segmentCount = state.roadSegments.size,
                    onClose = { layersOpen = false }
                )
            }
            AnimatedVisibility(visible = panelOpen && !layersOpen) {
                ExtraPanel(state = state, context = context)
            }
            MainBar(
                isTracking = state.isTracking,
                locationGranted = locationGranted,
                panelOpen = panelOpen || layersOpen,
                voiceLabel = voiceModeLabel(if (state.isTracking) state.voiceMode else configuredVoice),
                onTogglePanel = {
                    panelOpen = !panelOpen; layersOpen = false
                    if (!panelOpen) { editJunction = null; editSegment = null }
                },
                onRequestPermissions = requestPermissions,
                onStartStop = { if (state.isTracking) stopTrip() else startTrip(configuredStart, configuredVoice) }
            )
        }
    }

    // ── กล่องแก้ไข ──
    editJunction?.let { j ->
        JunctionEditDialog(
            junction = j,
            segments = state.roadSegments,
            onDismiss = { editJunction = null },
            onSave = { TrackingBus.updateJunction(it); editJunction = null },
            onDelete = { TrackingBus.removeJunction(j.id); editJunction = null }
        )
    }

    editSegment?.let { seg ->
        RoadSegmentDialog(
            segment = seg,
            config = active,
            onDismiss = { editSegment = null },
            onSave = { TrackingBus.replaceSegmentRange(it); editSegment = null },
            onDelete = { TrackingBus.removeSegment(seg.id); editSegment = null }
        )
    }

    if (draft != null && draft.end != null && layerState.drawMode) {
        DraftActionsBar(
            draft = draft,
            config = active,
            onCancel = { segmentDraft = null },
            onContinue = {
                editSegment = RoadSegment(
                    startChainageMeters = minOf(draft.start, draft.end!!).toInt(),
                    endChainageMeters = maxOf(draft.start, draft.end!!).toInt()
                )
            }
        )
    }

    if (settingsOpen) ChainageSettingsDialog(configuredStart, configuredVoice, { settingsOpen = false }) { start, voice ->
        configuredStart = start; configuredVoice = voice; settingsOpen = false
    }
    if (diagnosticsOpen) MapsDiagnosticsDialog(context) { diagnosticsOpen = false }
}

private fun activeConfig(state: TrackingState, configured: StartChainageConfig) =
    if (state.isTracking || state.points.size > 1) state.startChainage else configured

private fun voiceModeLabel(mode: VoiceMode) = when (mode) {
    VoiceMode.THAI -> "เสียง: ภาษาไทย"
    VoiceMode.ENGLISH -> "เสียง: English"
    VoiceMode.OFF -> "เสียง: ปิด"
}

// ════════════════════════════════════════════════════════════
//  ชั้นข้อมูล (v1.5)
// ════════════════════════════════════════════════════════════

data class MapLayerState(
    val drawMode: Boolean = false,
    val showCenterline: Boolean = true,
    val showJunctions: Boolean = true,
    val showChainage: Boolean = true,
    val showNotes: Boolean = false,
    val showSegments: Boolean = true
)

@Composable
private fun LayersPanel(
    layerState: MapLayerState,
    onToggle: (MapLayerState) -> Unit,
    segmentCount: Int,
    onClose: () -> Unit
) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ชั้นข้อมูล", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("ชั้นที่เปิดจะถูกเขียนเป็น layer แยกใน DXF", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClose) { Icon(Icons.Default.Close, "ปิด") }
            }
            LayerRow("✋ โหมดวาด", "กำหนดช่วงถนน", layerState.drawMode) { onToggle(layerState.copy(drawMode = it)) }
            LayerRow("🛣 เส้นกึ่งกลางถนน", "ROUTE_CENTERLINE", layerState.showCenterline) { onToggle(layerState.copy(showCenterline = it)) }
            LayerRow("🔶 จุดทางแยก", "JUNCTION", layerState.showJunctions) { onToggle(layerState.copy(showJunctions = it)) }
            LayerRow("📏 ป้ายระยะ กม.", "CHAINAGE_TEXT", layerState.showChainage) { onToggle(layerState.copy(showChainage = it)) }
            LayerRow("📝 หมายเหตุสำรวจ", "SURVEY_NOTE", layerState.showNotes) { onToggle(layerState.copy(showNotes = it)) }
            LayerRow("📊 ช่วงของถนน ($segmentCount)", "ROAD_SEG_xx", layerState.showSegments) { onToggle(layerState.copy(showSegments = it)) }
            Text(
                if (layerState.drawMode) "โหมดวาด: แตะที่แผนที่ 2 ครั้งเพื่อกำหนดจุดเริ่มและจุดจบของช่วง"
                else "โหมดดู: แตะจุดทางแยกเพื่อดูหรือแก้ไข",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun LayerRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 7.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(8.dp))
        Switch(on, onChange)
    }
}

// ════════════════════════════════════════════════════════════
//  การลากกำหนดช่วง
// ════════════════════════════════════════════════════════════

data class SegmentDraft(val start: Double, val end: Double? = null) {
    fun next(chainage: Double, maxRoute: Double) =
        if (end == null) copy(end = chainage.coerceIn(0.0, maxRoute))
        else SegmentDraft(chainage.coerceIn(0.0, maxRoute))
    fun undo() = if (end != null) SegmentDraft(start) else null
}

@Composable
private fun DraftPreviewOverlay(
    points: List<TrackPoint>,
    draft: SegmentDraft,
    config: StartChainageConfig,
    onCancel: () -> Unit
) {
    val lo = minOf(draft.start, draft.end ?: draft.start)
    val hi = maxOf(draft.start, draft.end ?: draft.start)
    val slice = remember(points, lo, hi) { sliceByChainage(points, lo, hi) }
    val coords = remember(slice) { decimatePoints(slice).map { LatLng(it.latitude, it.longitude) } }
    val hasEnd = draft.end != null

    Box(Modifier.fillMaxSize()) {
        if (coords.size > 1) {
            Polyline(points = coords, color = Color(0xFFF2A03D), width = 18f, zIndex = 30f, geodesic = true)
        }
        if (hasEnd) {
            listOf(draft.start, draft.end!!).forEach { c ->
                pointAtChainage(points, c)?.let { p ->
                    Marker(
                        state = remember(p) { MarkerState(LatLng(p.latitude, p.longitude)) },
                        title = chainageLabel(config.displayMeters(c), config.direction),
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE),
                        zIndex = 31f
                    )
                }
            }
        }
        Surface(
            Modifier.align(Alignment.TopCenter).padding(top = 108.dp),
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFFF2A03D),
            shadowElevation = 3.dp
        ) {
            Text(
                if (!hasEnd) "กำหนดจุดเริ่ม: ${chainageLabel(config.displayMeters(draft.start), config.direction)} — แตะจุดถัดไป"
                else "${chainageLabel(config.displayMeters(lo), config.direction)} – ${chainageLabel(config.displayMeters(hi), config.direction)}",
                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
        IconButton(onCancel, Modifier.align(Alignment.TopEnd).padding(top = 108.dp)) {
            Icon(Icons.Default.Close, "ยกเลิกการกำหนดช่วง")
        }
    }
}

@Composable
private fun DraftActionsBar(
    draft: SegmentDraft,
    config: StartChainageConfig,
    onCancel: () -> Unit,
    onContinue: () -> Unit
) {
    val lo = minOf(draft.start, draft.end ?: draft.start).toInt()
    val hi = maxOf(draft.start, draft.end ?: draft.start).toInt()
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "ช่วงใหม่ · ${chainageLabel(config.displayMeters(lo.toDouble()), config.direction)} – ${chainageLabel(config.displayMeters(hi.toDouble()), config.direction)} · ${hi - lo} ม.",
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onCancel, Modifier.weight(1f)) { Text("ยกเลิก") }
                Button(onContinue, Modifier.weight(1.4f)) { Text("กำหนดค่าถนน") }
            }
        }
    }
}

@Composable
private fun RoadSegmentDialog(
    segment: RoadSegment,
    config: StartChainageConfig,
    onDismiss: () -> Unit,
    onSave: (RoadSegment) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(segment.name) }
    var start by remember { mutableIntStateOf(segment.startChainageMeters) }
    var end by remember { mutableIntStateOf(segment.endChainageMeters) }
    var lanes by remember { mutableIntStateOf(segment.lanes) }
    var width by remember { mutableStateOf(segment.widthMeters?.let { fmtNum(it) } ?: "") }
    var shoulder by remember { mutableStateOf(segment.shoulderMeters?.let { fmtNum(it) } ?: "") }
    var surface by remember { mutableStateOf(segment.surface) }
    var note by remember { mutableStateOf(segment.note) }

    val attrs = RoadAttributes(lanes, width.toDoubleOrNull(), shoulder.toDoubleOrNull(), surface, name.ifBlank { null })
    val lengthM = (end - start).coerceAtLeast(0)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Straighten, null) },
        title = { Text(if (segment.id == 0L) "เพิ่มช่วงของถนน" else "แก้ไขช่วงของถนน") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            "${chainageLabel(config.displayMeters(start.toDouble()), config.direction)} – ${chainageLabel(config.displayMeters(end.toDouble()), config.direction)}",
                            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall
                        )
                        Text("ความยาว $lengthM เมตร (${String.format("%.2f", lengthM / 1000.0)} กม.)", style = MaterialTheme.typography.labelSmall)
                    }
                }
                OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("ชื่อช่วง (ใช้เป็นชื่อชั้นใน DXF)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        start.toString(), { start = it.filter(Char::isDigit).toIntOrNull() ?: 0 },
                        Modifier.weight(1f), label = { Text("เริ่ม (เมตร)") }, singleLine = true
                    )
                    OutlinedTextField(
                        end.toString(), { end = it.filter(Char::isDigit).toIntOrNull() ?: 0 },
                        Modifier.weight(1f), label = { Text("จบ (เมตร)") }, singleLine = true
                    )
                }
                Text("จำนวนเลน (ต่อทิศทาง)", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    (1..5).forEach { n -> FilterChip(lanes == n, { lanes = n }, label = { Text(if (n == 5) "5+" else "$n") }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        width, { width = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                        Modifier.weight(1f), label = { Text("กว้าง (ม.)") }, singleLine = true
                    )
                    OutlinedTextField(
                        shoulder, { shoulder = it.filter { c -> c.isDigit() || c == '.' }.take(4) },
                        Modifier.weight(1f), label = { Text("ไหล่ทาง (ม.)") }, singleLine = true
                    )
                }
                Text("เว้นว่างได้ — ถ้าไม่กรอกจะประมาณจากจำนวนเลน × 3.5 ม.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text("ผิวถนน", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    FilterChip(surface == SurfaceKind.UNKNOWN, { surface = SurfaceKind.UNKNOWN }, label = { Text("ยังไม่ระบุ", style = MaterialTheme.typography.labelSmall) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    SurfaceKind.all.forEach { s ->
                        FilterChip(surface == s, { surface = s }, label = { Text(SurfaceKind.label(s), style = MaterialTheme.typography.labelSmall) })
                    }
                }
                OutlinedTextField(note, { note = it.take(300) }, Modifier.fillMaxWidth(), label = { Text("หมายเหตุภาคสนาม") }, minLines = 2)
                Surface(color = Color(0xFFEFF8F1), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        "▣ สรุป: ${attrs.summary()}\n▣ กว้างรวมช่องทาง+ไหล่ทาง ${attrs.totalWidthMeters?.let { "${fmtNum(it)} ม." } ?: "-"}",
                        Modifier.padding(10.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFF1F5C3D)
                    )
                }
                TextButton(onDelete) { Text("ลบช่วงนี้", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton({
                onSave(segment.copy(
                    name = name.trim(),
                    startChainageMeters = start,
                    endChainageMeters = end,
                    lanes = lanes,
                    widthMeters = width.toDoubleOrNull(),
                    shoulderMeters = shoulder.toDoubleOrNull(),
                    surface = surface,
                    note = note.trim()
                ))
            }) { Text("บันทึก") }
        },
        dismissButton = { TextButton(onDismiss) { Text("ยกเลิก") } }
    )
}

// ════════════════════════════════════════════════════════════
//  แก้ไขจุดทางแยก
// ════════════════════════════════════════════════════════════

@Composable
private fun JunctionEditDialog(
    junction: JunctionPoint,
    segments: List<RoadSegment>,
    onDismiss: () -> Unit,
    onSave: (JunctionPoint) -> Unit,
    onDelete: () -> Unit
) {
    var type by remember { mutableStateOf(junction.type) }
    var side by remember { mutableStateOf(junction.side) }
    var note by remember { mutableStateOf(junction.note) }
    var override by remember { mutableStateOf(junction.lanes != null || junction.widthMeters != null) }
    var lanes by remember { mutableIntStateOf(junction.lanes ?: 2) }
    var width by remember { mutableStateOf(junction.widthMeters?.let { fmtNum(it) } ?: "") }
    var shoulder by remember { mutableStateOf(junction.shoulderMeters?.let { fmtNum(it) } ?: "") }
    var surface by remember { mutableStateOf(junction.surface) }
    var photoRef by remember { mutableStateOf(junction.photoRef ?: "") }

    val fromSegment = segments.at(junction.nearestChainageMeters)
    val eff = junction.effective(segments)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Place, null) },
        title = { Text("แก้ไขจุดทางแยก") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("กม.${junction.nearestChainageMeters.toInt()}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        Text("${"%.6f".format(junction.latitude)}, ${"%.6f".format(junction.longitude)}", style = MaterialTheme.typography.labelSmall)
                        Text(
                            "แหล่งข้อมูล: ${if (junction.source == JunctionSource.OSM) "OSM (แก้ไขได้)" else "ระบุเอง"}" +
                                    (fromSegment?.name?.takeIf { it.isNotBlank() }?.let { " · อยู่ใน $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text("ประเภท", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    JunctionType.entries.forEach { t ->
                        FilterChip(type == t, { type = t }, label = { Text(t.label(), style = MaterialTheme.typography.labelSmall) })
                    }
                }
                if (type == JunctionType.THREE_WAY || type == JunctionType.T) {
                    Text("แขนแยกอยู่ด้าน", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        FilterChip(side == JunctionSide.LEFT, { side = JunctionSide.LEFT }, label = { Text("ซ้าย", style = MaterialTheme.typography.labelSmall) })
                        FilterChip(side == JunctionSide.RIGHT, { side = JunctionSide.RIGHT }, label = { Text("ขวา", style = MaterialTheme.typography.labelSmall) })
                        FilterChip(side == JunctionSide.BOTH, { side = JunctionSide.BOTH }, label = { Text("ทั้งสอง", style = MaterialTheme.typography.labelSmall) })
                    }
                } else {
                    Text("4 แยก จะตั้งแขนแยกทั้งสองด้านให้อัตโนมัติ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                Divider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("วัดค่าถนนเฉพาะจุดนี้", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (override) "ใช้ค่าที่กรอกเอง" else "ใช้ค่าจา่านช่วงถนน: ${eff.summary()}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(override, { override = it })
                }
                if (override) {
                    Text("จำนวนเลน", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        (1..5).forEach { n -> FilterChip(lanes == n, { lanes = n }, label = { Text(if (n == 5) "5+" else "$n") }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(width, { width = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, Modifier.weight(1f), label = { Text("กว้าง (ม.)") }, singleLine = true)
                        OutlinedTextField(shoulder, { shoulder = it.filter { c -> c.isDigit() || c == '.' }.take(4) }, Modifier.weight(1f), label = { Text("ไหล่ทาง (ม.)") }, singleLine = true)
                    }
                    Text("ผิวถนน", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        SurfaceKind.all.forEach { s ->
                            FilterChip(surface == s, { surface = s }, label = { Text(SurfaceKind.label(s), style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                }
                OutlinedTextField(note, { note = it.take(300) }, Modifier.fillMaxWidth(), label = { Text("หมายเหตุภาคสนาม") }, minLines = 2)
                OutlinedTextField(photoRef, { photoRef = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("รหัสรูปอ้างอิง") }, singleLine = true)
                TextButton(onDelete) { Text("ลบจุดนี้", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton({
                onSave(junction.copy(
                    type = type,
                    side = if (type == JunctionType.FOUR_WAY) JunctionSide.BOTH else side,
                    note = note.trim(),
                    lanes = if (override) lanes else null,
                    widthMeters = if (override) width.toDoubleOrNull() else null,
                    shoulderMeters = if (override) shoulder.toDoubleOrNull() else null,
                    surface = if (override) surface else SurfaceKind.UNKNOWN,
                    photoRef = photoRef.trim().ifBlank { null }
                ))
            }) { Text("บันทึก") }
        },
        dismissButton = { TextButton(onDismiss) { Text("ยกเลิก") } }
    )
}

private fun fmtNum(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else String.format("%.1f", v)

// ════════════════════════════════════════════════════════════
//  HUD / แผงล่าง
// ════════════════════════════════════════════════════════════

@Composable
private fun HudSurface(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(
        modifier = if (onClick != null) modifier.clickable { onClick() } else modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.94f),
        shadowElevation = 3.dp
    ) { content() }
}

@Composable
private fun HudChainage(label: String, sub: String, tracking: Boolean, modifier: Modifier = Modifier) {
    HudSurface(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(if (tracking) Color(0xFF2D865E) else Color(0xFFBDBDB8), RoundedCornerShape(50)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
            }
        }
    }
}

@Composable
private fun HudIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    HudSurface(onClick = onClick) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(icon, description, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun MainBar(
    isTracking: Boolean,
    locationGranted: Boolean,
    panelOpen: Boolean,
    voiceLabel: String,
    onTogglePanel: () -> Unit,
    onRequestPermissions: () -> Unit,
    onStartStop: () -> Unit
) {
    Surface(shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 10.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().clickable { onTogglePanel() }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).background(Color(0xFFBDBDB8), RoundedCornerShape(50)))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onTogglePanel() }) {
                Icon(if (panelOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (panelOpen) "ซ่อนเครื่องมือ" else "แสดงเครื่องมือ", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(6.dp))
                Text(voiceLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Text(if (panelOpen) "ซ่อน" else "เครื่องมือ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            if (!locationGranted) {
                Button(onRequestPermissions, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("อนุญาตตำแหน่ง GPS") }
            } else {
                Button(
                    onClick = onStartStop,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = if (isTracking) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
                ) {
                    Icon(if (isTracking) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isTracking) "หยุดและบันทึกทริป" else "เริ่มทริป")
                }
            }
        }
    }
}

@Composable
private fun ExtraPanel(state: TrackingState, context: Context) {
    var osmSyncing by remember { mutableStateOf(false) }
    var osmMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxWidth().padding(horizontal = 10.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.isTracking) "●" else "○", color = if (state.isTracking) Color(0xFF2D865E) else Color.Gray)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (state.isTracking) "REAL-TIME • กำลังลากเส้นตำแหน่ง" else "พร้อมเริ่มทริป", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    val latest = state.points.lastOrNull()
                    Text(latest?.let { "${"%.6f".format(it.latitude)}, ${"%.6f".format(it.longitude)}" } ?: "ยังไม่มีตำแหน่ง", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (state.roadSegments.isNotEmpty()) {
                Text("ช่วงของถนน ${state.roadSegments.size} ช่วง", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VolumeUp, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.ttsStatus, style = MaterialTheme.typography.bodySmall)
                    state.lastSpokenText?.let { Text("ล่าสุด: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                }
            }
            if (state.points.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShareButton({ shareCurrent(context, state, ReportFormat.PNG) }, Icons.Default.Image, "รูป", Modifier.weight(1f))
                ShareButton({ shareCurrent(context, state, ReportFormat.PDF) }, Icons.Default.PictureAsPdf, "PDF", Modifier.weight(1f))
                ShareButton({ shareCurrent(context, state, ReportFormat.KMZ) }, Icons.Default.Place, "KMZ", Modifier.weight(1f))
                ShareButton({ shareCurrent(context, state, ReportFormat.DXF) }, Icons.Default.Straighten, "DXF", Modifier.weight(1f))
            }
            if (state.points.size > 1) {
                OutlinedButton(
                    onClick = {
                        osmSyncing = true; osmMessage = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { OsmJunctionLookup.fetchJunctions(state.points) }
                            osmSyncing = false
                            osmMessage = when (result) {
                                is OsmJunctionLookup.Result.Success -> {
                                    TrackingBus.addJunctions(result.junctions)
                                    if (result.junctions.isEmpty()) "ไม่พบทางแยกจาก OSM ในเส้นทางนี้ — แตะค้างบนแผนที่เพื่อระบุเอง"
                                    else "พบทางแยกจาก OSM ${result.junctions.size} จุด"
                                }
                                is OsmJunctionLookup.Result.Failure -> "ดึงข้อมูล OSM ไม่สำเร็จ: ${result.message}"
                            }
                        }
                    },
                    enabled = !osmSyncing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (osmSyncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Sync, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (osmSyncing) "กำลังดึงทางแยกจาก OSM…" else "ดึงทางแยกจาก OSM")
                }
                osmMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
//  DIALOG เดิม
// ════════════════════════════════════════════════════════════

@Composable
private fun ChainageSettingsDialog(initialConfig: StartChainageConfig, initialVoice: VoiceMode, onDismiss: () -> Unit, onSave: (StartChainageConfig, VoiceMode) -> Unit) {
    var km by remember { mutableStateOf((initialConfig.meters / 1000).toString()) }
    var hundred by remember { mutableStateOf(((initialConfig.meters % 1000) / 100).toString()) }
    var direction by remember { mutableStateOf(initialConfig.direction) }
    var voice by remember { mutableStateOf(initialVoice) }
    val safeHundred = hundred.toIntOrNull()?.coerceIn(0, 9) ?: 0
    val startMeters = (km.toIntOrNull() ?: 0) * 1000 + safeHundred * 100
    AlertDialog(onDismissRequest = onDismiss, title = { Text("กำหนด Start chainage") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("เช่น กม.4+000 หรือ กม.12+000", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(km, { km = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), label = { Text("กม.") }, singleLine = true)
                OutlinedTextField(hundred, { hundred = it.filter(Char::isDigit).take(1) }, Modifier.weight(1f), label = { Text("+ x00 ม.") }, singleLine = true)
            }
            Text("เริ่มที่ ${chainageLabel(startMeters)}", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = direction == ChainageDirection.LT, onClick = { direction = ChainageDirection.LT }, label = { Text("LT · นับเพิ่ม") })
                FilterChip(selected = direction == ChainageDirection.RT, onClick = { direction = ChainageDirection.RT }, label = { Text("RT · นับลด") })
            }
            Text(voiceModeLabel(voice), fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(voice == VoiceMode.THAI, { voice = VoiceMode.THAI }, label = { Text("ไทย") })
                FilterChip(voice == VoiceMode.ENGLISH, { voice = VoiceMode.ENGLISH }, label = { Text("English") })
                FilterChip(voice == VoiceMode.OFF, { voice = VoiceMode.OFF }, label = { Text("ปิดเสียง") })
            }
        }
    }, confirmButton = { TextButton({ onSave(StartChainageConfig(startMeters, direction), voice) }) { Text("บันทึก") } }, dismissButton = { TextButton(onDismiss) { Text("ยกเลิก") } })
}

@Composable
private fun MapsDiagnosticsDialog(context: Context, onDismiss: () -> Unit) {
    val sha1 = remember { AppDiagnostics.signingSha1(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Info, null) },
        title = { Text("ตรวจสอบ Google Maps") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("นำค่า 2 รายการนี้ไปใส่ใน Google Cloud Console ให้ตรงทุกตัว")
                Text("Package name", style = MaterialTheme.typography.labelSmall)
                Text(context.packageName, fontWeight = FontWeight.SemiBold)
                Text("SHA-1 ของแอปที่ติดตั้ง", style = MaterialTheme.typography.labelSmall)
                Text(sha1, fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("ปิด") } }
    )
}

@Composable
private fun ShareButton(onClick: () -> Unit, icon: ImageVector, label: String, modifier: Modifier = Modifier, filled: Boolean = false) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
    val padding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    if (filled) Button(onClick = onClick, modifier = modifier, contentPadding = padding, content = content)
    else OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = padding, content = content)
}

// ════════════════════════════════════════════════════════════
//  แผนที่
// ════════════════════════════════════════════════════════════

@Composable
private fun RealtimeMap(
    points: List<TrackPoint>,
    junctions: List<JunctionPoint>,
    roadSegments: List<RoadSegment> = emptyList(),
    locationGranted: Boolean,
    config: StartChainageConfig,
    drawMode: Boolean = false,
    bottomInset: Dp = 12.dp,
    layerState: MapLayerState = MapLayerState(),
    onMapTap: (LatLng) -> Unit = {},
    onJunctionTap: (JunctionPoint) -> Unit = {},
    onAddJunction: (JunctionPoint) -> Unit = {},
    onRemoveJunction: (Long) -> Unit = {},
    onSegmentTap: (RoadSegment) -> Unit = {},
    onUndo: () -> Unit = {}
) {
    val polylinePoints = remember(points) { decimatePoints(points) }
    val coordinates = remember(polylinePoints) { polylinePoints.map { LatLng(it.latitude, it.longitude) } }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(13.7563, 100.5018), 11f) }
    var mapLoaded by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var detailMode by remember { mutableStateOf(false) }
    var previewMeters by remember(points) { mutableFloatStateOf(points.maxOfOrNull { it.chainageMeters.toFloat() } ?: 0f) }
    var pendingJunctionLatLng by remember { mutableStateOf<LatLng?>(null) }
    val maxChainage = remember(points) { points.maxOfOrNull { it.chainageMeters.toFloat() } ?: 0f }

    val interval = if (detailMode) 100 else 1_000
    val selectedKm = if (detailMode) previewMeters.toInt() / 1_000 else null
    val markers = remember(points, interval, config, selectedKm, layerState.showChainage) {
        if (layerState.showChainage) chainageMarkers(points, interval, config, selectedKm) else emptyList()
    }
    val segmentLines = remember(points, roadSegments, layerState.showSegments) {
        if (!layerState.showSegments) return@remember emptyList()
        roadSegments.mapNotNull { seg ->
            val lo = seg.startChainageMeters.toDouble()
            val hi = seg.endChainageMeters.toDouble()
            val slice = sliceByChainage(points, lo, hi)
            if (slice.size < 2) return@mapNotNull null
            val coords = decimatePoints(slice).map { p -> LatLng(p.latitude, p.longitude) }
            seg to coords
        }
    }

    LaunchedEffect(mapLoaded, points.size, follow) {
        if (mapLoaded && follow && coordinates.isNotEmpty()) {
            camera.animate(CameraUpdateFactory.newLatLngZoom(coordinates.last(), 18f), 500)
        }
    }

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(isMyLocationEnabled = locationGranted),
            uiSettings = MapUiSettings(myLocationButtonEnabled = false, zoomControlsEnabled = false),
            onMapLoaded = { mapLoaded = true },
            onMapClick = { latLng -> if (drawMode) onMapTap(latLng) else follow = false },
            onMapLongClick = { latLng -> pendingJunctionLatLng = latLng }
        ) {
            if (coordinates.size > 1) {
                Polyline(points = coordinates, color = if (layerState.showCenterline) Color(0xFF2783DE) else Color(0xFF9AA7B5), width = 12f, zIndex = 10f, geodesic = true)
            }
            segmentLines.forEach { (seg, coords) ->
                Polyline(points = coords, color = aciColor(seg.colorIndex), width = 18f, zIndex = 12f, geodesic = true)
                val mid = pointAtChainage(points, (seg.startChainageMeters + seg.endChainageMeters) / 2.0)
                mid?.let { p ->
                    Marker(
                        state = remember(p) { MarkerState(LatLng(p.latitude, p.longitude)) },
                        title = seg.name.ifBlank { "ช่วง ${chainageLabel(config.displayMeters(seg.startChainageMeters.toDouble()), config.direction)}" },
                        snippet = "${seg.attributes().summary()} · ${seg.lengthMeters} ม.",
                        icon = BitmapDescriptorFactory.defaultMarker(aciHue(seg.colorIndex)),
                        zIndex = 16f,
                        onClick = { onSegmentTap(seg); true }
                    )
                }
            }
            markers.forEachIndexed { index, marker ->
                val labelText = chainageLabel(marker.displayMeters, config.direction)
                val above = index % 2 == 0
                Marker(
                    state = remember(marker.point) { MarkerState(LatLng(marker.point.latitude, marker.point.longitude)) },
                    title = labelText,
                    icon = remember(labelText, above) { chainageIconCached(labelText, above) },
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                    zIndex = 15f
                )
            }
            if (layerState.showJunctions) junctions.forEach { junction ->
                val attrs = junction.effective(roadSegments)
                Marker(
                    state = remember(junction.id) { MarkerState(LatLng(junction.latitude, junction.longitude)) },
                    title = junctionLabel(junction),
                    snippet = "${chainageLabel(config.displayMeters(junction.nearestChainageMeters), config.direction)} · ${attrs.summary()}",
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE),
                    zIndex = 18f,
                    onClick = { onJunctionTap(junction); true }
                )
            }
            coordinates.lastOrNull()?.let {
                Marker(
                    state = remember(it) { MarkerState(it) },
                    title = "ตำแหน่ง Real-time",
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE),
                    zIndex = 20f
                )
            }
        }

        FloatingActionButton(
            onClick = { follow = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = bottomInset),
            containerColor = Color.White
        ) { Icon(Icons.Default.MyLocation, "ติดตามตำแหน่ง") }

        Surface(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = bottomInset), shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(!detailMode, { detailMode = false }, label = { Text("ภาพรวม · ทุก 1 กม.") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(detailMode, { detailMode = true }, label = { Text("รายละเอียด · ทุก 100 ม.") })
                }
                if (drawMode) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("โหมดวาด · แตะกำหนดช่วง", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        IconButton({ onUndo() }, Modifier.size(28.dp)) { Icon(Icons.Default.Undo, "ย้อนกลับ", Modifier.size(16.dp)) }
                    }
                } else {
                    Text("แตะค้างเพื่อเพิ่มทางแยก · แตะจุดเพื่อแก้ไข", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                if (detailMode) {
                    Slider(previewMeters, { previewMeters = it }, valueRange = 0f..maxChainage.coerceAtLeast(1f), enabled = points.size > 1)
                }
            }
        }
    }

    pendingJunctionLatLng?.let { latLng ->
        AddJunctionDialog(
            onDismiss = { pendingJunctionLatLng = null },
            onConfirm = { type, side ->
                onAddJunction(JunctionPoint(
                    id = System.nanoTime(),
                    latitude = latLng.latitude,
                    longitude = latLng.longitude,
                    type = type,
                    side = side,
                    source = JunctionSource.MANUAL,
                    nearestChainageMeters = nearestChainageMeters(points, latLng.latitude, latLng.longitude)
                ))
                pendingJunctionLatLng = null
            }
        )
    }
}

@Composable
private fun DeleteJunctionDialog(junction: JunctionPoint, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("ลบจุดทางแยกนี้ใช่ไหม") },
        text = { Text("${junctionLabel(junction)} — ${if (junction.source == JunctionSource.OSM) "ดึงมาจาก OSM" else "ระบุเอง"}") },
        confirmButton = { TextButton(onConfirm) { Text("ลบ", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onDismiss) { Text("ยกเลิก") } }
    )
}

@Composable
private fun AddJunctionDialog(onDismiss: () -> Unit, onConfirm: (JunctionType, JunctionSide) -> Unit) {
    var type by remember { mutableStateOf(JunctionType.THREE_WAY) }
    var side by remember { mutableStateOf(JunctionSide.RIGHT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Place, null) },
        title = { Text("เพิ่มจุดทางแยก") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ประเภททางแยก", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(type == JunctionType.THREE_WAY, { type = JunctionType.THREE_WAY }, label = { Text("3 แยก") })
                    FilterChip(type == JunctionType.FOUR_WAY, { type = JunctionType.FOUR_WAY }, label = { Text("4 แยก") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(type == JunctionType.T, { type = JunctionType.T }, label = { Text("ทางตัวที") })
                    FilterChip(type == JunctionType.LEFT_TURN, { type = JunctionType.LEFT_TURN }, label = { Text("กลับซ้าย") })
                }
                if (type == JunctionType.THREE_WAY || type == JunctionType.T) {
                    Text("ด้านแขนแยก (เทียบทิศทางเดินทาง)", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(side == JunctionSide.LEFT, { side = JunctionSide.LEFT }, label = { Text("ซ้าย") })
                        FilterChip(side == JunctionSide.RIGHT, { side = JunctionSide.RIGHT }, label = { Text("ขวา") })
                    }
                } else {
                    Text("4 แยก จะตั้งแขนแยกทั้งสองด้านให้อัตโนมัติ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        },
        confirmButton = { TextButton({ onConfirm(type, if (type == JunctionType.FOUR_WAY) JunctionSide.BOTH else side) }) { Text("บันทึก") } },
        dismissButton = { TextButton(onDismiss) { Text("ยกเลิก") } }
    )
}

private fun junctionLabel(junction: JunctionPoint): String {
    val sideLabel = when (junction.side) {
        JunctionSide.LEFT -> "ซ้าย"; JunctionSide.RIGHT -> "ขวา"; JunctionSide.BOTH -> "ทั้งสองด้าน"
    }
    return "${junction.type.label()} ($sideLabel)"
}

/** ✅ แก้จุดที่ 4 — ส่งช่วงถนนไปกับรายงาน */
private fun shareCurrent(context: Context, state: TrackingState, format: ReportFormat) {
    val start = state.startedAt ?: System.currentTimeMillis()
    TripReportExporter.share(context, TripReport(
        start, System.currentTimeMillis() - start,
        state.chainageMeters, state.traveledDistanceMeters, state.points,
        startChainage = state.startChainage,
        junctions = state.junctions,
        roadSegments = state.roadSegments
    ), format)
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier, hud: Boolean = false) {
    if (hud) {
        HudSurface(modifier) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    } else {
        Column(modifier) {
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

// ════════════════════════════════════════════════════════════
//  ตัวช่วย
// ════════════════════════════════════════════════════════════

data class ChainageMarker(val routeMeters: Int, val displayMeters: Int, val point: TrackPoint)

private fun chainageMarkers(points: List<TrackPoint>, interval: Int = 100, config: StartChainageConfig = StartChainageConfig(), selectedKm: Int? = null): List<ChainageMarker> {
    if (points.isEmpty()) return emptyList()
    val forward = mutableListOf(points.first())
    var maximum = points.first().chainageMeters
    points.drop(1).forEach { if (it.chainageMeters > maximum + 1) { forward += it; maximum = it.chainageMeters } }
    val result = mutableListOf<ChainageMarker>()
    val end = floor(maximum / 100).toInt() * 100
    for (meters in 0..end step interval) {
        if (selectedKm != null && meters / 1000 != selectedKm) continue
        forward.minByOrNull { abs(it.chainageMeters - meters) }?.let { result += ChainageMarker(meters, config.displayMeters(meters.toDouble()), it) }
    }
    return result
}

/** ลดจำนวนจุดที่ส่งให้ Google Maps วาดเส้น เพื่อให้แผนที่ลื่นเมื่อทริปยาว — ข้อมูลเต็มยังอยู่ใน Room ครบ */
private fun decimatePoints(points: List<TrackPoint>, maxPoints: Int = 600): List<TrackPoint> {
    if (points.size <= maxPoints) return points
    val step = ((points.size + maxPoints - 1) / maxPoints).coerceAtLeast(1)
    val out = ArrayList<TrackPoint>(maxPoints + 1)
    var i = 0
    while (i < points.size) { out.add(points[i]); i += step }
    val last = points[points.size - 1]
    if (out[out.size - 1] !== last) out.add(last)
    return out
}

private val chainageIconCache = object : LinkedHashMap<String, com.google.android.gms.maps.model.BitmapDescriptor>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, com.google.android.gms.maps.model.BitmapDescriptor>) = size > 256
}

private fun chainageIconCached(label: String, above: Boolean): com.google.android.gms.maps.model.BitmapDescriptor =
    synchronized(chainageIconCache) { chainageIconCache.getOrPut("$label|${if (above) "a" else "b"}") { chainageIcon(label, above) } }

private fun chainageIcon(label: String, above: Boolean): com.google.android.gms.maps.model.BitmapDescriptor {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.textSize = 28f; paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
    val width = (paint.measureText(label) + 30).toInt().coerceAtLeast(140)
    val bitmap = android.graphics.Bitmap.createBitmap(width, 94, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    paint.color = android.graphics.Color.rgb(30, 101, 170); paint.strokeWidth = 3f
    val lineY = if (above) 72f else 22f
    canvas.drawLine(width / 2f, lineY, width / 2f, if (above) 92f else 2f, paint)
    paint.color = android.graphics.Color.WHITE; canvas.drawRoundRect(2f, if (above) 2f else 42f, width - 2f, if (above) 52f else 92f, 10f, 10f, paint)
    paint.style = android.graphics.Paint.Style.STROKE; paint.color = android.graphics.Color.rgb(30, 101, 170); canvas.drawRoundRect(2f, if (above) 2f else 42f, width - 2f, if (above) 52f else 92f, 10f, 10f, paint)
    paint.style = android.graphics.Paint.Style.FILL; paint.color = android.graphics.Color.rgb(18, 77, 130)
    canvas.drawText(label, 15f, if (above) 36f else 76f, paint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun chainageLabel(meters: Int, direction: ChainageDirection? = null): String {
    val safe = meters.coerceAtLeast(0)
    return "กม.${safe / 1000}+${(safe % 1000).toString().padStart(3, '0')}" + if (direction == null) "" else " ${direction.name}"
}

private fun durationText(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** สีมาตรฐาน AutoCAD ACI — ใช้เดียวกันทั้งบนแผนที่และใน DXF จะได้ตรงกัน */
private fun aciColor(index: Int) = when (index % 8) {
    1 -> Color(0xFFDE4B45)
    2 -> Color(0xFFE8963D)
    3 -> Color(0xFFD9B23C)
    4 -> Color(0xFF2D865E)
    5 -> Color(0xFF2783DE)
    6 -> Color(0xFF8E5BC4)
    7 -> Color(0xFF8A939F)
    else -> Color(0xFF4A545F)
}

private fun aciHue(index: Int) = when (index % 8) {
    1 -> BitmapDescriptorFactory.HUE_RED
    2 -> BitmapDescriptorFactory.HUE_ORANGE
    3 -> BitmapDescriptorFactory.HUE_YELLOW
    4 -> BitmapDescriptorFactory.HUE_GREEN
    5 -> BitmapDescriptorFactory.HUE_AZURE
    6 -> BitmapDescriptorFactory.HUE_VIOLET
    7 -> BitmapDescriptorFactory.HUE_CYAN
    else -> BitmapDescriptorFactory.HUE_ROSE
}

// ════════════════════════════════════════════════════════════
//  ประวัติ
// ════════════════════════════════════════════════════════════

@Composable
private fun HistoryScreen(
    modifier: Modifier,
    trips: List<TripEntity>,
    onOpen: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onImportTripFile: () -> Unit,
    importMessage: String?,
    clearImportMessage: () -> Unit
) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ประวัติทริป", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onImportTripFile) {
                Icon(Icons.Default.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("นำเข้า")
            }
        }
        importMessage?.let {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(clearImportMessage) { Text("ปิด") }
                }
            }
        }
        if (trips.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("ยังไม่มีทริปที่บันทึก", color = Color.Gray) }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(trips, key = { it.id }) { trip -> HistoryTripItem(trip, onOpen, onRename, onDelete) }
            }
        }
    }
}

@Composable
private fun HistoryTripItem(trip: TripEntity, onOpen: (Long) -> Unit, onRename: (Long, String) -> Unit, onDelete: (Long) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { onOpen(trip.id) }) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Route, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tripDisplayName(trip), fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(SimpleDateFormat("d MMM yyyy, HH:mm", Locale("th", "TH")).format(Date(trip.startedAt)), style = MaterialTheme.typography.labelSmall)
                Text("ระยะสุทธิ %.2f กม. • รวม %.2f กม.".format(trip.netDistanceMeters / 1000, trip.traveledDistanceMeters / 1000), style = MaterialTheme.typography.bodySmall)
            }
            Box {
                IconButton({ menuOpen = true }) { Icon(Icons.Default.MoreVert, "เมนูทริป") }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("แก้ไขชื่อ") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menuOpen = false; renameOpen = true })
                    DropdownMenuItem(text = { Text("ลบทริป", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { menuOpen = false; deleteOpen = true })
                }
            }
        }
    }
    if (renameOpen) RenameTripDialog(trip, { renameOpen = false }) { onRename(trip.id, it); renameOpen = false }
    if (deleteOpen) DeleteTripDialog(trip, { deleteOpen = false }) { onDelete(trip.id); deleteOpen = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryDetailScreen(
    data: TripWithPoints,
    onBack: () -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onDeleteJunction: (Long, Long) -> Unit,
    onAddJunction: (Long, JunctionPoint) -> Unit,
    onAddJunctions: (Long, List<JunctionPoint>) -> Unit,
    onUpdateJunction: (Long, JunctionPoint) -> Unit,
    onSaveSegment: (Long, RoadSegment) -> Unit,
    onDeleteSegment: (Long, Long) -> Unit,
    onSaveTripFile: (Long, String) -> Unit
) {
    val context = LocalContext.current
    val trip = data.trip
    val tripConfig = StartChainageConfig(trip.startChainageMeters, if (trip.chainageDirection == ChainageDirection.RT.name) ChainageDirection.RT else ChainageDirection.LT)
    val points = remember(data.points) { data.points.sortedBy { it.sequence }.map { TrackPoint(it.latitude, it.longitude, it.accuracyMeters, it.timestamp, it.chainageMeters) } }
    val junctions = remember(data.junctions) { data.junctions.map { it.toDomain() } }
    val segments = remember(data.roadSegments) { data.roadSegments.map { it.toDomain() }.sortedByRange() }

    var renameOpen by remember(trip.id) { mutableStateOf(false) }
    var deleteOpen by remember(trip.id) { mutableStateOf(false) }
    var exportOpen by remember(trip.id) { mutableStateOf(false) }
    var osmSyncing by remember(trip.id) { mutableStateOf(false) }
    var osmMessage by remember(trip.id) { mutableStateOf<String?>(null) }
    var editJunction by remember { mutableStateOf<JunctionPoint?>(null) }
    var editSegment by remember { mutableStateOf<RoadSegment?>(null) }
    var showSegments by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tripDisplayName(trip), maxLines = 1) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "ย้อนกลับ") } },
            actions = {
                IconButton({ onSaveTripFile(trip.id, "${tripDisplayName(trip).replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40)}.tripmap") }) { Icon(Icons.Default.Save, "บันทึกไฟล์ทริป") }
                IconButton({ renameOpen = true }) { Icon(Icons.Default.Edit, "แก้ไขชื่อ") }
                IconButton({ deleteOpen = true }) { Icon(Icons.Default.Delete, "ลบทริป", tint = MaterialTheme.colorScheme.error) }
            }
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            HistoryMap(
                points, junctions, Modifier.weight(1f), tripConfig,
                roadSegments = segments, showSegments = showSegments,
                onAddJunction = { onAddJunction(trip.id, it) },
                onDeleteJunction = { onDeleteJunction(trip.id, it) },
                onJunctionTap = { editJunction = it }
            )
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("th", "TH")).format(Date(trip.startedAt)), fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth()) {
                    Stat("ระยะสุทธิ", "%.2f กม.".format(trip.netDistanceMeters / 1000), Modifier.weight(1f))
                    Stat("เคลื่อนที่รวม", "%.2f กม.".format(trip.traveledDistanceMeters / 1000), Modifier.weight(1f))
                    Stat("เวลา", durationText(trip.durationMillis), Modifier.weight(1f))
                    Stat("ช่วงถนน", "${segments.size}", Modifier.weight(1f))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ช่วงของถนน (${segments.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    FilterChip(showSegments, { showSegments = !showSegments }, label = { Text("แสดงบนแผนที่", style = MaterialTheme.typography.labelSmall) })
                }
                if (segments.isEmpty()) {
                    Text("ยังไม่มีข้อมูลช่วงถนน — กดเพิ่มเพื่อบันทึกความกว้าง จำนวนเลน ไหล่ทาง และผิวถนน", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                } else {
                    segments.forEach { seg ->
                        Surface(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { editSegment = seg },
                            shape = RoundedCornerShape(8.dp),
                            color = aciColor(seg.colorIndex).copy(alpha = 0.10f)
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).background(aciColor(seg.colorIndex), RoundedCornerShape(2.dp)))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(seg.name.ifBlank { "ช่วง" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(
                                        "${chainageLabel(tripConfig.displayMeters(seg.startChainageMeters.toDouble()))} – ${chainageLabel(tripConfig.displayMeters(seg.endChainageMeters.toDouble()))} · ${seg.lengthMeters} ม.",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Text(seg.attributes().summary(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                                }
                                IconButton({ onDeleteSegment(trip.id, seg.id) }) {
                                    Icon(Icons.Default.Delete, "ลบช่วง", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        editSegment = RoadSegment(
                            startChainageMeters = 0,
                            endChainageMeters = (points.maxOfOrNull { it.chainageMeters } ?: 0.0).toInt()
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("เพิ่มช่วงของถนน")
                }

                if (points.size > 1) {
                    OutlinedButton(onClick = {
                        osmSyncing = true; osmMessage = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { OsmJunctionLookup.fetchJunctions(points) }
                            osmSyncing = false
                            osmMessage = when (result) {
                                is OsmJunctionLookup.Result.Success -> {
                                    val additions = result.junctions.filter { osm -> junctions.none { haversineMeters(it.latitude, it.longitude, osm.latitude, osm.longitude) < 15.0 } }
                                    onAddJunctions(trip.id, additions)
                                    if (additions.isEmpty()) "ไม่พบทางแยกใหม่จาก OSM" else "เพิ่มทางแยกจาก OSM ${additions.size} จุด"
                                }
                                is OsmJunctionLookup.Result.Failure -> "ดึงข้อมูล OSM ไม่สำเร็จ: ${result.message}"
                            }
                        }
                    }, enabled = !osmSyncing, modifier = Modifier.fillMaxWidth()) {
                        if (osmSyncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Sync, null)
                        Spacer(Modifier.width(8.dp)); Text(if (osmSyncing) "กำลังตรวจทางแยก…" else "ดึง/เพิ่มทางแยกจาก OSM")
                    }
                    osmMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShareButton({ exportOpen = true }, Icons.Default.FileUpload, "Export", Modifier.weight(1f), filled = true)
                    ShareButton({ onSaveTripFile(trip.id, "${tripDisplayName(trip).replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40)}.tripmap") }, Icons.Default.Save, "Save ทริป", Modifier.weight(1f))
                }
            }
        }
    }

    if (renameOpen) RenameTripDialog(trip, { renameOpen = false }) { onRename(trip.id, it); renameOpen = false }
    if (deleteOpen) DeleteTripDialog(trip, { deleteOpen = false }) { onDelete(trip.id); deleteOpen = false }

    // ✅ แก้จุดที่ 1 — ส่ง segments เข้า ExportDialog
    if (exportOpen) ExportDialog(trip, points, tripConfig, junctions, segments, onDismiss = { exportOpen = false })

    editJunction?.let { j ->
        JunctionEditDialog(
            junction = j, segments = segments,
            onDismiss = { editJunction = null },
            onSave = { onUpdateJunction(trip.id, it); editJunction = null },
            onDelete = { onDeleteJunction(trip.id, j.id); editJunction = null }
        )
    }
    editSegment?.let { seg ->
        RoadSegmentDialog(
            segment = seg, config = tripConfig,
            onDismiss = { editSegment = null },
            onSave = { onSaveSegment(trip.id, it); editSegment = null },
            onDelete = { onDeleteSegment(trip.id, seg.id); editSegment = null }
        )
    }
}

/** ✅ แก้จุดที่ 2 + 3 — รับ segments และส่งต่อไปยัง TripReport */
@Composable
private fun ExportDialog(
    trip: TripEntity,
    points: List<TrackPoint>,
    config: StartChainageConfig,
    junctions: List<JunctionPoint>,
    segments: List<RoadSegment>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val maxRoute = points.maxOfOrNull { it.chainageMeters.toInt() } ?: 0
    var startText by remember { mutableStateOf(chainageInput(config.displayMeters(0.0))) }
    var endText by remember { mutableStateOf(chainageInput(config.displayMeters(maxRoute.toDouble()))) }
    var interval by remember { mutableIntStateOf(1_000) }
    var format by remember { mutableStateOf(ReportFormat.PNG) }
    val startDisplay = parseChainageInput(startText).coerceAtLeast(0)
    val endDisplay = parseChainageInput(endText).coerceAtLeast(0)
    val rawStart = if (config.direction == ChainageDirection.LT) startDisplay - config.meters else config.meters - startDisplay
    val rawEnd = if (config.direction == ChainageDirection.LT) endDisplay - config.meters else config.meters - endDisplay
    val routeStart = minOf(rawStart, rawEnd).coerceIn(0, maxRoute)
    val routeEnd = maxOf(rawStart, rawEnd).coerceIn(routeStart, maxRoute)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("กำหนดช่วงก่อน Export") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("เลือกช่วงหลัก กม. ที่ต้องการส่งออก และเลือกระยะป้าย", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(startText, { startText = it.filter { c -> c.isDigit() || c == '+' }.take(12) }, Modifier.weight(1f), label = { Text("เริ่ม เช่น 9+500") }, singleLine = true)
                    OutlinedTextField(endText, { endText = it.filter { c -> c.isDigit() || c == '+' }.take(12) }, Modifier.weight(1f), label = { Text("ถึง เช่น 12+000") }, singleLine = true)
                }
                Text("ช่วงที่จะ Export: ${chainageLabel(config.displayMeters(routeStart.toDouble()), config.direction)} – ${chainageLabel(config.displayMeters(routeEnd.toDouble()), config.direction)}", style = MaterialTheme.typography.labelMedium)
                if (segments.isNotEmpty()) {
                    Text("รวมช่วงถนน ${segments.size} ช่วงในไฟล์ DXF/KMZ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text("ระยะป้าย/รายละเอียด", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(interval == 1_000, { interval = 1_000 }, label = { Text("ภาพรวม · ทุก 1 กม.") })
                    FilterChip(interval == 100, { interval = 100 }, label = { Text("รายละเอียด · ทุก 100 ม.") })
                }
                Text("รูปแบบ", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReportFormat.entries.forEach { f -> FilterChip(format == f, { format = f }, label = { Text(f.name) }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                TripReportExporter.share(context, TripReport(
                    trip.startedAt, trip.durationMillis, trip.netDistanceMeters, trip.traveledDistanceMeters,
                    points, tripDisplayName(trip), config, junctions,
                    roadSegments = segments,
                    selection = ExportSelection(routeStart, routeEnd, interval)
                ), format)
                onDismiss()
            }, enabled = points.size > 1) { Text("Export") }
        },
        dismissButton = { TextButton(onDismiss) { Text("ยกเลิก") } }
    )
}

private fun chainageInput(meters: Int): String = "${meters / 1000}+${(meters % 1000).toString().padStart(3, '0')}"

private fun parseChainageInput(value: String): Int {
    val v = value.trim().replace("กม.", "")
    val parts = v.split('+')
    return if (parts.size == 2) (parts[0].toIntOrNull() ?: 0) * 1000 + (parts[1].filter(Char::isDigit).toIntOrNull() ?: 0).coerceIn(0, 999)
    else (v.filter(Char::isDigit).toIntOrNull() ?: 0) * 1000
}

@Composable
private fun RenameTripDialog(trip: TripEntity, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(trip.id) { mutableStateOf(trip.name.ifBlank { tripDisplayName(trip) }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("แก้ไขชื่อทริป") },
        text = { OutlinedTextField(value, { value = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("ชื่อทริป") }, singleLine = true, supportingText = { Text("${value.length}/80") }) },
        confirmButton = { TextButton({ onSave(value) }, enabled = value.isNotBlank()) { Text("บันทึก") } },
        dismissButton = { TextButton(onDismiss) { Text("ยกเลิก") } }
    )
}

@Composable
private fun DeleteTripDialog(trip: TripEntity, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("ลบทริปนี้หรือไม่?") },
        text = { Text("${tripDisplayName(trip)} และข้อมูลเส้นทางจะถูกลบถาวร") },
        confirmButton = { TextButton(onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("ลบถาวร") } },
        dismissButton = { TextButton(onDismiss) { Text("ยกเลิก") } }
    )
}

private fun tripDisplayName(trip: TripEntity): String = trip.name.ifBlank {
    "ทริป ${SimpleDateFormat("d MMM yyyy HH:mm", Locale("th", "TH")).format(Date(trip.startedAt))}"
}

@Composable
private fun HistoryMap(
    points: List<TrackPoint>,
    junctions: List<JunctionPoint> = emptyList(),
    modifier: Modifier = Modifier,
    config: StartChainageConfig = StartChainageConfig(),
    roadSegments: List<RoadSegment> = emptyList(),
    showSegments: Boolean = true,
    onAddJunction: (JunctionPoint) -> Unit = {},
    onDeleteJunction: (Long) -> Unit = {},
    onJunctionTap: (JunctionPoint) -> Unit = {}
) {
    val coordinates = remember(points) { decimatePoints(points).map { LatLng(it.latitude, it.longitude) } }
    val camera = rememberCameraPositionState()
    var mapLoaded by remember { mutableStateOf(false) }
    var detailMode by remember { mutableStateOf(false) }
    var previewMeters by remember(points) { mutableFloatStateOf(points.maxOfOrNull { it.chainageMeters.toFloat() } ?: 0f) }
    var pendingAdd by remember { mutableStateOf<LatLng?>(null) }
    var pendingDeleteJunction by remember { mutableStateOf<JunctionPoint?>(null) }
    val maxChainage = remember(points) { points.maxOfOrNull { it.chainageMeters.toFloat() } ?: 0f }
    val interval = if (detailMode) 100 else 1_000
    val selectedKm = if (detailMode) previewMeters.toInt() / 1_000 else null
    val markers = remember(points, interval, config, selectedKm) { chainageMarkers(points, interval, config, selectedKm) }
    val segmentLines = remember(points, roadSegments, showSegments) {
        if (!showSegments) return@remember emptyList()
        roadSegments.mapNotNull { seg ->
            val lo = seg.startChainageMeters.toDouble()
            val hi = seg.endChainageMeters.toDouble()
            val slice = sliceByChainage(points, lo, hi)
            if (slice.size < 2) return@mapNotNull null
            val coords = decimatePoints(slice).map { p -> LatLng(p.latitude, p.longitude) }
            seg to coords
        }
    }

    LaunchedEffect(mapLoaded, coordinates) {
        if (!mapLoaded || coordinates.isEmpty()) return@LaunchedEffect
        if (coordinates.size == 1) camera.animate(CameraUpdateFactory.newLatLngZoom(coordinates.first(), 18f))
        else camera.animate(CameraUpdateFactory.newLatLngBounds(LatLngBounds.builder().apply { coordinates.forEach { include(it) } }.build(), 100))
    }
    Box(modifier) {
        GoogleMap(Modifier.fillMaxSize(), cameraPositionState = camera, onMapLoaded = { mapLoaded = true }, onMapLongClick = { pendingAdd = it }) {
            if (coordinates.size > 1) Polyline(points = coordinates, color = Color(0xFF9AA7B5), width = 11f, zIndex = 10f)
            segmentLines.forEach { (seg, coords) ->
                Polyline(points = coords, color = aciColor(seg.colorIndex), width = 17f, zIndex = 12f)
            }
            markers.forEach { marker ->
                val labelText = chainageLabel(marker.displayMeters, config.direction)
                Marker(
                    state = remember(marker.point) { MarkerState(LatLng(marker.point.latitude, marker.point.longitude)) },
                    title = labelText,
                    icon = remember(labelText) { chainageIconCached(labelText, false) },
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
                )
            }
            junctions.forEach { junction ->
                Marker(
                    state = remember(junction.id) { MarkerState(LatLng(junction.latitude, junction.longitude)) },
                    title = junctionLabel(junction),
                    snippet = "${chainageLabel(config.displayMeters(junction.nearestChainageMeters), config.direction)} · ${junction.effective(roadSegments).summary()}",
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE),
                    onClick = { onJunctionTap(junction); true }
                )
            }
        }
        Surface(Modifier.align(Alignment.BottomStart).padding(10.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp) {
            Column(Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(!detailMode, { detailMode = false }, label = { Text("ภาพรวม · 1 กม.") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(detailMode, { detailMode = true }, label = { Text("รายละเอียด · 100 ม.") })
                }
                if (detailMode && points.isNotEmpty()) {
                    Text("เลือกช่วง กม. ${(previewMeters / 1000).toInt()}", style = MaterialTheme.typography.labelSmall)
                    Slider(previewMeters, { previewMeters = it }, valueRange = 0f..maxChainage.coerceAtLeast(1f))
                }
                Text("แตะค้างเพื่อเพิ่มทางแยก • แตะจุดเพื่อแก้ไข", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
    pendingAdd?.let { latLng ->
        AddJunctionDialog(onDismiss = { pendingAdd = null }) { type, side ->
            onAddJunction(JunctionPoint(
                id = System.nanoTime(), latitude = latLng.latitude, longitude = latLng.longitude,
                type = type, side = side, source = JunctionSource.MANUAL,
                nearestChainageMeters = nearestChainageMeters(points, latLng.latitude, latLng.longitude)
            ))
            pendingAdd = null
        }
    }
    pendingDeleteJunction?.let { junction ->
        DeleteJunctionDialog(junction, { pendingDeleteJunction = null }, { onDeleteJunction(junction.id); pendingDeleteJunction = null })
    }
}