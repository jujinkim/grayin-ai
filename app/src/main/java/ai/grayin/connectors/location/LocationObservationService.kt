package ai.grayin.connectors.location

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
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import ai.grayin.R
import ai.grayin.core.store.SqlCipherLocalMemoryStore
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LocationObservationPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    @SuppressLint("UseKtx")
    fun setEnabled(enabled: Boolean) {
        check(preferences.edit().putBoolean(KEY_ENABLED, enabled).commit()) {
            "Could not persist location observation state."
        }
    }

    private companion object {
        const val PREFS_NAME = "grayin_location_observation"
        const val KEY_ENABLED = "enabled"
    }
}

class LocationObservationService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences by lazy { LocationObservationPreferences(applicationContext) }
    private val connector by lazy { LocationConnector(applicationContext) }
    private var locationManager: LocationManager? = null
    private var lastAcceptedAt: Instant? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            preferences.setEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasLocationPermission()) {
            preferences.setEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }

        startVisibleForeground()
        if (!requestUpdates()) {
            preferences.setEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        preferences.setEnabled(true)
        return START_NOT_STICKY
    }

    override fun onLocationChanged(location: Location) {
        val receivedAt = Instant.now()
        if (!LocationObservationAcceptancePolicy.shouldAccept(location, receivedAt, lastAcceptedAt)) return
        lastAcceptedAt = receivedAt
        val transientCopy = Location(location)
        scope.launch {
            LocationObservationConsentCoordinator.withExclusiveAccess {
                if (!preferences.isEnabled()) return@withExclusiveAccess
                val store = SqlCipherLocalMemoryStore(applicationContext)
                if (store.isConnectorReconsentRequired(LocationConnector.CONNECTOR_ID)) {
                    preferences.setEnabled(false)
                    stopSelf()
                    return@withExclusiveAccess
                }
                try {
                    val scan = connector.scanObservedLocation(
                        location = transientCopy,
                        indexedAt = receivedAt,
                        includeOnlineEnrichment = false,
                    )
                    store.saveConnectorScan(scan)
                    connector.onScanStored(scan)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@withExclusiveAccess
                }
            }
        }
    }

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    @Deprecated("Deprecated by Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onDestroy() {
        locationManager?.removeUpdates(this)
        preferences.setEnabled(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun requestUpdates(): Boolean {
        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
        if (providers.isEmpty()) return false
        locationManager = manager
        return providers.any { provider ->
            try {
                manager.requestLocationUpdates(
                    provider,
                    MIN_UPDATE_INTERVAL_MILLIS,
                    MIN_UPDATE_DISTANCE_METERS,
                    this,
                    Looper.getMainLooper(),
                )
                true
            } catch (_: SecurityException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    }

    private fun startVisibleForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, LocationObservationService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.location_observation_notification_title))
            .setContentText(getString(R.string.location_observation_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.location_observation_notification_stop),
                    stopPendingIntent,
                ).build(),
            )
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.location_observation_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.location_observation_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, LocationObservationService::class.java).setAction(ACTION_START)
            val preferences = LocationObservationPreferences(context)
            preferences.setEnabled(true)
            try {
                context.startForegroundService(intent)
            } catch (error: RuntimeException) {
                preferences.setEnabled(false)
                throw error
            }
        }

        fun stop(context: Context) {
            LocationObservationPreferences(context).setEnabled(false)
            context.stopService(Intent(context, LocationObservationService::class.java))
        }

        private const val ACTION_START = "ai.grayin.location.START_OBSERVATION"
        private const val ACTION_STOP = "ai.grayin.location.STOP_OBSERVATION"
        private const val CHANNEL_ID = "grayin_location_observation"
        private const val NOTIFICATION_ID = 4301
        private const val STOP_REQUEST_CODE = 4302
        private const val MIN_UPDATE_INTERVAL_MILLIS = 15 * 60 * 1_000L
        private const val MIN_UPDATE_DISTANCE_METERS = 250f
    }
}

internal object LocationObservationAcceptancePolicy {
    fun shouldAccept(location: Location, receivedAt: Instant, lastAcceptedAt: Instant?): Boolean {
        val sampleAt = location.time.takeIf { timestamp -> timestamp > 0L }?.let(Instant::ofEpochMilli) ?: receivedAt
        val accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() }?.toDouble()
        return shouldAccept(
            candidate = LocationObservationCandidate(sampleAt, accuracyMeters),
            receivedAt = receivedAt,
            lastAcceptedAt = lastAcceptedAt,
        )
    }

    fun shouldAccept(
        candidate: LocationObservationCandidate,
        receivedAt: Instant,
        lastAcceptedAt: Instant?,
    ): Boolean {
        if (candidate.sampleAt > receivedAt.plus(MAX_FUTURE_SKEW)) return false
        if (candidate.sampleAt < receivedAt.minus(MAX_SAMPLE_AGE)) return false
        if (
            candidate.accuracyMeters != null &&
            (!candidate.accuracyMeters.isFinite() || candidate.accuracyMeters !in 0.0..MAX_ACCURACY_METERS)
        ) return false
        return lastAcceptedAt == null || Duration.between(lastAcceptedAt, receivedAt) >= MIN_ACCEPTED_INTERVAL
    }

    private val MAX_FUTURE_SKEW: Duration = Duration.ofMinutes(2)
    private val MAX_SAMPLE_AGE: Duration = Duration.ofMinutes(10)
    private val MIN_ACCEPTED_INTERVAL: Duration = Duration.ofMinutes(5)
    private const val MAX_ACCURACY_METERS = 5_000.0
}

internal data class LocationObservationCandidate(
    val sampleAt: Instant,
    val accuracyMeters: Double?,
)

internal object LocationObservationConsentCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withExclusiveAccess(block: suspend () -> T): T = mutex.withLock { block() }
}
