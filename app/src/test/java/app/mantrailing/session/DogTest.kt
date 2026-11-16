package app.mantrailing.session

import app.mantrailing.tractive.Pet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DogTest {
    private var next = 0
    private fun newId() = "new-${++next}"

    @Test
    fun `les animaux Tractive deviennent des chiens`() {
        val dogs = mergeTractivePets(emptyList(), listOf(Pet("p1", "Rex", "TRK1"), Pet("p2", "Mirza", null)), emptySet(), ::newId)
        assertEquals(listOf(Dog("new-1", "Rex", "p1", "TRK1"), Dog("new-2", "Mirza", "p2", null)), dogs)
        assertTrue(dogs[0].hasCollar)
    }

    @Test
    fun `un chien deja lie garde son nom et suit son collier`() {
        val dogs = listOf(Dog("d1", "Rex le magnifique", "p1", "OLD"))
        val merged = mergeTractivePets(dogs, listOf(Pet("p1", "Rex", "TRK2")), emptySet(), ::newId)
        assertEquals(listOf(Dog("d1", "Rex le magnifique", "p1", "TRK2")), merged)
    }

    @Test
    fun `un chien ajoute a la main est lie a l'animal Tractive du meme nom`() {
        val dogs = listOf(Dog("d1", "rex "), Dog("d2", "Nala"))
        val merged = mergeTractivePets(dogs, listOf(Pet("p1", "Rex", "TRK1")), emptySet(), ::newId)
        assertEquals(listOf(Dog("d1", "rex ", "p1", "TRK1"), Dog("d2", "Nala")), merged)
    }

    @Test
    fun `un animal retire de l'appli n'est pas reimporte`() {
        val merged = mergeTractivePets(emptyList(), listOf(Pet("cat", "Minou", "TRK9"), Pet("p1", "Rex", "TRK1")), setOf("cat"), ::newId)
        assertEquals(listOf("Rex"), merged.map { it.name })
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
}
