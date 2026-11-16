package app.mantrailing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mantrailing.data.SessionStore
import app.mantrailing.data.StoredSession
import app.mantrailing.demo.DemoSession
import app.mantrailing.recording.PhoneRecordingService
import app.mantrailing.session.ActiveRecording
import app.mantrailing.session.LaidTrail
import app.mantrailing.session.LaidTrailRepository
import app.mantrailing.session.RecordingKind
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
    /** Recherche enregistrée affichée sur la carte. */
    val openedSession: TrailSession? = null,
    /** Pistes posées par le traceur, la plus récente d'abord. */
    val trails: List<LaidTrail> = emptyList(),
    /** Piste posée affichée seule sur la carte (avant la recherche). */
    val openedTrail: LaidTrail? = null,
    /** Piste du traceur dessinée sur la carte (avec ou sans trace du chien). */
    val trailLine: List<Position> = emptyList(),
) {
    val connected get() = stored?.pet != null
    val dogName get() = stored?.pet?.name ?: "Mon chien"
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SessionStore(app)
    private val api = TractiveApi()
    private val repository = SessionRepository(File(app.filesDir, "sessions"))
    private val trailRepository = LaidTrailRepository(File(app.filesDir, "trails"))
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = store.session.first()
            val recording = store.activeRecording.first()
            val sessions = withContext(Dispatchers.IO) { repository.list() }
            val trails = withContext(Dispatchers.IO) { trailRepository.list() }
            _state.update {
                it.copy(
                    ready = true, stored = stored, trackSource = store.trackSource.first(),
                    recording = recording, sessions = sessions, trails = trails,
                )
            }
            if (stored != null && stored.pet == null) loadPets()
            // Appli relancée pendant un enregistrement au GPS du téléphone : on le reprend.
            if (recording?.source == TrackSource.PHONE && !PhoneRecordingService.running.value &&
                PhoneRecordingService.hasLocationPermission(getApplication())
            ) {
                PhoneRecordingService.start(getApplication(), layingTrail = recording.kind == RecordingKind.TRAIL)
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
            openedTrail = null,
            range = DemoSession.range,
            segments = DemoSession.segments,
            stats = TrackStats.of(DemoSession.segments),
            trailLine = DemoSession.trail,
            loadedOnce = true,
            message = null,
        )
    }

    fun exitDemo() = _state.update {
        it.copy(
            demo = false, range = TimeRange.lastHours(3), segments = emptyList(), stats = null,
            trailLine = emptyList(), loadedOnce = false, message = null,
        )
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
                openedTrail = null,
                trailLine = emptyList(),
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

    // ---------- Enregistrement : pose de la piste et recherche ----------

    fun setTrackSource(source: TrackSource) {
        viewModelScope.launch {
            store.saveTrackSource(source)
            _state.update { it.copy(trackSource = source) }
        }
    }

    /** Le traceur pose la piste : son parcours est enregistré au GPS du téléphone (autorisation déjà accordée). */
    fun startTrailLaying() = run {
        if (_state.value.recording != null) return@run
        val recording = ActiveRecording(
            UUID.randomUUID().toString(), _state.value.dogName, TrackSource.PHONE, Instant.now().epochSecond, RecordingKind.TRAIL,
        )
        withContext(Dispatchers.IO) { PhoneRecordingService.bufferOf(getApplication()).clear() }
        PhoneRecordingService.start(getApplication(), layingTrail = true)
        store.saveActiveRecording(recording)
        _state.update { it.copy(recording = recording).clearedDisplay() }
    }

    /**
     * Démarre la recherche du chien. Pour le GPS du téléphone, l'autorisation de localisation doit déjà être accordée.
     * [remember] : enregistre [source] comme choix pour les recherches suivantes.
     * [trailId] : piste posée que le chien va suivre.
     */
    fun startRecording(source: TrackSource, remember: Boolean = false, trailId: String? = null) = run {
        if (_state.value.recording != null) return@run
        if (remember) {
            store.saveTrackSource(source)
            _state.update { it.copy(trackSource = source) }
        }
        val s = _state.value
        val recording = ActiveRecording(
            UUID.randomUUID().toString(), s.dogName, source, Instant.now().epochSecond, RecordingKind.SEARCH, trailId,
        )
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
                    "Recherche démarrée, mode LIVE demandé au collier."
                } catch (e: TractiveAuthException) {
                    throw e
                } catch (e: Exception) {
                    "Recherche démarrée, mais le mode LIVE n'a pas pu être activé : active-le dans l'appli Tractive."
                }
            }
        }
        store.saveActiveRecording(recording)
        _state.update { it.copy(recording = recording, message = message).clearedDisplay() }
    }

    /** Termine l'enregistrement en cours : pose de la piste ou recherche du chien. */
    fun stopRecording() = run {
        val recording = _state.value.recording ?: return@run
        when (recording.kind) {
            RecordingKind.TRAIL -> finishTrail(recording)
            RecordingKind.SEARCH -> finishSearch(recording)
        }
    }

    private suspend fun finishTrail(recording: ActiveRecording) {
        val endedAt = Instant.now().epochSecond
        val points = withContext(Dispatchers.IO) { PhoneRecordingService.stop(getApplication()) }
        store.saveActiveRecording(null)
        if (points.isEmpty()) {
            _state.update { it.copy(recording = null, message = "Aucune position GPS enregistrée : la piste n'a pas été gardée.") }
            return
        }
        val trail = LaidTrail(recording.id, recording.startedAt, endedAt, points)
        withContext(Dispatchers.IO) { trailRepository.save(trail) }
        _state.update {
            it.copy(
                recording = null,
                trails = listOf(trail) + it.trails.filter { old -> old.id != trail.id },
                message = "Piste enregistrée. Laisse-la vieillir, puis lance la recherche.",
            )
        }
        showTrail(trail)
    }

    private suspend fun finishSearch(recording: ActiveRecording) {
        val endedAt = Instant.now().epochSecond
        var message: String? = null
        val segments: List<List<Position>> = when (recording.source) {
            TrackSource.PHONE -> {
                val points = withContext(Dispatchers.IO) { PhoneRecordingService.stop(getApplication()) }
                if (points.isEmpty()) message = "Aucune position GPS enregistrée : signal trop faible ?"
                listOf(points).filter { it.isNotEmpty() }
            }
            TrackSource.TRACTIVE -> fetchCollarTrack(recording.startedAt, endedAt).also { segs ->
                if (segs.isEmpty()) message = "Trace du collier pas encore disponible : rouvre la recherche un peu plus tard depuis « Mes pistes »."
                stopLiveQuietly()
            }
        }
        val trail = recording.trailId?.let { id ->
            _state.value.trails.find { it.id == id } ?: withContext(Dispatchers.IO) { trailRepository.load(id) }
        }
        val session = TrailSession(
            recording.id, recording.dogName, recording.source, recording.startedAt, endedAt, segments,
            trailId = trail?.id, trail = trail?.points.orEmpty(), trailEndedAt = trail?.endedAt,
        )
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

    /** Positions du collier pendant la recherche (avec une marge d'une minute). Vide en cas d'échec réseau. */
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
        // Recherche au collier enregistrée avant que Tractive ait transmis les positions : nouvel essai.
        if (session.source == TrackSource.TRACTIVE && session.segments.isEmpty() && _state.value.connected) {
            val segments = fetchCollarTrack(session.startedAt, session.endedAt)
            if (segments.isEmpty()) {
                _state.update { it.copy(message = "Le collier n'a toujours pas transmis de positions pour cette recherche.") }
            } else {
                val updated = session.copy(segments = segments)
                withContext(Dispatchers.IO) { repository.save(updated) }
                _state.update { it.copy(sessions = it.sessions.map { old -> if (old.id == updated.id) updated else old }) }
                showSession(updated)
            }
        }
    }

    private fun showSession(session: TrailSession) = _state.update {
        it.copy(
            openedSession = session, openedTrail = null, demo = false, segments = session.segments,
            stats = TrackStats.of(session.segments), trailLine = session.trail, loadedOnce = true,
        )
    }

    fun openTrail(trail: LaidTrail) = showTrail(trail)

    private fun showTrail(trail: LaidTrail) = _state.update {
        it.copy(
            openedTrail = trail, openedSession = null, demo = false, segments = emptyList(),
            stats = null, trailLine = trail.points, loadedOnce = true,
        )
    }

    fun closeSession() = _state.update { it.clearedDisplay() }

    /** Plus rien d'affiché sur la carte. */
    private fun UiState.clearedDisplay() = copy(
        demo = false, openedSession = null, openedTrail = null, segments = emptyList(), stats = null,
        trailLine = emptyList(), loadedOnce = false,
    )

    fun deleteTrail(trail: LaidTrail) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { trailRepository.delete(trail.id) }
            _state.update { s ->
                val updated = s.copy(trails = s.trails.filter { it.id != trail.id })
                if (s.openedTrail?.id == trail.id) updated.clearedDisplay() else updated
            }
        }
    }

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
