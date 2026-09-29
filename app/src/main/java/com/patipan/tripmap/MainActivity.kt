package com.patipan.tripmap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.patipan.tripmap.tracking.LocationTrackingService
import com.patipan.tripmap.tracking.TrackPoint
import com.patipan.tripmap.tracking.TrackingBus
import com.patipan.tripmap.tracking.StartChainageConfig
import com.patipan.tripmap.tracking.VoiceMode
import com.patipan.tripmap.ui.TripMapRoot
import com.patipan.tripmap.ui.TripMapTheme
import com.patipan.tripmap.ui.TripViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TripMapTheme {
                val vm: TripViewModel = viewModel()
                var locationGranted by remember { mutableStateOf(hasLocationPermission()) }

                val exportMessage by vm.exportMessage.collectAsState()
                val viewNotice by vm.notice.collectAsState()

                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                    locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasLocationPermission()
                }
                LaunchedEffect(locationGranted) {
                    if (locationGranted) loadPreviewLocation()
                }
                LaunchedEffect(exportMessage) {
                    val message = exportMessage ?: return@LaunchedEffect
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    vm.clearExportMessage()
                }
                LaunchedEffect(viewNotice) {
                    val message = viewNotice ?: return@LaunchedEffect
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    vm.clearNotice()
                }

                var saveTripId by rememberSaveable { mutableStateOf(0L) }
                var importMessage by rememberSaveable { mutableStateOf<String?>(null) }

                val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
                    if (uri != null && saveTripId != 0L) vm.exportTrip(this@MainActivity, saveTripId, uri)
                }
                val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    if (uri != null) vm.importTrip(this@MainActivity, uri,
                        onDone = { id -> vm.openTrip(id); importMessage = "นำเข้าทริปเรียบร้อยแล้ว" },
                        onError = { importMessage = it })
                }
                TripMapRoot(
                    viewModel = vm,
                    locationGranted = locationGranted,
                    notice = viewNotice,
                    onClearNotice = vm::clearNotice,
                    onSaveTripFile = { tripId, suggestedName -> saveTripId = tripId; saveLauncher.launch(suggestedName) },
                    onImportTripFile = { importMessage = null; importLauncher.launch(arrayOf("*/*")) },
                    importMessage = importMessage,
                    clearImportMessage = { importMessage = null },
                    requestPermissions = {
                        launcher.launch(buildList {
                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }.toTypedArray())
                    },
                    startTrip = { startChainage: StartChainageConfig, voiceMode: VoiceMode ->
                        ContextCompat.startForegroundService(this, Intent(this, LocationTrackingService::class.java)
                            .setAction(LocationTrackingService.ACTION_START)
                            .putExtra(LocationTrackingService.EXTRA_START_METERS, startChainage.meters)
                            .putExtra(LocationTrackingService.EXTRA_DIRECTION, startChainage.direction.name)
                            .putExtra(LocationTrackingService.EXTRA_VOICE_MODE, voiceMode.name))
                    },
                    stopTrip = {
                        startService(Intent(this, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_STOP))
                    }
                )
            }
        }
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun loadPreviewLocation() {
        if (!hasLocationPermission() || TrackingBus.state.value.isTracking) return
        val token = CancellationTokenSource()
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
            .addOnSuccessListener { location ->
                if (location != null && !TrackingBus.state.value.isTracking) {
                    TrackingBus.update(TrackingBus.state.value.copy(
                        accuracyMeters = location.accuracy,
                        points = listOf(TrackPoint(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyMeters = location.accuracy,
                            timestamp = location.time,
                            chainageMeters = 0.0
                        ))
                    ))
                }
            }
    }
}