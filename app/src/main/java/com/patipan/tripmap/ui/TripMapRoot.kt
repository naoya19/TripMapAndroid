package com.patipan.tripmap.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.maps.android.compose.*
import com.patipan.tripmap.AppDiagnostics
import com.patipan.tripmap.data.TripEntity
import com.patipan.tripmap.data.TripWithPoints
import com.patipan.tripmap.share.*
import com.patipan.tripmap.tracking.TrackPoint
import com.patipan.tripmap.tracking.TrackingState
import com.patipan.tripmap.tracking.StartChainageConfig
import com.patipan.tripmap.tracking.ChainageDirection
import com.patipan.tripmap.tracking.VoiceMode
import com.patipan.tripmap.tracking.JunctionPoint
import com.patipan.tripmap.tracking.JunctionType
import com.patipan.tripmap.tracking.JunctionSide
import com.patipan.tripmap.tracking.JunctionSource
import com.patipan.tripmap.tracking.TrackingBus
import com.patipan.tripmap.tracking.nearestChainageMeters
import com.patipan.tripmap.tracking.haversineMeters
import com.patipan.tripmap.osm.OsmJunctionLookup
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.floor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripMapRoot(
    viewModel: TripViewModel,
    locationGranted: Boolean,
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
            requestPermissions, startTrip, stopTrip
        ) else HistoryScreen(
            Modifier.padding(padding), trips, viewModel::openTrip,
            viewModel::renameTrip, viewModel::deleteTrip, onImportTripFile, importMessage, clearImportMessage
        )
    }
}

@Composable
private fun TripScreen(
    modifier: Modifier,
    state: TrackingState,
    locationGranted: Boolean,
    requestPermissions: () -> Unit,
    startTrip: (StartChainageConfig, VoiceMode) -> Unit,
    stopTrip: () -> Unit
) {
    val context = LocalContext.current
    var diagnosticsOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var configuredStart by remember { mutableStateOf(StartChainageConfig()) }
    var configuredVoice by remember { mutableStateOf(VoiceMode.THAI) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isTracking) {
        while (state.isTracking) { now = System.currentTimeMillis(); delay(1_000) }
    }
    val elapsed = state.startedAt?.let { (now - it).coerceAtLeast(0) } ?: 0L

    Column(modifier.fillMaxSize()) {
        Surface(tonalElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Trip Map", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("จับระยะสุทธิตามแนวเส้นทาง", style = MaterialTheme.typography.bodySmall)
                }
                AssistChip(onClick = {}, label = {
                    Text(if (state.accuracyMeters == null) "รอ GPS" else "GPS ±${state.accuracyMeters.toInt()} ม.")
                })
                IconButton({ settingsOpen = true }) { Icon(Icons.Default.Settings, "ตั้งค่าจุดเริ่มและเสียง") }
                IconButton({ diagnosticsOpen = true }) {
                    Icon(Icons.Default.Info, "ตรวจสอบ Maps API")
                }
            }
        }

        Box(Modifier.weight(1f)) {
            RealtimeMap(state.points, state.junctions, locationGranted, activeConfig(state, configuredStart), onAddJunction = TrackingBus::addJunction, onRemoveJunction = TrackingBus::removeJunction)
            Card(Modifier.padding(12.dp).align(Alignment.TopStart)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    val active = activeConfig(state, configuredStart)
                    Text(chainageLabel(active.displayMeters(state.chainageMeters), active.direction), fontWeight = FontWeight.Bold)
                    Text("ระยะสุทธิ", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Surface(color = if (state.isTracking) Color(0xFFE8F1EC) else Color(0xFFF0EFED), shape = MaterialTheme.shapes.small) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.isTracking) "●" else "○", color = if (state.isTracking) Color(0xFF2D865E) else Color.Gray)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (state.isTracking) "REAL-TIME • กำลังลากเส้นตำแหน่ง" else "พร้อมเริ่มทริป", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        val latest = state.points.lastOrNull()
                        Text(latest?.let { "${"%.6f".format(it.latitude)}, ${"%.6f".format(it.longitude)}" } ?: "ยังไม่มีตำแหน่ง", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("ระยะสุทธิ", "%.2f กม.".format(state.chainageMeters / 1000), Modifier.weight(1f))
                Stat("เคลื่อนที่รวม", "%.2f กม.".format(state.traveledDistanceMeters / 1000), Modifier.weight(1f))
                Stat("ความเร็ว", "%.1f กม./ชม.".format(state.speedKmh), Modifier.weight(1f))
                Stat("เวลา", durationText(elapsed), Modifier.weight(1f))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VolumeUp, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.ttsStatus, style = MaterialTheme.typography.bodySmall)
                    state.lastSpokenText?.let { Text("ล่าสุด: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                }
                Text(voiceModeLabel(if (state.isTracking) state.voiceMode else configuredVoice), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }

            if (!locationGranted) {
                Button(requestPermissions, Modifier.fillMaxWidth()) { Text("อนุญาตตำแหน่ง GPS") }
            } else {
                Button(
                    onClick = { if (state.isTracking) stopTrip() else startTrip(configuredStart, configuredVoice) },
                    Modifier.fillMaxWidth(),
                    colors = if (state.isTracking) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
                ) {
                    Icon(if (state.isTracking) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp)); Text(if (state.isTracking) "หยุดและบันทึกทริป" else "เริ่มทริป")
                }
            }

            if (state.points.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShareButton({ shareCurrent(context, state, ReportFormat.PNG) }, Icons.Default.Image, "รูป", Modifier.weight(1f))
                ShareButton({ shareCurrent(context, state, ReportFormat.PDF) }, Icons.Default.PictureAsPdf, "PDF", Modifier.weight(1f))
                ShareButton({ shareCurrent(context, state, ReportFormat.KMZ) }, Icons.Default.Place, "KMZ", Modifier.weight(1f))
                ShareButton({ shareCurrent(context, state, ReportFormat.DXF) }, Icons.Default.Straighten, "DXF", Modifier.weight(1f))
            }

            if (state.points.size > 1) {
                var osmSyncing by remember { mutableStateOf(false) }
                var osmMessage by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()
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
                    if (osmSyncing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Sync, null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (osmSyncing) "กำลังดึงทางแยกจาก OSM…" else "ดึงทางแยกจาก OSM")
                }
                osmMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
            }
        }
    }
    if (settingsOpen) ChainageSettingsDialog(configuredStart, configuredVoice, { settingsOpen = false }) { start, voice ->
        configuredStart = start; configuredVoice = voice; settingsOpen = false
    }
    if (diagnosticsOpen) MapsDiagnosticsDialog(context) { diagnosticsOpen = false }
}

private fun activeConfig(state: TrackingState, configured: StartChainageConfig) = if (state.isTracking || state.points.size > 1) state.startChainage else configured

private fun voiceModeLabel(mode: VoiceMode) = when (mode) {
    VoiceMode.THAI -> "เสียง: ภาษาไทย"
    VoiceMode.ENGLISH -> "เสียง: English"
    VoiceMode.OFF -> "เสียง: ปิด"
}

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
            Text(if (direction == ChainageDirection.LT) "${chainageLabel(startMeters)} → ${chainageLabel(startMeters + 100)} → ${chainageLabel(startMeters + 200)}" else "${chainageLabel(startMeters)} → ${chainageLabel((startMeters - 100).coerceAtLeast(0))} → ${chainageLabel((startMeters - 200).coerceAtLeast(0))}", style = MaterialTheme.typography.bodySmall)
            Text("เสียง", fontWeight = FontWeight.SemiBold)
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
    val packageName = context.packageName
    val sha1 = remember { AppDiagnostics.signingSha1(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Info, null) },
        title = { Text("ตรวจสอบ Google Maps") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("นำค่า 2 รายการนี้ไปใส่ใน Google Cloud Console ให้ตรงทุกตัว")
                Text("Package name", style = MaterialTheme.typography.labelSmall)
                Text(packageName, fontWeight = FontWeight.SemiBold)
                Text("SHA-1 ของแอปที่ติดตั้ง", style = MaterialTheme.typography.labelSmall)
                Text(sha1, fontWeight = FontWeight.SemiBold)
                Text("หากเห็นโลโก้ Google แต่ไม่มีถนน ให้ทดลองปิด Application restrictions ชั่วคราว หากแผนที่ขึ้น แสดงว่า Package/SHA-1 restriction ยังไม่ตรง", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("ปิด") } }
    )
}

@Composable
private fun ShareButton(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, filled: Boolean = false) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
    val padding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    if (filled) {
        Button(onClick = onClick, modifier = modifier, contentPadding = padding, content = content)
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = padding, content = content)
    }
}

@Composable
private fun RealtimeMap(points: List<TrackPoint>, junctions: List<JunctionPoint>, locationGranted: Boolean, config: StartChainageConfig, onAddJunction: (JunctionPoint) -> Unit, onRemoveJunction: (Long) -> Unit) {
    val coordinates = points.map { LatLng(it.latitude, it.longitude) }
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(13.7563, 100.5018), 11f)
    }
    var mapLoaded by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var detailMode by remember { mutableStateOf(false) }
    var previewMeters by remember(points) { mutableFloatStateOf(points.maxOfOrNull { it.chainageMeters.toFloat() } ?: 0f) }
    var pendingJunctionLatLng by remember { mutableStateOf<LatLng?>(null) }
    var pendingDeleteJunction by remember { mutableStateOf<JunctionPoint?>(null) }

    LaunchedEffect(mapLoaded, coordinates.size, follow) {
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
            onMapClick = { follow = false },
            onMapLongClick = { latLng -> follow = false; pendingJunctionLatLng = latLng }
        ) {
            if (coordinates.size > 1) {
                Polyline(points = coordinates, color = Color(0xFF2783DE), width = 14f, zIndex = 10f, geodesic = true)
            }
            val markers = chainageMarkers(points, if (detailMode) 100 else 1_000, config, if (detailMode) previewMeters.toInt() / 1_000 else null)
            markers.forEachIndexed { index, marker ->
                Marker(state = MarkerState(LatLng(marker.point.latitude, marker.point.longitude)), title = chainageLabel(marker.displayMeters, config.direction), icon = chainageIcon(chainageLabel(marker.displayMeters, config.direction), index % 2 == 0), anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f), zIndex = 15f)
            }
            junctions.forEach { junction ->
                Marker(
                    state = MarkerState(LatLng(junction.latitude, junction.longitude)),
                    title = junctionLabel(junction),
                    snippet = if (junction.source == JunctionSource.OSM) "จาก OSM · แตะเพื่อลบ" else "แตะระบุเอง · แตะเพื่อลบ",
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE),
                    zIndex = 18f,
                    onClick = { pendingDeleteJunction = junction; true }
                )
            }
            coordinates.lastOrNull()?.let {
                Marker(state = MarkerState(it), title = "ตำแหน่ง Real-time", icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE), zIndex = 20f)
            }
        }
        FloatingActionButton(
            onClick = { follow = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            containerColor = Color.White
        ) { Icon(Icons.Default.MyLocation, "ติดตามตำแหน่ง") }
        Surface(Modifier.align(Alignment.BottomStart).padding(12.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(!detailMode, { detailMode = false }, label = { Text("ภาพรวม · ทุก 1 กม.") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(detailMode, { detailMode = true }, label = { Text("รายละเอียด · ทุก 100 ม.") })
                }
                if (detailMode) {
                    Text("เลื่อนเพื่อเลือกช่วง กม. ${(previewMeters / 1000).toInt()}", style = MaterialTheme.typography.labelSmall)
                    Slider(previewMeters, { previewMeters = it }, valueRange = 0f..(points.maxOfOrNull { it.chainageMeters.toFloat() }?.coerceAtLeast(1f) ?: 1f), enabled = points.size > 1)
                }
                Text("แตะค้างบนแผนที่เพื่อเพิ่มจุดทางแยก", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
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

    pendingDeleteJunction?.let { junction ->
        DeleteJunctionDialog(
            junction = junction,
            onDismiss = { pendingDeleteJunction = null },
            onConfirm = { onRemoveJunction(junction.id); pendingDeleteJunction = null }
        )
    }
}

@Composable
private fun DeleteJunctionDialog(junction: JunctionPoint, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("ลบจุดทางแยกนี้ใช่ไหม") },
        text = { Text("${junctionLabel(junction)} — ${if (junction.source == JunctionSource.OSM) "ดึงมาจาก OSM" else "แตะระบุเอง"}") },
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(type == JunctionType.THREE_WAY, { type = JunctionType.THREE_WAY }, label = { Text("3 แยก") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(type == JunctionType.FOUR_WAY, { type = JunctionType.FOUR_WAY }, label = { Text("4 แยก") })
                }
                if (type == JunctionType.THREE_WAY) {
                    Text("ด้านแขนแยก (เทียบทิศทางเดินทาง)", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(side == JunctionSide.LEFT, { side = JunctionSide.LEFT }, label = { Text("ซ้าย") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(side == JunctionSide.RIGHT, { side = JunctionSide.RIGHT }, label = { Text("ขวา") })
                    }
                } else {
                    Text("4 แยก จะตั้งแขนแยกทั้งสองด้านให้อัตโนมัติ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        },
        confirmButton = {
            TextButton({ onConfirm(type, if (type == JunctionType.FOUR_WAY) JunctionSide.BOTH else side) }) { Text("บันทึก") }
        },
        dismissButton = { TextButton(onDismiss) { Text("ยกเลิก") } }
    )
}

private fun junctionLabel(junction: JunctionPoint): String {
    val typeLabel = if (junction.type == JunctionType.THREE_WAY) "3 แยก" else "4 แยก"
    val sideLabel = when (junction.side) {
        JunctionSide.LEFT -> "ซ้าย"; JunctionSide.RIGHT -> "ขวา"; JunctionSide.BOTH -> "ทั้งสองด้าน"
    }
    return "$typeLabel ($sideLabel)"
}

private fun shareCurrent(context: Context, state: TrackingState, format: ReportFormat) {
    val start = state.startedAt ?: System.currentTimeMillis()
    TripReportExporter.share(context, TripReport(
        start, System.currentTimeMillis() - start,
        state.chainageMeters, state.traveledDistanceMeters, state.points,
        startChainage = state.startChainage,
        junctions = state.junctions
    ), format)
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

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
private fun HistoryTripItem(
    trip: TripEntity,
    onOpen: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit
) {
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
    onSaveTripFile: (Long, String) -> Unit
) {
    val context = LocalContext.current
    val trip = data.trip
    val tripConfig = StartChainageConfig(trip.startChainageMeters, if (trip.chainageDirection == ChainageDirection.RT.name) ChainageDirection.RT else ChainageDirection.LT)
    val points = remember(data.points) { data.points.sortedBy { it.sequence }.map { TrackPoint(it.latitude, it.longitude, it.accuracyMeters, it.timestamp, it.chainageMeters) } }
    val junctions = remember(data.junctions) { data.junctions.map { e -> JunctionPoint(
        id = e.id, latitude = e.latitude, longitude = e.longitude,
        type = runCatching { JunctionType.valueOf(e.type) }.getOrDefault(JunctionType.THREE_WAY),
        side = runCatching { JunctionSide.valueOf(e.side) }.getOrDefault(JunctionSide.RIGHT),
        source = runCatching { JunctionSource.valueOf(e.source) }.getOrDefault(JunctionSource.MANUAL),
        nearestChainageMeters = e.nearestChainageMeters, timestamp = e.timestamp
    ) } }
    var renameOpen by remember(trip.id) { mutableStateOf(false) }
    var deleteOpen by remember(trip.id) { mutableStateOf(false) }
    var exportOpen by remember(trip.id) { mutableStateOf(false) }
    var osmSyncing by remember(trip.id) { mutableStateOf(false) }
    var osmMessage by remember(trip.id) { mutableStateOf<String?>(null) }
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
                onAddJunction = { onAddJunction(trip.id, it) },
                onDeleteJunction = { onDeleteJunction(trip.id, it) }
            )
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("th", "TH")).format(Date(trip.startedAt)), fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth()) {
                    Stat("ระยะสุทธิ", "%.2f กม.".format(trip.netDistanceMeters / 1000), Modifier.weight(1f))
                    Stat("เคลื่อนที่รวม", "%.2f กม.".format(trip.traveledDistanceMeters / 1000), Modifier.weight(1f))
                    Stat("เวลา", durationText(trip.durationMillis), Modifier.weight(1f))
                    Stat("จุด GPS", "${points.size}", Modifier.weight(1f))
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
    if (exportOpen) ExportDialog(trip, points, tripConfig, junctions, onDismiss = { exportOpen = false })
}

@Composable
private fun ExportDialog(
    trip: TripEntity,
    points: List<TrackPoint>,
    config: StartChainageConfig,
    junctions: List<JunctionPoint>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val maxRoute = points.maxOfOrNull { it.chainageMeters.toInt() } ?: 0
    var startText by remember { mutableStateOf(chainageInput(config.displayMeters(0))) }
    var endText by remember { mutableStateOf(chainageInput(config.displayMeters(maxRoute))) }
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
                Text("ช่วงที่จะ Export: ${chainageLabel(config.displayMeters(routeStart), config.direction)} – ${chainageLabel(config.displayMeters(routeEnd), config.direction)}", style = MaterialTheme.typography.labelMedium)
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
                TripReportExporter.share(context, TripReport(trip.startedAt, trip.durationMillis, trip.netDistanceMeters, trip.traveledDistanceMeters, points, tripDisplayName(trip), config, junctions, ExportSelection(routeStart, routeEnd, interval)), format)
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
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(80) },
                label = { Text("ชื่อทริป") },
                singleLine = true,
                supportingText = { Text("${value.length}/80") }
            )
        },
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
    onAddJunction: (JunctionPoint) -> Unit = {},
    onDeleteJunction: (Long) -> Unit = {}
) {
    val coordinates = points.map { LatLng(it.latitude, it.longitude) }
    val camera = rememberCameraPositionState()
    var mapLoaded by remember { mutableStateOf(false) }
    var detailMode by remember { mutableStateOf(false) }
    var previewMeters by remember(points) { mutableFloatStateOf(points.maxOfOrNull { it.chainageMeters.toFloat() } ?: 0f) }
    var pendingAdd by remember { mutableStateOf<LatLng?>(null) }
    var pendingDeleteJunction by remember { mutableStateOf<JunctionPoint?>(null) }
    LaunchedEffect(mapLoaded, coordinates) {
        if (!mapLoaded || coordinates.isEmpty()) return@LaunchedEffect
        if (coordinates.size == 1) camera.animate(CameraUpdateFactory.newLatLngZoom(coordinates.first(), 18f))
        else camera.animate(CameraUpdateFactory.newLatLngBounds(LatLngBounds.builder().apply { coordinates.forEach { include(it) } }.build(), 100))
    }
    Box(modifier) {
        GoogleMap(
            Modifier.fillMaxSize(), cameraPositionState = camera, onMapLoaded = { mapLoaded = true },
            onMapLongClick = { pendingAdd = it }
        ) {
            if (coordinates.size > 1) Polyline(points = coordinates, color = Color(0xFF2783DE), width = 14f, zIndex = 10f)
            val markers = chainageMarkers(points, if (detailMode) 100 else 1_000, config, if (detailMode) previewMeters.toInt() / 1_000 else null)
            markers.forEach { marker ->
                Marker(state = MarkerState(LatLng(marker.point.latitude, marker.point.longitude)), title = chainageLabel(marker.displayMeters, config.direction), icon = chainageIcon(chainageLabel(marker.displayMeters, config.direction), false), anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f))
            }
            junctions.forEach { junction ->
                Marker(state = MarkerState(LatLng(junction.latitude, junction.longitude)), title = junctionLabel(junction), snippet = "แตะเพื่อลบ", icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE), onClick = { pendingDeleteJunction = junction; true })
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
                    Slider(previewMeters, { previewMeters = it }, valueRange = 0f..(points.maxOf { it.chainageMeters.toFloat() }.coerceAtLeast(1f)))
                }
                Text("แตะค้างเพื่อเพิ่มทางแยก • แตะจุดทางแยกเพื่อลบ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
    pendingAdd?.let { latLng ->
        AddJunctionDialog(onDismiss = { pendingAdd = null }) { type, side ->
            onAddJunction(JunctionPoint(id = System.nanoTime(), latitude = latLng.latitude, longitude = latLng.longitude, type = type, side = side, source = JunctionSource.MANUAL, nearestChainageMeters = nearestChainageMeters(points, latLng.latitude, latLng.longitude)))
            pendingAdd = null
        }
    }
    pendingDeleteJunction?.let { junction ->
        DeleteJunctionDialog(junction, { pendingDeleteJunction = null }, { onDeleteJunction(junction.id); pendingDeleteJunction = null })
    }
}

