package app.mantrailing.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAreaTest {
    // Forêt de Fontainebleau, zone de ~2 km de côté.
    private val forest = Area(48.415, 2.625, 48.433, 2.652)

    @Test
    fun `zone visible raisonnable telechargee telle quelle`() {
        assertEquals(2.0, forest.heightKm, 0.05)
        assertSame(forest, downloadArea(forest))
    }

    @Test
    fun `carte trop dezoomee - 3 km autour du centre`() {
        val france = Area(42.0, -5.0, 51.0, 8.0)
        val area = downloadArea(france)
        assertEquals(6.0, area.widthKm, 0.05)
        assertEquals(6.0, area.heightKm, 0.05)
        assertEquals(france.centerLat, area.centerLat, 1e-9)
        assertEquals(france.centerLon, area.centerLon, 1e-9)
    }

    @Test
    fun `estimation des tuiles - un niveau, puis la pyramide 12 a 17`() {
        // Une tuile exacte au niveau 12 : comptée une fois.
        val z = 12
        val oneTile = Area(tileTopLat(1411, z) - 0.002, 2.64, tileTopLat(1411, z) - 0.001, 2.64)
        assertEquals(1L, estimateTiles(oneTile, 12, 12))
        // Zone de 2 km : quelques centaines de tuiles en tout, bien sous la limite.
        val tiles = estimateTiles(forest)
        assertTrue(tiles in 100..1_000)
        // Zone de 6 km (le maximum autour du centre) : toujours sous la limite.
        assertTrue(estimateTiles(Area.around(48.42, 2.64, OFFLINE_AROUND_KM)) < OFFLINE_MAX_TILES)
        // La plus grande zone visible acceptée aussi.
        assertTrue(estimateTiles(Area.around(48.42, 2.64, OFFLINE_MAX_SIDE_KM / 2)) < OFFLINE_MAX_TILES)
    }

    @Test
    fun `tailles lisibles`() {
        assertEquals("340 Ko", formatBytes(340_000))
        assertEquals("85 Mo", formatBytes(85_000_000))
        assertEquals("1,2 Go", formatBytes(1_200_000_000))
    }

    @Test
    fun `resume des zones`() {
        assertEquals("Aucune zone : la carte demande du réseau", offlineSummary(emptyList()))
        val zones = listOf(OfflineZone(1, "A", 0, 50_000_000, true, null), OfflineZone(2, "B", 0, 35_000_000, true, null))
        assertEquals("2 zones · 85 Mo", offlineSummary(zones))
    }
}
