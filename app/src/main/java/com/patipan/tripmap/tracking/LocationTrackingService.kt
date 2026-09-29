package com.patipan.tripmap.tracking

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.patipan.tripmap.MainActivity
import com.patipan.tripmap.TripMapApplication
import com.patipan.tripmap.data.TripEntity
import com.patipan.tripmap.data.TrackPointEntity
import com.patipan.tripmap.data.JunctionEntity
import com.patipan.tripmap.osm.OsmJunctionLookup
import com.patipan.tripmap.voice.ThaiDistanceSpeaker
import kotlinx.coroutines.*
import kotlin.math.floor

class LocationTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationClient: FusedLocationProviderClient
    private lateinit var speaker: ThaiDistanceSpeaker
    private val engine = GeoTrackEngine()
    private val sessionPoints = mutableListOf<TrackPoint>()
    private var startedAt = 0L
    private var nextAnnouncement = 100
    private var previousChainage = 0.0
    private var startChainage = StartChainageConfig()
    private var voiceMode = VoiceMode.THAI

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) { result.locations.forEach(::consumeLocation) }
    }

    override fun onCreate() {
        super.onCreate()
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        speaker = ThaiDistanceSpeaker(this) { status ->
            TrackingBus.update(TrackingBus.state.value.copy(ttsStatus = status))
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent?.let(::startTrip)
            ACTION_STOP -> stopTrip()
        }
        return START_NOT_STICKY
    }

    private fun startTrip(intent: Intent) {
        if (TrackingBus.state.value.isTracking) return
        startChainage = StartChainageConfig(
            intent.getIntExtra(EXTRA_START_METERS, 0).coerceAtLeast(0),
            if (intent.getStringExtra(EXTRA_DIRECTION) == ChainageDirection.RT.name) ChainageDirection.RT else ChainageDirection.LT
        )
        voiceMode = VoiceMode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_VOICE_MODE) } ?: VoiceMode.THAI
        speaker.setMode(voiceMode)
        startedAt = System.currentTimeMillis()
        sessionPoints.clear(); engine.reset(); previousChainage = 0.0
        nextAnnouncement = if (startChainage.direction == ChainageDirection.LT) 100 else 0
        TrackingBus.update(TrackingState(isTracking = true, startedAt = startedAt, ttsStatus = TrackingBus.state.value.ttsStatus, startChainage = startChainage, voiceMode = voiceMode))
        startForeground(NOTIFICATION_ID, notification(0.0))
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            TrackingBus.update(TrackingBus.state.value.copy(isTracking = false))
            stopSelf(); return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(1.5f)
            .setWaitForAccurateLocation(false)
            .build()
        locationClient.requestLocationUpdates(request, callback, mainLooper)
    }

    private fun consumeLocation(location: Location) {
        val update = engine.add(GeoPoint(location.latitude, location.longitude), location.accuracy)
        if (!update.accepted) return
        val point = TrackPoint(location.latitude, location.longitude, location.accuracy, location.time, update.chainageMeters)
        sessionPoints += point

        var spokenText = TrackingBus.state.value.lastSpokenText
        if (update.chainageMeters < previousChainage - 5.0) {
            nextAnnouncement = (floor(update.chainageMeters / 100.0).toInt() + 1) * 100
        } else {
            while (update.chainageMeters >= nextAnnouncement) {
                val labelAt = startChainage.displayMeters(nextAnnouncement.toDouble())
                val spoken = speaker.speakChainage(labelAt)
                if (spoken.isNotBlank()) spokenText = spoken
                nextAnnouncement += 100
            }
        }
        previousChainage = update.chainageMeters
        TrackingBus.update(TrackingBus.state.value.copy(
            isTracking = true,
            startedAt = startedAt,
            chainageMeters = update.chainageMeters,
            traveledDistanceMeters = update.traveledMeters,
            accuracyMeters = location.accuracy,
            speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f,
            points = sessionPoints.toList(),
            lastSpokenText = spokenText,
            startChainage = startChainage,
            voiceMode = voiceMode
        ))
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(update.chainageMeters))
    }

    private fun stopTrip() {
        if (!TrackingBus.state.value.isTracking) { stopSelf(); return }
        locationClient.removeLocationUpdates(callback)
        val snapshot = TrackingBus.state.value
        val endedAt = System.currentTimeMillis()
        TrackingBus.update(snapshot.copy(isTracking = false, speedKmh = 0f, ttsStatus = "กำลังบันทึกทริปและตรวจทางแยกจาก OSM…"))
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.launch {
            val dao = (application as TripMapApplication).database.tripDao()
            val tripId = dao.insertTrip(TripEntity(
                startedAt = startedAt, endedAt = endedAt,
                netDistanceMeters = snapshot.chainageMeters,
                traveledDistanceMeters = snapshot.traveledDistanceMeters,
                durationMillis = endedAt - startedAt,
                startChainageMeters = startChainage.meters,
                chainageDirection = startChainage.direction.name
            ))
            dao.insertPoints(snapshot.points.mapIndexed { index, p -> TrackPointEntity(
                tripId = tripId, sequence = index, latitude = p.latitude, longitude = p.longitude,
                accuracyMeters = p.accuracyMeters, timestamp = p.timestamp, chainageMeters = p.chainageMeters
            ) })

            // ตรวจทางแยกจาก OSM อัตโนมัติหลังจบทริป แล้วรวมกับจุดที่ผู้ใช้แตะระบุเองระหว่างทริป
            // (จุดที่แตะเองไว้ในระยะใกล้กัน ถือว่าเป็นจุดเดียวกัน ไม่เพิ่มซ้ำ)
            val osmJunctions = (OsmJunctionLookup.fetchJunctions(snapshot.points) as? OsmJunctionLookup.Result.Success)?.junctions ?: emptyList()
            val merged = snapshot.junctions + osmJunctions.filter { osm ->
                snapshot.junctions.none { haversineMeters(it.latitude, it.longitude, osm.latitude, osm.longitude) < 15.0 }
            }
            if (merged.isNotEmpty()) {
                dao.insertJunctions(merged.map { j -> JunctionEntity(
                    tripId = tripId, latitude = j.latitude, longitude = j.longitude,
                    type = j.type.name, side = j.side.name, source = j.source.name,
                    nearestChainageMeters = j.nearestChainageMeters, timestamp = j.timestamp
                ) })
            }
            // อัปเดตหน้าจอ เผื่อยังเปิดค้างอยู่ ให้เห็นจุดที่ระบบดึงจาก OSM มาเพิ่มด้วย
            TrackingBus.update(TrackingBus.state.value.copy(junctions = merged, ttsStatus = "บันทึกทริปแล้ว"))
            stopSelf()
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "กำลังจับระยะทาง", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(distance: Double): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Trip Map กำลังทำงาน")
            .setContentText("ระยะสุทธิ %.2f กม.".format(distance / 1000.0))
            .setContentIntent(pending).setOngoing(true).build()
    }

    override fun onDestroy() {
        locationClient.removeLocationUpdates(callback); speaker.shutdown(); scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.patipan.tripmap.START"
        const val ACTION_STOP = "com.patipan.tripmap.STOP"
        const val EXTRA_START_METERS = "start_meters"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_VOICE_MODE = "voice_mode"
        private const val CHANNEL_ID = "trip_tracking"
        private const val NOTIFICATION_ID = 7001
    }
}
