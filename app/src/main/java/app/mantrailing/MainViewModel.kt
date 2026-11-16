package app.mantrailing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mantrailing.data.SessionStore
import app.mantrailing.demo.DemoSession
import app.mantrailing.session.Dog
import app.mantrailing.session.mergeTractivePets
import app.mantrailing.tractive.Session
import app.mantrailing.recording.PhoneRecordingService
import app.mantrailing.session.ActiveRecording
import app.mantrailing.session.LaidTrail
import app.mantrailing.session.LaidTrailRepository
import app.mantrailing.session.DEFAULT_QUICK_NOTES
import app.mantrailing.session.NoteType
import app.mantrailing.session.NotesBuffer
import app.mantrailing.session.SearchNote
import app.mantrailing.session.locateNotes
import app.mantrailing.session.RecordingKind
import app.mantrailing.session.SessionRepository
import app.mantrailing.session.TrackSource
import app.mantrailing.session.TrailSession
import app.mantrailing.track.TrackStats
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
    /** Compte Tractive connecté. */
    val tractive: Session? = null,
    /** Chiens de l'équipe. */
    val dogs: List<Dog> = emptyList(),
    /** Chien sélectionné : c'est lui qui cherche. */
    val activeDogId: String? = null,
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
    /** Notes posées pendant la recherche en cours. */
    val recordingNotes: List<SearchNote> = emptyList(),
    /** Notes rapides proposées pendant la recherche. */
    val quickNotes: List<NoteType> = DEFAULT_QUICK_NOTES,
    /** Notes affichées sur la carte (recherche enregistrée ou démo). */
    val notes: List<SearchNote> = emptyList(),
) {
    val activeDog: Dog? get() = dogs.find { it.id == activeDogId }

    /** Le collier du chien sélectionné est utilisable (compte Tractive connecté). */
    val connected get() = tractive != null && activeDog?.hasCollar == true
    val dogName get() = activeDog?.name ?: "Mon chien"

    /** Collier de [dogId], si le compte Tractive est connecté. */
    fun trackerOf(dogId: String?): String? = tractive?.let { dogs.find { it.id == dogId }?.trackerId }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SessionStore(app)
    private val api = TractiveApi()
    private val repository = SessionRepository(File(app.filesDir, "sessions"))
    private val trailRepository = LaidTrailRepository(File(app.filesDir, "trails"))
    private val notesBuffer = NotesBuffer(File(app.filesDir, "recording/notes.jsonl"))
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val tractive = store.tractive.first()
            val dogs = store.dogs.first()
            val activeDogId = store.activeDogId.first()?.takeIf { id -> dogs.any { it.id == id } } ?: dogs.firstOrNull()?.id
            val recording = store.activeRecording.first()
            val sessions = withContext(Dispatchers.IO) { repository.list() }
            val trails = withContext(Dispatchers.IO) { trailRepository.list() }
            _state.update {
                it.copy(
                    ready = true, tractive = tractive, dogs = dogs, activeDogId = activeDogId, trackSource = store.trackSource.first(),
                    recording = recording, sessions = sessions, trails = trails,
                    quickNotes = store.quickNotes.first(),
                    recordingNotes = if (recording?.kind == RecordingKind.SEARCH) withContext(Dispatchers.IO) { notesBuffer.read() } else emptyList(),
                )
            }
            // Enregistre la liste (migration du chien unique d'avant) ; compte connecté sans chien lié : import.
            store.saveDogs(dogs, activeDogId)
            if (tractive != null && dogs.none { it.tractivePetId != null }) syncTractiveDogs()
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
        _state.update { it.copy(tractive = session, demo = false, segments = emptyList(), stats = null) }
        fetchTractiveDogs()
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
            notes = DemoSession.notes,
            loadedOnce = true,
            message = null,
        )
    }

    fun exitDemo() = _state.update {
        it.copy(
            demo = false, range = TimeRange.lastHours(3), segments = emptyList(), stats = null,
            trailLine = emptyList(), notes = emptyList(), loadedOnce = false, message = null,
        )
    }

    // ---------- Chiens ----------

    /** Importe (ou met à jour) les chiens du compte Tractive. */
    fun syncTractiveDogs() = run { fetchTractiveDogs() }

    private suspend fun fetchTractiveDogs() {
        val session = _state.value.tractive ?: return
        val pets = api.pets(session)
        val s = _state.value
        val merged = mergeTractivePets(s.dogs, pets, store.ignoredPetIds.first()) { UUID.randomUUID().toString() }
        // Premier import : le chien sélectionné est le premier qui porte un collier.
        val active = s.activeDogId?.takeIf { id -> merged.any { it.id == id } }
            ?: (merged.firstOrNull { it.hasCollar } ?: merged.firstOrNull())?.id
        saveDogs(merged, active)
        _state.update {
            it.copy(
                message = when {
                    pets.isEmpty() -> "Aucun animal trouvé sur ce compte Tractive."
                    else -> "Chiens Tractive à jour : ${pets.joinToString { p -> p.name }}."
                },
            )
        }
    }

    private suspend fun saveDogs(dogs: List<Dog>, activeDogId: String?) {
        store.saveDogs(dogs, activeDogId)
        _state.update { it.copy(dogs = dogs, activeDogId = activeDogId) }
    }

    /** Chien qui va chercher ; on revient à la carte vierge. */
    fun selectDog(dog: Dog) {
        viewModelScope.launch {
            if (_state.value.recording != null) return@launch
            saveDogs(_state.value.dogs, dog.id)
            _state.update { it.clearedDisplay() }
        }
    }

    /** Nouveau chien sans collier (sa trace sera prise au téléphone) ; il devient le chien sélectionné. */
    fun addDog(name: String) {
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return
        viewModelScope.launch {
            val dog = Dog(UUID.randomUUID().toString(), clean)
            saveDogs(_state.value.dogs + dog, dog.id)
        }
    }

    fun renameDog(dog: Dog, name: String) {
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return
        viewModelScope.launch {
            saveDogs(_state.value.dogs.map { if (it.id == dog.id) it.copy(name = clean) else it }, _state.value.activeDogId)
        }
    }

    /** Retire le chien de l'appli ; ses recherches restent dans « Mes pistes ». */
    fun deleteDog(dog: Dog) {
        viewModelScope.launch {
            val s = _state.value
            if (s.recording?.dogId == dog.id) return@launch
            // Animal Tractive retiré (le chat de la maison…) : pas réimporté à la prochaine synchronisation.
            dog.tractivePetId?.let { store.saveIgnoredPetIds(store.ignoredPetIds.first() + it) }
            val remaining = s.dogs.filter { it.id != dog.id }
            saveDogs(remaining, s.activeDogId?.takeIf { it != dog.id } ?: remaining.firstOrNull()?.id)
        }
    }

    fun setRange(range: TimeRange) = _state.update { it.copy(range = range) }

    fun loadTrack() = run {
        val s = _state.value
        if (s.demo) return@run
        val session = s.tractive ?: return@run
        val tracker = s.activeDog?.trackerId ?: error("Ce chien n'a pas de collier associé.")
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
        val session = s.tractive ?: return@run
        val tracker = s.activeDog?.trackerId ?: error("Ce chien n'a pas de collier associé.")
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
            UUID.randomUUID().toString(), s.dogName, source, Instant.now().epochSecond, RecordingKind.SEARCH, trailId, s.activeDog?.id,
        )
        var message: String? = null
        when (source) {
            TrackSource.PHONE -> {
                withContext(Dispatchers.IO) { PhoneRecordingService.bufferOf(getApplication()).clear() }
                PhoneRecordingService.start(getApplication())
            }
            TrackSource.TRACTIVE -> {
                val session = s.tractive ?: error("Connecte d'abord le collier Tractive dans les paramètres.")
                val tracker = s.activeDog?.trackerId ?: error("${s.dogName} n'a pas de collier associé.")
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
        withContext(Dispatchers.IO) { notesBuffer.clear() }
        store.saveActiveRecording(recording)
        _state.update { it.copy(recording = recording, recordingNotes = emptyList(), message = message).clearedDisplay() }
    }

    // ---------- Notes pendant la recherche ----------

    /** Note posée maintenant ; sa position sera retrouvée sur la trace à la fin de la recherche. */
    fun addNote(type: NoteType, comment: String? = null) {
        val recording = _state.value.recording?.takeIf { it.kind == RecordingKind.SEARCH } ?: return
        val note = SearchNote(UUID.randomUUID().toString(), Instant.now().epochSecond, type, comment?.trim()?.takeIf { it.isNotEmpty() })
        viewModelScope.launch {
            withContext(Dispatchers.IO) { notesBuffer.append(note) }
            if (_state.value.recording?.id == recording.id) _state.update { it.copy(recordingNotes = it.recordingNotes + note) }
        }
    }

    /** Annule la dernière note (appui par erreur, le chien tire sur la longe…). */
    fun undoLastNote() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { notesBuffer.removeLast() }
            _state.update { it.copy(recordingNotes = it.recordingNotes.dropLast(1)) }
        }
    }

    /** Complète ou corrige une note d'une recherche enregistrée. */
    fun updateNote(note: SearchNote) = editNotes { notes -> notes.map { if (it.id == note.id) note else it } }

    fun deleteNote(note: SearchNote) = editNotes { notes -> notes.filter { it.id != note.id } }

    private fun editNotes(change: (List<SearchNote>) -> List<SearchNote>) {
        val session = _state.value.openedSession ?: return
        val updated = session.copy(notes = change(session.notes))
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.save(updated) }
            _state.update {
                it.copy(
                    openedSession = updated,
                    notes = updated.notes,
                    sessions = it.sessions.map { old -> if (old.id == updated.id) updated else old },
                )
            }
        }
    }

    fun setQuickNotes(types: List<NoteType>) {
        viewModelScope.launch {
            store.saveQuickNotes(types)
            _state.update { it.copy(quickNotes = types) }
        }
    }

    /** Termine l'enregistrement en cours : pose de la piste ou recherche du chien. */
    fun stopRecording(outcome: NoteType? = null) = run {
        val recording = _state.value.recording ?: return@run
        when (recording.kind) {
            RecordingKind.TRAIL -> finishTrail(recording)
            RecordingKind.SEARCH -> finishSearch(recording, outcome)
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

    private suspend fun finishSearch(recording: ActiveRecording, outcome: NoteType?) {
        val endedAt = Instant.now().epochSecond
        // Issue choisie en terminant : notée à l'heure de fin (écrite avant la relecture des notes).
        if (outcome != null) withContext(Dispatchers.IO) { notesBuffer.append(SearchNote(UUID.randomUUID().toString(), endedAt, outcome)) }
        var message: String? = null
        val segments: List<List<Position>> = when (recording.source) {
            TrackSource.PHONE -> {
                val points = withContext(Dispatchers.IO) { PhoneRecordingService.stop(getApplication()) }
                if (points.isEmpty()) message = "Aucune position GPS enregistrée : signal trop faible ?"
                listOf(points).filter { it.isNotEmpty() }
            }
            TrackSource.TRACTIVE -> fetchCollarTrack(recording.dogId, recording.startedAt, endedAt).also { segs ->
                if (segs.isEmpty()) message = "Trace du collier pas encore disponible : rouvre la recherche un peu plus tard depuis « Mes pistes »."
                stopLiveQuietly(recording.dogId)
            }
        }
        val trail = recording.trailId?.let { id ->
            _state.value.trails.find { it.id == id } ?: withContext(Dispatchers.IO) { trailRepository.load(id) }
        }
        val notes = withContext(Dispatchers.IO) { notesBuffer.read() }
        val session = TrailSession(
            recording.id, recording.dogName, recording.source, recording.startedAt, endedAt, segments,
            trailId = trail?.id, trail = trail?.points.orEmpty(), trailEndedAt = trail?.endedAt,
            notes = locateNotes(notes, segments.flatten()), dogId = recording.dogId,
        )
        withContext(Dispatchers.IO) {
            repository.save(session)
            notesBuffer.clear()
        }
        store.saveActiveRecording(null)
        _state.update {
            it.copy(
                recording = null,
                recordingNotes = emptyList(),
                sessions = listOf(session) + it.sessions.filter { old -> old.id != session.id },
                message = message,
            )
        }
        showSession(session)
    }

    /** Positions du collier de [dogId] pendant la recherche (avec une marge d'une minute). Vide en cas d'échec réseau. */
    private suspend fun fetchCollarTrack(dogId: String?, startedAt: Long, endedAt: Long): List<List<Position>> {
        val s = _state.value
        val session = s.tractive ?: return emptyList()
        val tracker = s.trackerOf(dogId) ?: return emptyList()
        return try {
            api.positions(session, tracker, Instant.ofEpochSecond(startedAt - 60), Instant.ofEpochSecond(endedAt + 60))
        } catch (e: TractiveAuthException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun stopLiveQuietly(dogId: String?) {
        val s = _state.value
        val session = s.tractive ?: return
        val tracker = s.trackerOf(dogId) ?: return
        runCatching { api.setLiveTracking(session, tracker, false) }
    }

    // ---------- Mes pistes ----------

    fun openSession(session: TrailSession) = run {
        showSession(session)
        // Recherche au collier enregistrée avant que Tractive ait transmis les positions : nouvel essai.
        val dogId = session.dogId ?: _state.value.dogs.find { it.name == session.dogName }?.id
        if (session.source == TrackSource.TRACTIVE && session.segments.isEmpty() && _state.value.trackerOf(dogId) != null) {
            val segments = fetchCollarTrack(dogId, session.startedAt, session.endedAt)
            if (segments.isEmpty()) {
                _state.update { it.copy(message = "Le collier n'a toujours pas transmis de positions pour cette recherche.") }
            } else {
                // Notes posées avant que le collier ait transmis ses positions : on les place maintenant.
                val updated = session.copy(segments = segments, notes = locateNotes(session.notes, segments.flatten()))
                withContext(Dispatchers.IO) { repository.save(updated) }
                _state.update { it.copy(sessions = it.sessions.map { old -> if (old.id == updated.id) updated else old }) }
                showSession(updated)
            }
        }
    }

    private fun showSession(session: TrailSession) = _state.update {
        it.copy(
            openedSession = session, openedTrail = null, demo = false, segments = session.segments,
            stats = TrackStats.of(session.segments), trailLine = session.trail, notes = session.notes, loadedOnce = true,
        )
    }

    fun openTrail(trail: LaidTrail) = showTrail(trail)

    private fun showTrail(trail: LaidTrail) = _state.update {
        it.copy(
            openedTrail = trail, openedSession = null, demo = false, segments = emptyList(),
            stats = null, trailLine = trail.points, notes = emptyList(), loadedOnce = true,
        )
    }

    fun closeSession() = _state.update { it.clearedDisplay() }

    /** Plus rien d'affiché sur la carte. */
    private fun UiState.clearedDisplay() = copy(
        demo = false, openedSession = null, openedTrail = null, segments = emptyList(), stats = null,
        trailLine = emptyList(), notes = emptyList(), loadedOnce = false,
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
            _state.update { it.copy(tractive = null, segments = emptyList(), stats = null, openedSession = null, loadedOnce = false) }
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
                val wasLoggedIn = _state.value.tractive != null
                store.clearTractive()
                _state.update {
                    it.copy(
                        tractive = null,
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
