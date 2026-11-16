package app.mantrailing.track

import app.mantrailing.tractive.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackStatsTest {
    @Test
    fun `distance haversine d'un millième de degré de latitude`() {
        // 0,001° de latitude ≈ 111,2 m partout sur Terre.
        val d = distanceMeters(Position(0, 44.0, 2.0), Position(0, 44.001, 2.0))
        assertEquals(111.2, d, 0.1)
    }

    @Test
    fun `distance nulle entre deux points identiques`() {
        assertEquals(0.0, distanceMeters(Position(0, 44.0, 2.0), Position(5, 44.0, 2.0)), 1e-9)
    }

    @Test
    fun `bilan d'une trace en deux segments`() {
        val seg1 = listOf(
            Position(0, 44.000, 2.0, uncertainty = 3.0, sensor = "GPS"),
            Position(3, 44.001, 2.0, uncertainty = 5.0, sensor = "GPS"),
            Position(6, 44.002, 2.0, uncertainty = 4.0, sensor = "GPS"),
        )
        val seg2 = listOf(Position(600, 45.0, 2.0, uncertainty = 30.0, sensor = "KNOWN_WIFI"))

        val s = TrackStats.of(listOf(seg1, seg2))

        assertEquals(4, s.pointCount)
        assertEquals(2, s.segmentCount)
        // Le saut entre segments (trou de réception) ne compte pas dans la distance.
        assertEquals(222.4, s.distanceMeters, 0.5)
        assertEquals(600, s.durationSeconds)
        assertEquals(3L, s.medianIntervalSeconds)
        assertEquals(594L, s.maxIntervalSeconds)
        assertEquals(5.0, s.medianUncertaintyMeters!!, 1e-9)
        assertEquals(0.75, s.gpsShare, 1e-9)
    }

    @Test
    fun `bilan d'une trace vide`() {
        val s = TrackStats.of(emptyList())
        assertEquals(0, s.pointCount)
        assertEquals(0L, s.durationSeconds)
        assertNull(s.medianIntervalSeconds)
        assertNull(s.medianUncertaintyMeters)
        assertEquals(0.0, s.gpsShare, 0.0)
    }
}
