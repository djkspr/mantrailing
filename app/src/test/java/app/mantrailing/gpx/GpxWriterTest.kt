package app.mantrailing.gpx

import app.mantrailing.tractive.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class GpxWriterTest {
    private val start = 1_794_906_000L
    private val dog = listOf(
        listOf(Position(start, 48.4254, 2.6402, alt = 81.0), Position(start + 5, 48.4255, 2.6403)),
        listOf(Position(start + 60, 48.4260, 2.6410)),
    )
    private val trail = listOf(Position(start - 1_800, 48.4254, 2.6402), Position(start - 1_700, 48.4261, 2.6411))

    private fun write(): String = StringWriter().also {
        writeGpx(
            it, "Recherche de Rex & Nala",
            listOf(GpxOutTrack("Trace de Rex", dog, "GPS du téléphone"), GpxOutTrack("Piste du traceur", listOf(trail))),
            listOf(GpxWaypoint(48.4255, 2.6403, start + 5, "Danger — chien <méchant>", "Le traceur")),
        )
    }.toString()

    @Test
    fun `fichier GPX lisible - trace du chien relue en premier, piste ensuite`() {
        val xml = write()
        val track = Gpx.parse(xml.byteInputStream())

        // Réimporté dans Mantrailing : la trace du chien, segments et heures gardés (pas la piste écrite après).
        assertEquals("Trace de Rex", track.name)
        assertEquals(2, track.segments.size)
        assertEquals(listOf(start, start + 5, start + 60), track.points.map { it.time })
        assertEquals(81.0, track.points[0].alt!!, 0.0)
        assertTrue(xml.contains("<name>Piste du traceur</name>"))
    }

    @Test
    fun `caracteres speciaux echappes, notes en points nommes`() {
        val xml = write()
        assertTrue(xml.contains("<metadata><name>Recherche de Rex &amp; Nala</name></metadata>"))
        assertTrue(xml.contains("<wpt lat=\"48.4255000\" lon=\"2.6403000\"><time>2026-11-17T09:00:05Z</time><name>Danger — chien &lt;méchant&gt;</name><desc>Le traceur</desc></wpt>"))
    }

    @Test
    fun `trace vide pas ecrite`() {
        val xml = StringWriter().also { writeGpx(it, "Piste", listOf(GpxOutTrack("Vide", listOf(emptyList())), GpxOutTrack("Piste", listOf(trail)))) }.toString()
        assertEquals("Piste", Gpx.parse(xml.byteInputStream()).name)
        assertTrue(!xml.contains("<name>Vide</name>"))
    }
}
