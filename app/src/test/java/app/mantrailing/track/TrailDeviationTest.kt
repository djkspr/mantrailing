package app.mantrailing.track

import app.mantrailing.demo.DemoSession
import app.mantrailing.tractive.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailDeviationTest {
    // Mètres → degrés autour de 48° N (latitude des tests).
    private val degLat = 1 / 111_195.0
    private val degLon = 1 / (111_195.0 * kotlin.math.cos(Math.toRadians(48.0)))

    /** Point à [north] m au nord et [east] m à l'est de l'origine (48° N, 2° E). */
    private fun at(east: Double, north: Double, t: Long = 0) = Position(t, 48.0 + north * degLat, 2.0 + east * degLon)

    /** Piste rectiligne de 200 m vers l'est, un point tous les 10 m. */
    private val straightTrail = (0..20).map { at(it * 10.0, 0.0) }

    @Test
    fun `chien exactement sur la piste`() {
        val d = TrailDeviation.of(straightTrail, straightTrail)!!
        assertEquals(0.0, d.meanMeters, 0.01)
        assertEquals(1.0, d.onTrailShare, 0.0)
        assertEquals(1.0, d.coverage, 0.0)
        assertTrue(d.reachedEnd)
    }

    @Test
    fun `chien parallèle à 10 m - écart de 10 m, toujours sur la piste`() {
        val dog = (0..20).map { at(it * 10.0, 10.0) }
        val d = TrailDeviation.of(straightTrail, dog)!!
        assertEquals(10.0, d.meanMeters, 0.1)
        assertEquals(10.0, d.maxMeters, 0.1)
        assertEquals(1.0, d.onTrailShare, 0.0)
        assertTrue(d.reachedEnd)
    }

    @Test
    fun `l'écart se mesure au segment, pas seulement aux points de la piste`() {
        // Piste de deux points seulement, à 200 m l'un de l'autre : le chien au milieu est à 0 m.
        val d = TrailDeviation.of(listOf(at(0.0, 0.0), at(200.0, 0.0)), listOf(at(100.0, 0.0)))!!
        assertEquals(0.0, d.meanMeters, 0.01)
    }

    @Test
    fun `écart au départ de la piste - distance jusqu'au premier point`() {
        val d = TrailDeviation.of(straightTrail, listOf(at(-30.0, 40.0)))!!
        assertEquals(50.0, d.maxMeters, 0.2) // 3-4-5
    }

    @Test
    fun `chien qui abandonne à mi-chemin - arrivée pas atteinte, moitié de la piste couverte`() {
        val dog = (0..10).map { at(it * 10.0, 2.0) }
        val d = TrailDeviation.of(straightTrail, dog)!!
        assertFalse(d.reachedEnd)
        assertEquals(12.0 / 21, d.coverage, 0.01) // points de 0 à 110 m à moins de 15 m
    }

    @Test
    fun `chien parti à côté - une partie hors piste`() {
        val dog = listOf(at(0.0, 0.0), at(50.0, 0.0), at(100.0, 60.0), at(150.0, 0.0), at(200.0, 0.0))
        val d = TrailDeviation.of(straightTrail, dog)!!
        assertEquals(60.0, d.maxMeters, 0.2)
        assertEquals(0.8, d.onTrailShare, 0.0)
        assertEquals(12.0, d.meanMeters, 0.1)
    }

    @Test
    fun `sans piste ou sans trace - pas de comparaison`() {
        assertNull(TrailDeviation.of(emptyList(), straightTrail))
        assertNull(TrailDeviation.of(listOf(at(0.0, 0.0)), straightTrail))
        assertNull(TrailDeviation.of(straightTrail, emptyList()))
    }

    @Test
    fun `séance de démonstration - le chien arrive, après avoir dépassé et coupé un virage`() {
        val d = TrailDeviation.of(DemoSession.trail, DemoSession.segments.flatten())!!
        assertTrue(d.reachedEnd)
        assertTrue("couverture ${d.coverage}", d.coverage in 0.75..0.95) // le virage coupé n'est pas couvert
        assertTrue("écart max ${d.maxMeters}", d.maxMeters > 30) // le dépassement du virage
        assertTrue("écart moyen ${d.meanMeters}", d.meanMeters < 15)
    }
}
