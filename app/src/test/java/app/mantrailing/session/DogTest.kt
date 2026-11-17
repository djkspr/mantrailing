package app.mantrailing.session

import app.mantrailing.tractive.Pet
import app.mantrailing.weenect.WeenectTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DogTest {

    @Test
    fun `les animaux Tractive deviennent des chiens`() {
        val dogs = mergeTractivePets(emptyList(), listOf(Pet("p1", "Rex", "TRK1"), Pet("p2", "Mirza", null)), emptySet())
        assertEquals(listOf(Dog("tractive-p1", "Rex", "p1", "TRK1"), Dog("tractive-p2", "Mirza", "p2", null)), dogs)
        assertTrue(dogs[0].hasCollar)
    }

    @Test
    fun `un chien deja lie garde son nom et suit son collier`() {
        val dogs = listOf(Dog("d1", "Rex le magnifique", "p1", "OLD"))
        val merged = mergeTractivePets(dogs, listOf(Pet("p1", "Rex", "TRK2")), emptySet())
        assertEquals(listOf(Dog("d1", "Rex le magnifique", "p1", "TRK2")), merged)
    }

    @Test
    fun `un chien ajoute a la main est lie a l'animal Tractive du meme nom`() {
        val dogs = listOf(Dog("d1", "rex "), Dog("d2", "Nala"))
        val merged = mergeTractivePets(dogs, listOf(Pet("p1", "Rex", "TRK1")), emptySet())
        assertEquals(listOf(Dog("d1", "rex ", "p1", "TRK1"), Dog("d2", "Nala")), merged)
    }

    @Test
    fun `un animal retire de l'appli n'est pas reimporte`() {
        val merged = mergeTractivePets(emptyList(), listOf(Pet("cat", "Minou", "TRK9"), Pet("p1", "Rex", "TRK1")), setOf("cat"))
        assertEquals(listOf("Rex"), merged.map { it.name })
    }

    @Test
    fun `seuls les chiens ajoutes ou modifies prennent une date de modification`() {
        val before = listOf(Dog("d1", "Rex", updatedAt = 10), Dog("d2", "Nala", updatedAt = 10))
        val after = listOf(Dog("d1", "Rex", updatedAt = 10), Dog("d2", "Nala la belle", updatedAt = 10), Dog("d3", "Gaïa"))
        assertEquals(listOf(10L, 99L, 99L), stampChangedDogs(before, after, now = 99).map { it.updatedAt })
    }

    @Test
    fun `liste des chiens enregistree puis relue`() {
        val dogs = listOf(Dog("d1", "Rex", "p1", "TRK1"), Dog("d2", "Nala"))
        assertEquals(dogs, parseDogs(formatDogs(dogs)))
        assertEquals(emptyList<Dog>(), parseDogs(null))
        assertEquals(emptyList<Dog>(), parseDogs("pas du json"))
    }

    @Test
    fun `recherches d'un chien, anciennes comprises`() {
        fun session(id: String, dogName: String, dogId: String?) =
            TrailSession(id, dogName, TrackSource.PHONE, 0, 60, dogId = dogId)
        val rex = Dog("d1", "Rex")
        val sessions = listOf(
            session("a", "Rex", "d1"),
            session("b", "Rex", null), // avant les chiens multiples
            session("c", "Nala", "d2"),
            session("d", "Rex", "d3"), // un autre chien nommé Rex
        )
        assertEquals(listOf("a", "b"), sessionsOf(sessions, rex).map { it.id })
    }

    @Test
    fun `traceurs Weenect - nouveau chien, chien du meme nom lie, traceur retire ignore`() {
        val rex = Dog("d1", "Rex")
        val gaia = Dog("tractive-p2", "Gaïa", "p2", "TRK2")
        val trackers = listOf(WeenectTracker("100", "rex"), WeenectTracker("200", "Nala"), WeenectTracker("300", "Chat"), WeenectTracker("400", "Gaïa"))

        val merged = mergeWeenectTrackers(listOf(rex, gaia), trackers, setOf(weenectIgnoreKey("300")))

        assertEquals(Dog("d1", "Rex", weenectTrackerId = "100"), merged[0])
        // Un chien Tractive de même nom reçoit aussi son traceur Weenect (il en change, ou porte les deux).
        assertEquals("400", merged[1].weenectTrackerId)
        assertEquals(Dog(weenectDogId("200"), "Nala", weenectTrackerId = "200"), merged[2])
        assertEquals(3, merged.size)
        // Relancé : rien ne change.
        assertEquals(merged, mergeWeenectTrackers(merged, trackers, setOf(weenectIgnoreKey("300"))))
    }

    @Test
    fun `marque du collier`() {
        assertEquals("Tractive", Dog("a", "A", "p", "T").collarBrand)
        assertEquals("Weenect", Dog("b", "B", weenectTrackerId = "1").collarBrand)
        assertEquals(null, Dog("c", "C").collarBrand)
        assertTrue(Dog("b", "B", weenectTrackerId = "1").hasCollar)
    }
}
