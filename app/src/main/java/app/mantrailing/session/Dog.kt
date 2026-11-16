package app.mantrailing.session

import app.mantrailing.tractive.Pet
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Un chien de l'équipe. [tractivePetId] et [trackerId] : animal et collier du compte Tractive, s'il en porte un.
 * Sans collier, sa trace est prise au GPS du téléphone.
 */
@Serializable
data class Dog(
    val id: String,
    val name: String,
    val tractivePetId: String? = null,
    val trackerId: String? = null,
) {
    val hasCollar: Boolean get() = trackerId != null
}

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun formatDogs(dogs: List<Dog>): String = json.encodeToString(ListSerializer(Dog.serializer()), dogs)

/** Lecture tolérante : une valeur illisible donne une liste vide plutôt qu'un plantage. */
fun parseDogs(value: String?): List<Dog> =
    if (value.isNullOrBlank()) emptyList()
    else runCatching { json.decodeFromString(ListSerializer(Dog.serializer()), value) }.getOrDefault(emptyList())

/**
 * Ajoute les animaux du compte Tractive à la liste des chiens.
 * - Chien déjà lié : son collier est mis à jour, son nom (peut-être changé dans l'appli) est gardé.
 * - Chien sans lien portant le même nom : il est lié à l'animal Tractive (ses recherches restent les siennes).
 * - Sinon, nouveau chien, sauf si l'utilisateur l'a retiré de l'appli ([ignoredPetIds] : le chat de la maison…).
 */
fun mergeTractivePets(dogs: List<Dog>, pets: List<Pet>, ignoredPetIds: Set<String>, newId: () -> String): List<Dog> {
    var result = dogs
    for (pet in pets) {
        val linked = result.find { it.tractivePetId == pet.id }
        val sameName = result.find { it.tractivePetId == null && it.name.trim().equals(pet.name.trim(), ignoreCase = true) }
        result = when {
            linked != null -> result.map { if (it.id == linked.id) it.copy(trackerId = pet.trackerId) else it }
            sameName != null -> result.map { if (it.id == sameName.id) it.copy(tractivePetId = pet.id, trackerId = pet.trackerId) else it }
            pet.id in ignoredPetIds -> result
            else -> result + Dog(newId(), pet.name, pet.id, pet.trackerId)
        }
    }
    return result
}

/** Recherches de [dog] ; les plus anciennes, d'avant les chiens multiples, sont reconnues à leur nom. */
fun sessionsOf(sessions: List<TrailSession>, dog: Dog): List<TrailSession> =
    sessions.filter { it.dogId == dog.id || (it.dogId == null && it.dogName == dog.name) }
