package app.mantrailing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mantrailing.data.SessionStore
import app.mantrailing.data.StoredSession
import app.mantrailing.demo.DemoSession
import app.mantrailing.recording.PhoneRecordingService
import app.mantrailing.session.ActiveRecording
import app.mantrailing.session.SessionRepository
import app.mantrailing.session.TrackSource
import app.mantrailing.session.TrailSession
import app.mantrailing.track.TrackStats
import app.mantrailing.tractive.Pet
import app.mantrailing.tractive.Position
import app.mantrailing.tractive.TractiveApi
import app.mantrailing.tractive.TractiveAuthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

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
    /** Séance fictive affichée, sans compte Tractive. */
    val demo: Boolean = false,
    /** Source choisie pour la trace du chien (null : pas encore choisie). */
    val trackSource: TrackSource? = null,
    /** Piste en cours d'enregistrement. */
    val recording: ActiveRecording? = null,
    /** Positions du téléphone enregistrées pour la piste en cours. */
    val recordingPoints: Int = 0,
    /** Pistes enregistrées, la plus récente d'abord. */
    val sessions: List<TrailSession> = emptyList(),
    /** Piste enregistrée affichée sur la carte. */
    val openedSession: TrailSession? = null,
) {
    val connected get() = stored?.pet != null
    val dogName get() = stored?.pet?.name ?: "Mon chien"
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SessionStore(app)
    private val api = TractiveApi()
    private val repository = SessionRepository(File(app.filesDir, "sessions"))
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = store.session.first()
            val recording = store.activeRecording.first()
            val sessions = withContext(Dispatchers.IO) { repository.list() }
            _state.update {
                it.copy(ready = true, stored = stored, trackSource = store.trackSource.first(), recording = recording, sessions = sessions)
            }
            if (stored != null && stored.pet == null) loadPets()
            // Appli relancée pendant une piste au GPS du téléphone : on reprend l'enregistrement.
            if (recording?.source == TrackSource.PHONE && !PhoneRecordingService.running.value &&
                PhoneRecordingService.hasLocationPermission(getApplication())
            ) {
                PhoneRecordingService.start(getApplication())
            }
        }
        viewModelScope.launch {
            PhoneRecordingService.pointCount.collect { n -> _state.update { it.copy(recordingPoints = n) } }
        }
    }

    fun login(email: String, password: String) = run {
        if (DemoSession.isDemoAccount(email, password)) {
            startDemo()
            _state.update { it.copy(message = "Compte de démonstration : séance fictive affichée sur l'accueil.") }
            return@run
        }
        val session = api.login(email.trim(), password)
        store.saveSession(session)
        _state.update { it.copy(stored = StoredSession(session, null), demo = false, segments = emptyList(), stats = null) }
        fetchPets()
    }

    fun startDemo() = _state.update {
        it.copy(
            demo = true,
            openedSession = null,
            range = DemoSession.range,
            segments = DemoSession.segments,
            stats = TrackStats.of(DemoSession.segments),
            loadedOnce = true,
            message = null,
        )
    }

    fun exitDemo() = _state.update {
        it.copy(demo = false, range = TimeRange.lastHours(3), segments = emptyList(), stats = null, loadedOnce = false, message = null)
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
        _state.update { s -> s.copy(stored = s.stored?.copy(pet = pet), demo = false) }
    }

    fun changePet() {
        _state.update { s -> s.copy(stored = s.stored?.copy(pet = null), segments = emptyList(), stats = null, loadedOnce = false) }
        loadPets()
    }

    fun setRange(range: TimeRange) = _state.update { it.copy(range = range) }

    fun loadTrack() = run {
        val s = _state.value
        if (s.demo) return@run
        val session = s.stored?.session ?: return@run
        val tracker = s.stored.pet?.trackerId ?: error("Ce chien n'a pas de collier associé.")
        require(s.range.isValid) { "L'heure de fin doit être après l'heure de début." }
        val segments = api.positions(session, tracker, s.range.from(), s.range.to())
        _state.update {
            it.copy(
                openedSession = null,
                segments = segments,
                stats = TrackStats.of(segments),
                loadedOnce = true,
                message = if (segments.isEmpty()) "Aucune position sur cette période." else null,
            )
        }
    }

    fun setLive(active: Boolean) = run {
        val s = _state.value
        if (s.demo) return@run
        val session = s.stored?.session ?: return@run
        val tracker = s.stored.pet?.trackerId ?: error("Ce chien n'a pas de collier associé.")
        api.setLiveTracking(session, tracker, active)
        _state.update {
            it.copy(message = if (active) "Mode LIVE demandé : il peut mettre une minute à démarrer sur le collier." else "Mode LIVE arrêté.")
        }
    }

    // ---------- Enregistrement d'une piste ----------

    fun setTrackSource(source: TrackSource) {
        viewModelScope.launch {
            store.saveTrackSource(source)
            _state.update { it.copy(trackSource = source) }
        }
    }

    /**
     * Démarre une piste. Pour le GPS du téléphone, l'autorisation de localisation doit déjà être accordée.
     * [remember] : enregistre [source] comme choix pour les pistes suivantes.
     */
    fun startRecording(source: TrackSource, remember: Boolean = false) = run {
        if (_state.value.recording != null) return@run
        if (remember) {
            store.saveTrackSource(source)
            _state.update { it.copy(trackSource = source) }
        }
        val s = _state.value
        val recording = ActiveRecording(UUID.randomUUID().toString(), s.dogName, source, Instant.now().epochSecond)
        var message: String? = null
        when (source) {
            TrackSource.PHONE -> {
                withContext(Dispatchers.IO) { PhoneRecordingService.bufferOf(getApplication()).clear() }
                PhoneRecordingService.start(getApplication())
            }
            TrackSource.TRACTIVE -> {
                val session = s.stored?.session ?: error("Connecte d'abord le collier Tractive dans les paramètres.")
                val tracker = s.stored.pet?.trackerId ?: error("Ce chien n'a pas de collier associé.")
                // Sans le mode LIVE, le collier n'envoie qu'un point toutes les quelques minutes.
                message = try {
                    api.setLiveTracking(session, tracker, true)
                    "Piste démarrée, mode LIVE demandé au collier."
                } catch (e: TractiveAuthException) {
                    throw e
                } catch (e: Exception) {
                    "Piste démarrée, mais le mode LIVE n'a pas pu être activé : active-le dans l'appli Tractive."
                }
            }
        }
        store.saveActiveRecording(recording)
        _state.update {
            it.copy(recording = recording, demo = false, openedSession = null, segments = emptyList(), stats = null, message = message)
        }
    }

    /** Termine la piste en cours, récupère la trace du chien et l'enregistre dans « Mes pistes ». */
    fun stopRecording() = run {
        val s = _state.value
        val recording = s.recording ?: return@run
        val endedAt = Instant.now().epochSecond
        var message: String? = null
        val segments: List<List<Position>> = when (recording.source) {
            TrackSource.PHONE -> {
                val points = withContext(Dispatchers.IO) { PhoneRecordingService.stop(getApplication()) }
                if (points.isEmpty()) message = "Aucune position GPS enregistrée : signal trop faible ?"
                listOf(points).filter { it.isNotEmpty() }
            }
            TrackSource.TRACTIVE -> fetchCollarTrack(recording.startedAt, endedAt).also { segs ->
                if (segs.isEmpty()) message = "Trace du collier pas encore disponible : rouvre la piste un peu plus tard depuis « Mes pistes »."
                stopLiveQuietly()
            }
        }
        val session = TrailSession(recording.id, recording.dogName, recording.source, recording.startedAt, endedAt, segments)
        withContext(Dispatchers.IO) { repository.save(session) }
        store.saveActiveRecording(null)
        _state.update {
            it.copy(
                recording = null,
                sessions = listOf(session) + it.sessions.filter { old -> old.id != session.id },
                message = message,
            )
        }
        showSession(session)
    }

    /** Positions du collier pendant la piste (avec une marge d'une minute avant le départ). Vide en cas d'échec réseau. */
    private suspend fun fetchCollarTrack(startedAt: Long, endedAt: Long): List<List<Position>> {
        val s = _state.value
        val session = s.stored?.session ?: return emptyList()
        val tracker = s.stored.pet?.trackerId ?: return emptyList()
        return try {
            api.positions(session, tracker, Instant.ofEpochSecond(startedAt - 60), Instant.ofEpochSecond(endedAt + 60))
        } catch (e: TractiveAuthException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun stopLiveQuietly() {
        val s = _state.value
        val session = s.stored?.session ?: return
        val tracker = s.stored.pet?.trackerId ?: return
        runCatching { api.setLiveTracking(session, tracker, false) }
    }

    // ---------- Mes pistes ----------

    fun openSession(session: TrailSession) = run {
        showSession(session)
        // Piste au collier enregistrée avant que Tractive ait transmis les positions : nouvel essai.
        if (session.source == TrackSource.TRACTIVE && session.segments.isEmpty() && _state.value.connected) {
            val segments = fetchCollarTrack(session.startedAt, session.endedAt)
            if (segments.isEmpty()) {
                _state.update { it.copy(message = "Le collier n'a toujours pas transmis de positions pour cette piste.") }
            } else {
                val updated = session.copy(segments = segments)
                withContext(Dispatchers.IO) { repository.save(updated) }
                _state.update { it.copy(sessions = it.sessions.map { old -> if (old.id == updated.id) updated else old }) }
                showSession(updated)
            }
        }
    }

    private fun showSession(session: TrailSession) = _state.update {
        it.copy(openedSession = session, demo = false, segments = session.segments, stats = TrackStats.of(session.segments), loadedOnce = true)
    }

    fun closeSession() = _state.update { it.copy(openedSession = null, segments = emptyList(), stats = null, loadedOnce = false) }

    fun deleteSession(session: TrailSession) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.delete(session.id) }
            _state.update {
                it.copy(
                    sessions = it.sessions.filter { old -> old.id != session.id },
                    openedSession = it.openedSession?.takeIf { opened -> opened.id != session.id },
                )
            }
            if (_state.value.openedSession == null && _state.value.segments.isNotEmpty() && !_state.value.demo) closeSession()
        }
    }

    fun logout() {
        viewModelScope.launch {
            store.clearTractive()
            _state.update { it.copy(stored = null, pets = emptyList(), segments = emptyList(), stats = null, openedSession = null, loadedOnce = false) }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun showMessage(message: String) = _state.update { it.copy(message = message) }

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
                store.clearTractive()
                _state.update {
                    it.copy(
                        stored = null, pets = emptyList(),
                        message = if (wasLoggedIn) "Session Tractive expirée, reconnecte-toi dans les paramètres." else "Email ou mot de passe incorrect.",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
