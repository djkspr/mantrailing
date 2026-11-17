package app.mantrailing.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxTest {
    private fun parse(xml: String) = Gpx.parse(xml.trimIndent().byteInputStream())

    @Test
    fun `trace Garmin - segments, heures, altitude, nom`() {
        val track = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="Garmin Alpha" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><name>Rex 17/11</name>
                <trkseg>
                  <trkpt lat="48.4254" lon="2.6402"><ele>81.5</ele><time>2026-11-17T09:00:00Z</time></trkpt>
                  <trkpt lat="48.4255" lon="2.6403"><ele>82</ele><time>2026-11-17T09:00:05.250Z</time></trkpt>
                </trkseg>
                <trkseg>
                  <trkpt lat="48.4260" lon="2.6410"><time>2026-11-17T10:01:00+01:00</time></trkpt>
                </trkseg>
              </trk>
            </gpx>
            """,
        )
        assertEquals("Rex 17/11", track.name)
        assertTrue(track.hasTimes)
        assertEquals(2, track.segments.size)
        assertEquals(1_794_906_000L, track.startedAt)
        assertEquals(1_794_906_060L, track.endedAt) // 10:01+01:00 = 09:01 UTC
        assertEquals(1_794_906_005L, track.points[1].time)
        assertEquals(81.5, track.points[0].alt!!, 0.0)
        assertEquals(GPX_SENSOR, track.points[0].sensor)
    }

    @Test
    fun `itineraire sans heures - heure fictive puis heure de depart choisie`() {
        val track = parse(
            """
            <gpx version="1.0"><rte><name>Piste du parc</name>
              <rtept lat="48.0" lon="2.0"/><rtept lat="48.001" lon="2.0"/><rtept lat="48.002" lon="2.0"/>
            </rte></gpx>
            """,
        )
        assertFalse(track.hasTimes)
        assertNull(track.startedAt)
        assertEquals("Piste du parc", track.name)
        val dated = track.startingAt(1_000)
        assertEquals(listOf(1_000L, 1_001L, 1_002L), dated.points.map { it.time })
        assertEquals(1_000L, dated.startedAt)
    }

    @Test
    fun `points invalides ignores, heures dans le desordre remises en ordre`() {
        val track = parse(
            """
            <gpx><trk><trkseg>
              <trkpt lat="48.0" lon="2.0"><time>2026-11-17T09:00:10Z</time></trkpt>
              <trkpt lat="abc" lon="2.0"><time>2026-11-17T09:00:20Z</time></trkpt>
              <trkpt lat="48.1" lon="2.0"><time>2026-11-17T09:00:00Z</time></trkpt>
            </trkseg></trk></gpx>
            """,
        )
        assertEquals(listOf(1_794_906_000L, 1_794_906_010L), track.points.map { it.time })
    }

    @Test
    fun `fichiers refuses avec un message clair`() {
        assertEquals("Ce fichier n'est pas un GPX lisible.", assertThrows(GpxException::class.java) { parse("pas du xml") }.message)
        assertEquals("Ce fichier n'est pas un GPX.", assertThrows(GpxException::class.java) { parse("<kml></kml>") }.message)
        assertEquals("Aucune trace dans ce fichier GPX.", assertThrows(GpxException::class.java) { parse("<gpx><wpt lat=\"1\" lon=\"2\"/></gpx>") }.message)
    }

    @Test
    fun `entite externe refusee`() {
        val evil = """
            <?xml version="1.0"?>
            <!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/hosts">]>
            <gpx><trk><name>&x;</name><trkseg><trkpt lat="48" lon="2"/></trkseg></trk></gpx>
        """
        val result = runCatching { parse(evil) }
        // Refusé, ou lu sans jamais insérer le contenu du fichier visé.
        assertTrue(result.isFailure || result.getOrNull()?.name.isNullOrEmpty())
    }

    @Test
    fun `heures ISO variees`() {
        assertEquals(1_794_906_000L, Gpx.parseTime("2026-11-17T09:00:00Z"))
        assertEquals(1_794_906_000L, Gpx.parseTime(" 2026-11-17T09:00:00.000+00:00 "))
        assertEquals(1_794_906_000L, Gpx.parseTime("2026-11-17T09:00:00"))
        assertNull(Gpx.parseTime("hier"))
    }
}
