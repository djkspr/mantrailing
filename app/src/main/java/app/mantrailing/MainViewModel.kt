package app.mantrailing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mantrailing.data.SessionStore
import app.mantrailing.data.StoredSession
import app.mantrailing.track.TrackStats
import app.mantrailing.tractive.Pet
import app.mantrailing.tractive.Position
import app.mantrailing.tractive.TractiveApi
import app.mantrailing.tractive.TractiveAuthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Période à interroger, en heure locale (une sortie tient dans une journée). */
data class TimeRange(val date: LocalDate, val start: LocalTime, val end: LocalTime) {
    fun from(zone: ZoneId = ZoneId.systemDefault()) = date.atTime(start).atZone(zone).toInstant()
    fun to(zone: ZoneId = ZoneId.systemDefault()) = date.atTime(end).atZone(zone).toInstant()
    val isValid get() = start < end

    companion object {
        /** Les [hours] dernières heures, sans remonter avant minuit. */
        fun lastHours(hours: Long, now: LocalDateTime = LocalDateTime.now()): TimeRange {
            val start = now.minusHours(hours)
            val startTime = if (start.toLocalDate() == now.toLocalDate()) start.toLocalTime() else LocalTime.MIDNIGHT
            // Fin arrondie à la minute suivante pour inclure les dernières positions (sans passer minuit).
            val end = now.toLocalTime().truncatedTo(ChronoUnit.MINUTES).let { if (it.hour == 23 && it.minute == 59) it else it.plusMinutes(1) }
            return TimeRange(now.toLocalDate(), startTime.truncatedTo(ChronoUnit.MINUTES), end)
        }
    }
}

data class UiState(
    val ready: Boolean = false,
    val stored: StoredSession? = null,
    val pets: List<Pet> = emptyList(),
    val range: TimeRange = TimeRange.lastHours(3),
    val busy: Boolean = false,
    val message: String? = null,
    val segments: List<List<Position>> = emptyList(),
    val stats: TrackStats? = null,
    val loadedOnce: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SessionStore(app)
    private val api = TractiveApi()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = store.session.first()
            _state.update { it.copy(ready = true, stored = stored) }
            if (stored != null && stored.pet == null) loadPets()
        }
    }

    fun login(email: String, password: String) = run {
        val session = api.login(email.trim(), password)
        store.saveSession(session)
        _state.update { it.copy(stored = StoredSession(session, null)) }
        fetchPets()
    }

    fun loadPets() = run { fetchPets() }

    private suspend fun fetchPets() {
        val session = _state.value.stored?.session ?: return
        val pets = api.pets(session)
        _state.update { it.copy(pets = pets) }
        // Un seul chien : on le choisit d'office.
        pets.singleOrNull()?.let { selectPetNow(it) }
    }

    fun selectPet(pet: Pet) = run { selectPetNow(pet) }

    private suspend fun selectPetNow(pet: Pet) {
        store.savePet(pet)
        _state.update { s -> s.copy(stored = s.stored?.copy(pet = pet)) }
    }

    fun changePet() {
        _state.update { s -> s.copy(stored = s.stored?.copy(pet = null), segments = emptyList(), stats = null, loadedOnce = false) }
        loadPets()
    }

    fun setRange(range: TimeRange) = _state.update { it.copy(range = range) }

    fun loadTrack() = run {
        val s = _state.value
        val session = s.stored?.session ?: return@run
        val tracker = s.stored.pet?.trackerId ?: error("Ce chien n'a pas de collier associé.")
        require(s.range.isValid) { "L'heure de fin doit être après l'heure de début." }
        val segments = api.positions(session, tracker, s.range.from(), s.range.to())
        _state.update {
            it.copy(
                segments = segments,
                stats = TrackStats.of(segments),
                loadedOnce = true,
                message = if (segments.isEmpty()) "Aucune position sur cette période." else null,
            )
        }
    }

    fun setLive(active: Boolean) = run {
        val s = _state.value
        val session = s.stored?.session ?: return@run
        val tracker = s.stored.pet?.trackerId ?: error("Ce chien n'a pas de collier associé.")
        api.setLiveTracking(session, tracker, active)
        _state.update {
            it.copy(message = if (active) "Mode LIVE demandé : il peut mettre une minute à démarrer sur le collier." else "Mode LIVE arrêté.")
        }
    }

    fun logout() {
        viewModelScope.launch {
            store.clear()
            _state.update { UiState(ready = true) }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Lance une action réseau : indicateur d'attente, message d'erreur, déconnexion si le jeton est refusé. */
    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TractiveAuthException) {
                val wasLoggedIn = _state.value.stored != null
                store.clear()
                _state.update {
                    UiState(ready = true, message = if (wasLoggedIn) "Session Tractive expirée, reconnecte-toi." else "Email ou mot de passe incorrect.")
                }
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
