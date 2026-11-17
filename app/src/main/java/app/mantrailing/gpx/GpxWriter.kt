package app.mantrailing.gpx

import app.mantrailing.tractive.Position
import java.io.Writer
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Trace écrite dans un fichier GPX (élément trk), avec ses segments. */
data class GpxOutTrack(val name: String, val segments: List<List<Position>>, val description: String? = null)

/** Point nommé (note, signalement du traceur) écrit comme waypoint. */
data class GpxWaypoint(val lat: Double, val lon: Double, val time: Long, val name: String, val description: String? = null)

/**
 * Écrit un fichier GPX 1.1 lisible par Garmin, Komoot, Strava, Google Earth… Les traces sont écrites dans l'ordre
 * donné : la première est celle que Mantrailing reprend à l'import (la trace du chien pour une recherche).
 */
fun writeGpx(out: Writer, name: String, tracks: List<GpxOutTrack>, waypoints: List<GpxWaypoint> = emptyList()) {
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    fun time(t: Long) = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(t))
    fun coord(v: Double) = String.format(Locale.ROOT, "%.7f", v)

    out.write("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n")
    out.write("""<gpx version="1.1" creator="Mantrailing" xmlns="http://www.topografix.com/GPX/1/1">""" + "\n")
    out.write("  <metadata><name>${esc(name)}</name></metadata>\n")
    for (w in waypoints) {
        out.write("  <wpt lat=\"${coord(w.lat)}\" lon=\"${coord(w.lon)}\"><time>${time(w.time)}</time><name>${esc(w.name)}</name>")
        w.description?.let { out.write("<desc>${esc(it)}</desc>") }
        out.write("</wpt>\n")
    }
    for (track in tracks.filter { t -> t.segments.any { it.isNotEmpty() } }) {
        out.write("  <trk><name>${esc(track.name)}</name>")
        track.description?.let { out.write("<desc>${esc(it)}</desc>") }
        out.write("\n")
        for (segment in track.segments.filter { it.isNotEmpty() }) {
            out.write("    <trkseg>\n")
            for (p in segment) {
                out.write("      <trkpt lat=\"${coord(p.lat)}\" lon=\"${coord(p.lon)}\">")
                p.alt?.let { out.write("<ele>${String.format(Locale.ROOT, "%.1f", it)}</ele>") }
                out.write("<time>${time(p.time)}</time></trkpt>\n")
            }
            out.write("    </trkseg>\n")
        }
        out.write("  </trk>\n")
    }
    out.write("</gpx>\n")
}
