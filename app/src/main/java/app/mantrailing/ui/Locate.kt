package app.mantrailing.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Une position récente suffit pour centrer la carte : pas besoin d'attendre le GPS. */
private const val RECENT_MS = 2 * 60_000L

/**
 * Position actuelle du téléphone, pour « Me localiser » (autorisation de localisation déjà accordée) : la dernière
 * connue si elle est récente, sinon une nouvelle mesure (GPS, ou réseau à défaut), au plus [timeoutMs]. Null : aucune.
 */
@SuppressLint("MissingPermission")
suspend fun currentLocation(context: Context, timeoutMs: Long = 15_000): Location? {
    val manager = ContextCompat.getSystemService(context, LocationManager::class.java) ?: return null
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.isProviderEnabled(it) }
    if (providers.isEmpty()) return null
    val recent = providers.mapNotNull { manager.getLastKnownLocation(it) }
        .filter { System.currentTimeMillis() - it.time < RECENT_MS }
        .minByOrNull { it.accuracy }
    if (recent != null) return recent
    return withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val cancel = CancellationSignal()
            continuation.invokeOnCancellation { cancel.cancel() }
            LocationManagerCompat.getCurrentLocation(manager, providers.first(), cancel, ContextCompat.getMainExecutor(context)) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        }
    }
}
