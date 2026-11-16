package app.mantrailing.demo

import app.mantrailing.track.TrackStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class DemoSessionTest {
    @Test
    fun `séance de démonstration réaliste, en mode LIVE`() {
        val stats = TrackStats.of(DemoSession.segments)
        assertEquals(1, stats.segmentCount)
        assertEquals(3L, stats.medianIntervalSeconds)
        assertTrue("distance ${stats.distanceMeters}", stats.distanceMeters in 700.0..1500.0)
        assertEquals(1.0, stats.gpsShare, 0.0)
    }

    @Test
    fun `séance comprise dans la période affichée`() {
        val zone = ZoneId.of("Europe/Paris")
        val points = DemoSession.segments.flatten()
        assertTrue(points.first().time >= DemoSession.range.from(zone).epochSecond)
        assertTrue(points.last().time <= DemoSession.range.to(zone).epochSecond)
    }

    @Test
    fun `séance identique à chaque ouverture`() {
        val first = DemoSession.segments.flatten().first()
        assertEquals(48.4254, first.lat, 0.0001)
        assertEquals(2.6402, first.lon, 0.0001)
    }

    @Test
    fun `compte de démonstration reconnu, insensible à la casse et aux espaces de l'email`() {
        assertTrue(DemoSession.isDemoAccount(DemoSession.EMAIL, DemoSession.PASSWORD))
        assertTrue(DemoSession.isDemoAccount("  DEMO@Mantrailing.app ", DemoSession.PASSWORD))
        assertFalse(DemoSession.isDemoAccount(DemoSession.EMAIL, "mauvais"))
        assertFalse(DemoSession.isDemoAccount("moi@example.com", DemoSession.PASSWORD))
    }
}
