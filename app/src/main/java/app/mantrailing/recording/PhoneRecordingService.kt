package app.mantrailing.recording

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.mantrailing.MainActivity
import app.mantrailing.R
import app.mantrailing.session.RecordingBuffer
import app.mantrailing.session.acceptPhoneFix
import app.mantrailing.track.PHONE_GPS_SENSOR
import app.mantrailing.tractive.Position
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Enregistre le GPS du téléphone pendant une piste (service au premier plan, type « location ») :
 * l'enregistrement continue écran éteint, sans autorisation de localisation en arrière-plan.
 */
class PhoneRecordingService : Service(), LocationListener {
    private lateinit var buffer: RecordingBuffer
    private var locationManager: LocationManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        buffer = bufferOf(this)
        _track.value = buffer.read()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasLocationPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val laying = intent?.getBooleanExtra(EXTRA_TRAIL, false) ?: false
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(laying), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        startUpdates()
        _running.value = true
        return START_STICKY
    }

    @SuppressLint("MissingPermission") // vérifiée dans onStartCommand
    private fun startUpdates() {
        if (locationManager != null) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        locationManager = lm
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, this, Looper.getMainLooper())
    }

    override fun onLocationChanged(location: Location) {
        if (!acceptPhoneFix(if (location.hasAccuracy()) location.accuracy else null)) return
        val position = location.toPosition()
        buffer.append(position)
        _track.value = _track.value + position
    }

    @Deprecated("Requis sur les anciennes versions d'Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    override fun onDestroy() {
        locationManager?.removeUpdates(this)
        locationManager = null
        _running.value = false
        super.onDestroy()
    }

    private fun notification(laying: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Enregistrement en cours", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Affichée pendant l'enregistrement GPS d'une piste ou d'une recherche"
            },
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (laying) "Pose de la piste en cours" else "Recherche en cours")
            .setContentText(
                if (laying) "Enregistrement GPS du parcours du traceur. Ouvrez l'appli pour terminer la pose."
                else "Enregistrement GPS du téléphone. Ouvrez l'appli pour terminer la recherche.",
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val INTERVAL_MS = 1_000L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _track = MutableStateFlow<List<Position>>(emptyList())
        /** Positions retenues depuis le début de l'enregistrement en cours, pour l'afficher au fur et à mesure. */
        val track: StateFlow<List<Position>> = _track.asStateFlow()

        fun bufferOf(context: Context) = RecordingBuffer(File(context.filesDir, "recording/current.jsonl"))

        fun hasLocationPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        private const val EXTRA_TRAIL = "trail"

        /** [layingTrail] : pose de la piste par le traceur (sinon recherche du chien), pour le texte de la notification. */
        fun start(context: Context, layingTrail: Boolean = false) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PhoneRecordingService::class.java).putExtra(EXTRA_TRAIL, layingTrail),
            )
        }

        /** Arrête l'enregistrement et renvoie les positions enregistrées (le tampon est vidé). */
        fun stop(context: Context): List<Position> {
            context.stopService(Intent(context, PhoneRecordingService::class.java))
            val buffer = bufferOf(context)
            return buffer.read().also {
                buffer.clear()
                _track.value = emptyList()
            }
        }
    }
}

internal fun Location.toPosition() = Position(
    time = time / 1000,
    lat = latitude,
    lon = longitude,
    alt = if (hasAltitude()) altitude else null,
    uncertainty = if (hasAccuracy()) accuracy.toDouble() else null,
    sensor = PHONE_GPS_SENSOR,
)
