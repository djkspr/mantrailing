package app.mantrailing.gpx

import app.mantrailing.tractive.Position
import org.w3c.dom.Element
import java.io.InputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Capteur noté sur les positions lues dans un fichier GPX (Garmin, autre appli…). */
const val GPX_SENSOR = "GPX"

class GpxException(message: String) : Exception(message)

/**
 * Trace lue dans un fichier GPX : segments de positions dans l'ordre du fichier.
 * [hasTimes] : faux si le fichier ne donne pas l'heure des points (trace dessinée sur une carte, itinéraire) ;
 * les positions ont alors une heure fictive, une seconde d'écart à partir de 0.
 */
data class GpxTrack(val name: String?, val segments: List<List<Position>>, val hasTimes: Boolean) {
    val points: List<Position> get() = segments.flatten()
    val startedAt: Long? get() = points.minOfOrNull { it.time }?.takeIf { hasTimes }
    val endedAt: Long? get() = points.maxOfOrNull { it.time }?.takeIf { hasTimes }

    /** Même trace, heures fictives remplacées par une heure de départ choisie (fichier sans heures). */
    fun startingAt(start: Long): GpxTrack =
        if (hasTimes) this else copy(segments = segments.map { seg -> seg.map { it.copy(time = it.time + start) } }, hasTimes = true)
}

/** Lecteur GPX 1.0 / 1.1 : traces (trk/trkseg/trkpt), sinon itinéraire (rte/rtept). */
object Gpx {
    private const val MAX_POINTS = 200_000

    fun parse(input: InputStream): GpxTrack {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // Pas d'entités externes : un fichier piégé ne doit rien lire d'autre sur le téléphone.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        }
        val doc = try {
            factory.newDocumentBuilder().parse(input)
        } catch (e: Exception) {
            throw GpxException("Ce fichier n'est pas un GPX lisible.")
        }
        val root = doc.documentElement
        if (root?.localName != "gpx" && root?.nodeName?.substringAfter(':') != "gpx") throw GpxException("Ce fichier n'est pas un GPX.")

        val tracks = root.children("trk")
        var raw: List<List<Element>> = tracks.flatMap { trk -> trk.children("trkseg").map { it.children("trkpt") } }
        if (raw.all { it.isEmpty() }) raw = root.children("rte").map { it.children("rtept") }
        raw = raw.filter { it.isNotEmpty() }
        if (raw.isEmpty()) throw GpxException("Aucune trace dans ce fichier GPX.")
        if (raw.sumOf { it.size } > MAX_POINTS) throw GpxException("Trace trop longue (plus de $MAX_POINTS points).")

        val name = (tracks.firstOrNull() ?: root.children("rte").firstOrNull())?.children("name")?.firstOrNull()?.textContent?.trim()
            ?: root.children("metadata").firstOrNull()?.children("name")?.firstOrNull()?.textContent?.trim()
        val times = raw.map { seg -> seg.map { pt -> pt.children("time").firstOrNull()?.textContent?.let(::parseTime) } }
        val hasTimes = times.all { seg -> seg.all { it != null } }
        var counter = 0L
        val segments = raw.mapIndexed { s, seg ->
            seg.mapIndexedNotNull { i, pt ->
                val lat = pt.getAttribute("lat").toDoubleOrNull() ?: return@mapIndexedNotNull null
                val lon = pt.getAttribute("lon").toDoubleOrNull() ?: return@mapIndexedNotNull null
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapIndexedNotNull null
                Position(
                    time = if (hasTimes) times[s][i]!! else counter++,
                    lat = lat,
                    lon = lon,
                    alt = pt.children("ele").firstOrNull()?.textContent?.trim()?.toDoubleOrNull(),
                    sensor = GPX_SENSOR,
                )
            }.let { if (hasTimes) it.sortedBy { p -> p.time } else it }
        }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) throw GpxException("Aucune position valable dans ce fichier GPX.")
        return GpxTrack(name?.takeIf { it.isNotEmpty() }, if (hasTimes) segments.sortedBy { it.first().time } else segments, hasTimes)
    }

    /** Heure ISO 8601 (« 2026-11-17T09:00:00Z », avec fraction ou décalage) ; sans fuseau, c'est l'heure UTC. */
    internal fun parseTime(text: String): Long? {
        val t = text.trim()
        return runCatching { OffsetDateTime.parse(t).toEpochSecond() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(t).toEpochSecond(ZoneOffset.UTC) }.getOrNull()
    }

    private fun Element.children(name: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { (it.localName ?: it.nodeName.substringAfter(':')) == name }
    }
}
