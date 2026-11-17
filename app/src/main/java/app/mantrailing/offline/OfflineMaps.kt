package app.mantrailing.offline

import android.content.Context
import app.mantrailing.ui.IGN_PLAN_TILES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import android.util.Log
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Taille du cache « ambiant » : les zones regardées récemment restent consultables sans réseau. */
const val AMBIENT_CACHE_BYTES = 200L * 1024 * 1024

/**
 * Style réduit au plan IGN pour les téléchargements : seules ses tuiles sont gardées (les photos aériennes
 * pèseraient bien plus). Les adresses des tuiles sont exactement celles de la carte ([IGN_PLAN_TILES]) :
 * MapLibre range les tuiles par adresse, la carte normale les retrouve donc sans réseau.
 */
internal val OFFLINE_STYLE_JSON = """
{
  "version": 8,
  "sources": { "plan": { "type": "raster", "tileSize": 256, "maxzoom": 19, "tiles": ["$IGN_PLAN_TILES"] } },
  "layers": [ { "id": "plan", "type": "raster", "source": "plan" } ]
}
""".trimIndent()

/** Adresse sous laquelle le style de téléchargement est rangé dans la base de MapLibre (jamais demandée au réseau). */
internal const val OFFLINE_STYLE_URL = "https://mantrailing.lesbrasseursdupastel.fr/cartes/plan-ign-hors-ligne.json"

@Serializable
private data class ZoneMeta(val name: String, val createdAt: Long, val s: Double, val w: Double, val n: Double, val e: Double)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Cartes hors ligne (MapLibre OfflineManager) : téléchargement d'une zone, liste, suppression.
 * Les appels MapLibre se font sur le fil principal ; le téléchargement continue en natif hors de l'écran.
 */
class OfflineMaps(private val context: Context) {
    private val manager: OfflineManager by lazy {
        MapLibre.getInstance(context)
        OfflineManager.getInstance(context)
    }

    /**
     * Adresse du style de téléchargement. Une région exige une URL, mais MapLibre refuse « file:// » pour le
     * téléchargement (« Unable to parse resourceUrl ») : le style est donc rangé directement dans sa base, sous une
     * adresse web, valable 10 ans ; le téléchargement le trouve là sans passer par le réseau.
     */
    private fun styleUrl(): String {
        val now = System.currentTimeMillis() / 1000
        manager.putResourceWithUrl(OFFLINE_STYLE_URL, OFFLINE_STYLE_JSON.encodeToByteArray(), now, now + 10L * 365 * 86_400, null, false)
        return OFFLINE_STYLE_URL
    }

    suspend fun raiseAmbientCache() = withContext(Dispatchers.Main) {
        manager.setMaximumAmbientCacheSize(AMBIENT_CACHE_BYTES, object : OfflineManager.FileSourceCallback {
            override fun onSuccess() = Unit
            override fun onError(message: String) = Unit
        })
    }

    private suspend fun regions(): Array<OfflineRegion> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) = cont.resume(offlineRegions ?: emptyArray())
                override fun onError(error: String) = cont.resumeWithException(IllegalStateException(error))
            })
        }
    }

    private suspend fun status(region: OfflineRegion): OfflineRegionStatus? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                override fun onStatus(status: OfflineRegionStatus?) = cont.resume(status)
                override fun onError(error: String?) = cont.resume(null)
            })
        }
    }

    /** Zones téléchargées, la plus récente d'abord. */
    suspend fun zones(): List<OfflineZone> = regions().map { region ->
        val meta = runCatching { json.decodeFromString(ZoneMeta.serializer(), region.metadata.decodeToString()) }.getOrNull()
        val status = status(region)
        OfflineZone(
            id = region.id,
            name = meta?.name ?: "Zone ${region.id}",
            createdAt = meta?.createdAt ?: 0,
            bytes = status?.completedResourceSize ?: 0,
            complete = status?.isComplete ?: false,
            area = meta?.let { Area(it.s, it.w, it.n, it.e) },
        )
    }.sortedByDescending { it.createdAt }

    /**
     * Télécharge [area] (tuiles du plan IGN, niveaux [OFFLINE_MIN_ZOOM] à [OFFLINE_MAX_ZOOM]).
     * [onProgress] reçoit le pourcentage ; [onDone] la fin (null : réussi, sinon le message d'erreur).
     * Renvoie l'identifiant de la zone (pour l'annuler).
     */
    suspend fun download(
        area: Area,
        name: String,
        createdAt: Long,
        onProgress: (Int) -> Unit,
        onDone: (String?) -> Unit,
    ): Long = withContext(Dispatchers.Main) {
        // Une tuile de 256 px est prise au niveau de zoom de la carte + 1 : la région se décrit en zoom de carte.
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl(),
            LatLngBounds.from(area.north, area.east, area.south, area.west),
            (OFFLINE_MIN_ZOOM - 1).toDouble(),
            (OFFLINE_MAX_ZOOM - 1).toDouble(),
            context.resources.displayMetrics.density,
        )
        val meta = json.encodeToString(ZoneMeta.serializer(), ZoneMeta(name, createdAt, area.south, area.west, area.north, area.east))
        val region = suspendCancellableCoroutine { cont ->
            manager.createOfflineRegion(definition, meta.encodeToByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) = cont.resume(offlineRegion)
                override fun onError(error: String) = cont.resumeWithException(IllegalStateException(error))
            })
        }
        var finished = false
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (finished) return
                val required = status.requiredResourceCount.coerceAtLeast(1)
                onProgress((100 * status.completedResourceCount / required).toInt().coerceIn(0, 100))
                if (status.isComplete) {
                    finished = true
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    onDone(null)
                }
            }

            // Erreurs passagères (tuile absente, réseau coupé) : MapLibre réessaie seul ; la progression reprend
            // avec le réseau, et « Annuler » reste possible.
            override fun onError(error: OfflineRegionError) {
                Log.w("OfflineMaps", "Téléchargement : ${error.reason} ${error.message}")
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                if (finished) return
                finished = true
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                onDone("Zone trop grande ($limit tuiles au plus).")
            }
        })
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        region.id
    }

    /** Arrête et supprime une zone (téléchargement annulé, ou zone qui n'est plus utile). */
    suspend fun delete(id: Long) = withContext(Dispatchers.Main) {
        val region = regions().find { it.id == id } ?: return@withContext
        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        suspendCancellableCoroutine { cont ->
            region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() = cont.resume(Unit)
                override fun onError(error: String) = cont.resumeWithException(IllegalStateException(error))
            })
        }
    }
}
