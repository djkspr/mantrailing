package app.mantrailing.track

import app.mantrailing.tractive.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CorridorTest {
    // Piste droite vers le nord, ≈ 222 m (0,002° de latitude) ; 0,0001° de longitude ≈ 7,4 m à 48°.
    private val trail = (0..20).map { Position(it * 10L, 48.0 + it * 0.0001, 2.0) }
    private fun at(i: Int, eastSteps: Double = 0.0) = Position(1_000L + i, 48.0 + i.coerceAtMost(20) * 0.0001, 2.0 + eastSteps * 0.0001)

    @Test
    fun `distance a la piste`() {
        val corridor = Corridor(trail, 20.0)
        assertEquals(0.0, corridor.distanceTo(at(5)), 0.1)
        assertEquals(22.3, corridor.distanceTo(at(5, 3.0)), 0.5)
    }

    @Test
    fun `sortie confirmee apres trois positions dehors, saut isole ignore`() {
        val corridor = Corridor(trail, 20.0)
        assertNull(corridor.update(at(1)).event)
        // Un saut GPS isolé hors du couloir : pas d'alerte.
        assertFalse(corridor.update(at(2, 4.0)).outside)
        assertFalse(corridor.update(at(3)).outside)
        // Trois positions de suite à ≈ 30 m : sortie.
        assertNull(corridor.update(at(4, 4.0)).event)
        assertNull(corridor.update(at(5, 4.0)).event)
        val left = corridor.update(at(6, 4.0))
        assertEquals(CorridorEvent.LEFT, left.event)
        assertTrue(left.outside)
        assertEquals(1, left.exits)
        assertEquals(29.7, left.distanceMeters, 0.5)
        // Toujours dehors : pas de nouvelle alerte.
        assertNull(corridor.update(at(7, 4.0)).event)
    }

    @Test
    fun `retour seulement bien a l'interieur, et sorties comptees`() {
        val corridor = Corridor(trail, 20.0)
        repeat(3) { corridor.update(at(it, 4.0)) }
        // À 18 m : dans le couloir de 20 m, mais pas assez loin du bord (marge de 5 m) : toujours dehors.
        repeat(3) { assertTrue(corridor.update(at(3 + it, 2.4)).outside) }
        assertNull(corridor.update(at(6, 1.0)).event)
        val back = corridor.update(at(7, 1.0))
        assertEquals(CorridorEvent.BACK, back.event)
        assertFalse(back.outside)
        repeat(3) { corridor.update(at(8 + it, -4.0)) }
        assertEquals(2, corridor.update(at(11, -4.0)).exits)
    }

    @Test
    fun `arrivee proche annoncee une fois, pas au depart d'une boucle`() {
        val loop = trail + trail.reversed().drop(1).map { it.copy(lon = it.lon + 0.00005) }
        val corridor = Corridor(loop, 20.0)
        // Au départ, l'arrivée (au même endroit) est toute proche, mais rien n'a encore été parcouru.
        assertFalse(corridor.update(at(0)).nearEnd)
        val events = (1..20).map { corridor.update(at(it)) }.mapNotNull { it.event } +
            (19 downTo 0).map { corridor.update(at(it)) }.mapNotNull { it.event }
        assertEquals(listOf(CorridorEvent.NEAR_END), events)
    }

    @Test
    fun `piste trop courte refusee`() {
        assertThrows(IllegalArgumentException::class.java) { Corridor(trail.take(1), 20.0) }
    }

    @Test
    fun `choix de largeur`() {
        assertEquals(listOf(10, 20, 30, 50), CORRIDOR_CHOICES)
        assertTrue(DEFAULT_CORRIDOR_METERS in CORRIDOR_CHOICES)
    }
}
