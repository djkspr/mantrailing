package app.mantrailing.offline

import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** Zone rectangulaire (degrés). */
data class Area(val south: Double, val west: Double, val north: Double, val east: Double) {
    val centerLat: Double get() = (south + north) / 2
    val centerLon: Double get() = (west + east) / 2

    /** Largeur et hauteur approximatives, en km. */
    val widthKm: Double get() = (east - west) * KM_PER_DEG_LAT * cos(Math.toRadians(centerLat))
    val heightKm: Double get() = (north - south) * KM_PER_DEG_LAT

    companion object {
        /** Carré de [halfSideKm] km de demi-côté autour d'un point. */
        fun around(lat: Double, lon: Double, halfSideKm: Double): Area {
            val dLat = halfSideKm / KM_PER_DEG_LAT
            val dLon = halfSideKm / (KM_PER_DEG_LAT * cos(Math.toRadians(lat)))
            return Area(lat - dLat, lon - dLon, lat + dLat, lon + dLon)
        }
    }
}

private const val KM_PER_DEG_LAT = 111.32

/** Niveaux téléchargés : du quartier (12) au chemin (17) ; au-delà, l'IGN n'est pas utile hors ligne. */
const val OFFLINE_MIN_ZOOM = 12
const val OFFLINE_MAX_ZOOM = 17

/** Zone visible plus grande que ça (en km de côté) : on télécharge seulement autour du centre. */
const val OFFLINE_MAX_SIDE_KM = 8.0

/** Demi-côté de la zone prise autour du centre quand la carte est trop dézoomée (≈ 3 km de rayon). */
const val OFFLINE_AROUND_KM = 3.0

/** Limite de tuiles par zone (conditions de la Géoplateforme IGN : usage raisonnable). */
const val OFFLINE_MAX_TILES = 6_000L

/** Poids moyen observé d'une tuile du plan IGN (PNG 256 px), pour l'estimation. */
const val AVERAGE_TILE_BYTES = 22_000L

/**
 * Zone à télécharger : la zone visible si elle est raisonnable, sinon ~3 km autour de son centre
 * (carte trop dézoomée : on ne télécharge pas la France entière).
 */
fun downloadArea(visible: Area): Area =
    if (visible.widthKm <= OFFLINE_MAX_SIDE_KM && visible.heightKm <= OFFLINE_MAX_SIDE_KM) visible
    else Area.around(visible.centerLat, visible.centerLon, OFFLINE_AROUND_KM)

private fun tileX(lon: Double, z: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

private fun tileY(lat: Double, z: Int): Int {
    val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
    return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
}

/** Nombre de tuiles (carte « web mercator ») couvrant la zone, de [minZoom] à [maxZoom] compris. */
fun estimateTiles(area: Area, minZoom: Int = OFFLINE_MIN_ZOOM, maxZoom: Int = OFFLINE_MAX_ZOOM): Long =
    (minZoom..maxZoom).sumOf { z ->
        val xs = tileX(area.east, z) - tileX(area.west, z) + 1
        val ys = tileY(area.south, z) - tileY(area.north, z) + 1
        xs.toLong() * ys.toLong()
    }

/** Inverse de [tileY] (haut de la tuile), utile aux tests. */
internal fun tileTopLat(y: Int, z: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / (1 shl z)))))

/** « 340 Ko », « 85 Mo », « 1,2 Go ». */
fun formatBytes(bytes: Long): String = when {
    bytes < 1_000_000 -> "%.0f Ko".format(Locale.FRENCH, bytes / 1_000.0)
    bytes < 1_000_000_000 -> "%.0f Mo".format(Locale.FRENCH, bytes / 1_000_000.0)
    else -> "%.1f Go".format(Locale.FRENCH, bytes / 1_000_000_000.0)
}

/** Zone téléchargée, telle que listée dans les paramètres. */
data class OfflineZone(
    val id: Long,
    val name: String,
    /** Date du téléchargement, en secondes Unix. */
    val createdAt: Long,
    val bytes: Long,
    val complete: Boolean,
    val area: Area?,
)

/** Résumé des zones pour le menu des paramètres : « 2 zones · 85 Mo », « Aucune zone ». */
fun offlineSummary(zones: List<OfflineZone>): String = when {
    zones.isEmpty() -> "Aucune zone : la carte demande du réseau"
    else -> "${zones.size} zone${if (zones.size > 1) "s" else ""} · ${formatBytes(zones.sumOf { it.bytes })}"
}

/** Téléchargement en cours : [percent] de 0 à 100. */
data class OfflineDownload(val name: String, val percent: Int, val tiles: Long)
