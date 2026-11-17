package app.mantrailing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mantrailing.data.SessionStore
import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import app.mantrailing.data.AppStorage
import app.mantrailing.demo.DemoSession
import app.mantrailing.session.stampChangedDogs
import app.mantrailing.sync.CloudAccount
import app.mantrailing.sync.CloudConfig
import app.mantrailing.sync.SyncEvents
import app.mantrailing.sync.SyncKind
import app.mantrailing.sync.SyncScheduler
import app.mantrailing.weather.WeatherApi
import app.mantrailing.sync.AppSyncStore
import app.mantrailing.sync.SyncItem
import app.mantrailing.session.DEFAULT_KEEP_ON_PHONE_DAYS
import app.mantrailing.sync.Team
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
    /** Ces positions, pour tracer la piste sur la carte pendant qu'on la pose. */
    val recordingTrack: List<Position> = emptyList(),
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
    /** Signalements du traceur affichés sur la carte (piste posée, ou piste suivie par la recherche), en lecture seule. */
    val trailNotes: List<SearchNote> = emptyList(),
    /** Sauvegarde en ligne. */
    val cloud: CloudState = CloudState(),
    /** Espace de la dernière piste ou recherche (null : perso), proposé la fois suivante. */
    val lastSpaceId: String? = null,
) {
    val activeDog: Dog? get() = dogs.find { it.id == activeDogId }

    /** Le collier du chien sélectionné est utilisable (compte Tractive connecté). */
    val connected get() = tractive != null && activeDog?.hasCollar == true
    val dogName get() = activeDog?.name ?: "Mon chien"

    /** Collier de [dogId], si le compte Tractive est connecté. */
    fun trackerOf(dogId: String?): String? = tractive?.let { dogs.find { it.id == dogId }?.trackerId }
}

/** Code d'invitation créé pour une équipe, à transmettre. */
data class Invite(val teamName: String, val code: String)

/** Sauvegarde en ligne et équipes, vues par l'écran des paramètres. */
data class CloudState(
    /** Serveur de sauvegarde configuré dans cette version de l'appli. */
    val available: Boolean = false,
    /** Compte Google connecté (null : sauvegarde désactivée). */
    val email: String? = null,
    val wifiOnly: Boolean = true,
    val syncing: Boolean = false,
    /** Connexion, déconnexion, création d'équipe… en cours. */
    val busy: Boolean = false,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    /** Équipes dont on est membre. */
    val teams: List<Team> = emptyList(),
    /** Jours gardés en entier sur le téléphone (null : tout). */
    val keepDays: Int? = DEFAULT_KEEP_ON_PHONE_DAYS,
    /** Code d'invitation à transmettre, une fois créé. */
    val invite: Invite? = null,
    /** Résultat de la dernière action (connexion, suppression…), affiché dans les paramètres. */
    val message: String? = null,
) {
    val enabled: Boolean get() = email != null

    /** Nom d'un espace pour l'affichage : « Perso » ou le nom de l'équipe. */
    fun spaceName(spaceId: String?): String = spaceId?.let { id -> teams.find { it.id == id }?.name ?: "Équipe" } ?: "Perso"
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SessionStore(app)
    private val api = TractiveApi()
    private val weatherApi = WeatherApi()
    private val repository = AppStorage.sessions(app)
    private val trailRepository = AppStorage.trails(app)
    private val notesBuffer = NotesBuffer(File(app.filesDir, "recording/notes.jsonl"))
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private fun journalOf(spaceId: String?) = AppStorage.syncJournal(getApplication(), spaceId)

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
                    quickNotes = store.quickNotes.first(), lastSpaceId = store.lastSpaceId.first(),
                    recordingNotes = if (recording != null) withContext(Dispatchers.IO) { notesBuffer.read() } else emptyList(),
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
            PhoneRecordingService.track.collect { track -> _state.update { it.copy(recordingPoints = track.size, recordingTrack = track) } }
        }
        viewModelScope.launch { initCloud() }
    }

    // ---------- Sauvegarde en ligne et équipes ----------

    private suspend fun initCloud() {
        val app = getApplication<Application>()
        val available = CloudConfig.isAvailable
        val user = if (available) CloudAccount.current(app) else null
        val email = user?.let { it.email ?: it.name }
        val wifiOnly = store.syncWifiOnly.first()
        _state.update {
            it.copy(
                cloud = it.cloud.copy(
                    available = available, email = email, wifiOnly = wifiOnly,
                    teams = if (email != null) store.cloudTeams.first() else emptyList(), keepDays = store.keepOnPhoneDays.first(),
                ),
            )
        }
        refreshSyncState()
        if (email != null) SyncScheduler.enable(app, wifiOnly)
        // Synchronisation terminée (tâche de fond) : on relit ce qui a pu arriver d'un autre téléphone ou d'une équipe.
        var seen = SyncEvents.status.value.completed
        SyncEvents.status.collect { status ->
            _state.update { it.copy(cloud = it.cloud.copy(syncing = status.running)) }
            if (status.completed != seen) {
                seen = status.completed
                reloadFromDisk()
                refreshSyncState()
            }
        }
    }

    private suspend fun refreshSyncState() {
        val sync = journalOf(null).read()
        // Compte déconnecté par la tâche de fond (jeton refusé) : l'écran le montre.
        val user = CloudAccount.current(getApplication())
        val teams = if (user != null) store.cloudTeams.first() else emptyList()
        _state.update {
            it.copy(
                cloud = it.cloud.copy(
                    email = user?.let { u -> u.email ?: u.name }, lastSyncAt = sync.lastSyncAt, lastError = sync.lastError, teams = teams,
                ),
            )
        }
    }

    private suspend fun reloadFromDisk() {
        val sessions = withContext(Dispatchers.IO) { repository.list() }
        val trails = withContext(Dispatchers.IO) { trailRepository.list() }
        val dogs = store.dogs.first()
        val activeDogId = store.activeDogId.first()?.takeIf { id -> dogs.any { it.id == id } } ?: dogs.firstOrNull()?.id
        _state.update { s ->
            s.copy(
                sessions = sessions, trails = trails, dogs = dogs, activeDogId = activeDogId,
                // Élément affiché supprimé ailleurs (ou équipe quittée) : on le referme.
                openedSession = s.openedSession?.takeIf { o -> sessions.any { it.id == o.id } },
                openedTrail = s.openedTrail?.takeIf { o -> trails.any { it.id == o.id } },
            )
        }
    }

    /** Un changement fait ici sera envoyé (quand le réseau demandé sera là). */
    private fun syncChanges() {
        val cloud = _state.value.cloud
        if (cloud.enabled) SyncScheduler.syncSoon(getApplication(), cloud.wifiOnly)
    }

    /** Suppression faite ici dans l'espace [spaceId], à transmettre aux autres téléphones. */
    private suspend fun recordDeletion(kind: SyncKind, id: String, spaceId: String?) {
        if (_state.value.cloud.enabled) journalOf(spaceId).recordDeletion(kind, id)
    }

    /** Connexion Google ; [activityContext] affiche le choix du compte. */
    fun signInCloud(activityContext: Context) = cloudAction {
        val user = CloudAccount.signIn(activityContext)
        val email = user.email ?: user.name
        _state.update { it.copy(cloud = it.cloud.copy(email = email, message = "Sauvegarde activée.")) }
        SyncScheduler.enable(getApplication(), _state.value.cloud.wifiOnly)
        refreshTeams()
    }

    /** Équipes du compte, relues au serveur ; données des équipes quittées retirées du téléphone. */
    private suspend fun refreshTeams() {
        val app = getApplication<Application>()
        val api = CloudConfig.api(app) ?: return
        val teams = api.me().teams
        store.cloudTeams.first().filter { old -> teams.none { it.id == old.id } }.forEach { AppSyncStore.forgetSpace(app, it.id) }
        store.saveCloudTeams(teams)
        _state.update { it.copy(cloud = it.cloud.copy(teams = teams)) }
        reloadFromDisk()
    }

    private suspend fun api() = CloudConfig.api(getApplication()) ?: error("Active d'abord la sauvegarde en ligne.")

    /** Nouvelle équipe (famille, club…), vide : on y met ensuite ce que l'on veut partager. */
    fun createTeam(name: String) = cloudAction {
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return@cloudAction
        val team = api().createTeam(clean)
        refreshTeams()
        _state.update { it.copy(cloud = it.cloud.copy(message = "Équipe « ${team.name} » créée : invite ses membres.")) }
    }

    /** Code à transmettre à un membre de la famille, au traceur… */
    fun createInvite(team: Team) = cloudAction {
        val code = api().createInvite(team.id)
        _state.update { it.copy(cloud = it.cloud.copy(invite = Invite(team.name, code))) }
    }

    fun dismissInvite() = _state.update { it.copy(cloud = it.cloud.copy(invite = null)) }

    /** Rejoint une équipe : on reçoit ce qui s'y fait, rien de ce que l'on a déjà n'y est envoyé. */
    fun joinTeam(code: String) = cloudAction {
        val team = api().join(code)
        refreshTeams()
        _state.update { it.copy(cloud = it.cloud.copy(message = "Tu as rejoint « ${team.name} ».")) }
        SyncScheduler.syncNow(getApplication())
    }

    /** Quitte l'équipe : ses données sont retirées du téléphone (elles restent à l'équipe). */
    fun leaveTeam(team: Team) = cloudAction {
        api().leave(team.id)
        refreshTeams()
        _state.update { it.copy(cloud = it.cloud.copy(message = "Tu as quitté « ${team.name} ».")) }
    }

    fun signOutCloud() = cloudAction {
        val app = getApplication<Application>()
        SyncScheduler.disable(app)
        // Les données des équipes leur appartiennent : elles ne restent pas sur le téléphone sans compte.
        store.cloudTeams.first().forEach { AppSyncStore.forgetSpace(app, it.id) }
        CloudAccount.signOut(app)
        AppStorage.resetAllJournals(app)
        store.saveCloudTeams(emptyList())
        _state.update { it.copy(cloud = it.cloud.copy(email = null, teams = emptyList(), lastSyncAt = null, lastError = null)) }
        reloadFromDisk()
    }

    /** Supprime l'espace perso en ligne et le compte, quitte les équipes ; les données perso du téléphone restent. */
    fun deleteCloudData(activityContext: Context) = cloudAction {
        val app = getApplication<Application>()
        SyncScheduler.disable(app)
        CloudAccount.deleteEverything(activityContext)
        store.cloudTeams.first().forEach { AppSyncStore.forgetSpace(app, it.id) }
        AppStorage.resetAllJournals(app)
        store.saveCloudTeams(emptyList())
        _state.update {
            it.copy(
                cloud = it.cloud.copy(
                    email = null, teams = emptyList(), lastSyncAt = null, lastError = null,
                    message = "Sauvegarde en ligne et compte supprimés. Tes données perso restent sur ce téléphone.",
                ),
            )
        }
        reloadFromDisk()
    }

    fun setSyncWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            store.saveSyncWifiOnly(wifiOnly)
            _state.update { it.copy(cloud = it.cloud.copy(wifiOnly = wifiOnly)) }
            if (_state.value.cloud.enabled) SyncScheduler.enable(getApplication(), wifiOnly)
        }
    }

    /** Jours gardés en entier sur le téléphone ; le reste n'est plus qu'en ligne (appliqué à la synchronisation). */
    fun setKeepDays(days: Int?) {
        viewModelScope.launch {
            store.saveKeepOnPhoneDays(days)
            _state.update { it.copy(cloud = it.cloud.copy(keepDays = days)) }
            syncChanges()
        }
    }

    fun syncNow() {
        if (_state.value.cloud.enabled) SyncScheduler.syncNow(getApplication())
    }

    /** Version complète d'un élément qui n'est plus qu'en ligne. */
    private suspend fun download(kind: SyncKind, id: String, spaceId: String?): SyncItem {
        val app = getApplication<Application>()
        val api = CloudConfig.api(app) ?: error("Reconnecte la sauvegarde en ligne pour l'afficher.")
        return try {
            api.backend(spaceId).get(kind, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error("Elle n'est plus que dans la sauvegarde en ligne : il faut du réseau pour l'afficher.")
        } ?: error("Introuvable dans la sauvegarde en ligne.")
    }

    private suspend fun fullSession(session: TrailSession): TrailSession =
        if (!session.archived) session
        else AppSyncStore.decodeSession(download(SyncKind.SESSION, session.id, session.spaceId)).copy(spaceId = session.spaceId)

    private suspend fun fullTrail(trail: LaidTrail): LaidTrail =
        if (!trail.archived) trail
        else AppSyncStore.decodeTrail(download(SyncKind.TRAIL, trail.id, trail.spaceId)).copy(spaceId = trail.spaceId)

    /** Partage la recherche avec une équipe, ou la reprend en perso ([spaceId] null). */
    fun moveSession(session: TrailSession, spaceId: String?) = run {
        if (session.spaceId == spaceId) return@run
        val moved = fullSession(session).copy(spaceId = spaceId, archived = false, summary = null, updatedAt = System.currentTimeMillis())
        withContext(Dispatchers.IO) { repository.save(moved) }
        recordDeletion(SyncKind.SESSION, session.id, session.spaceId)
        _state.update { s ->
            s.copy(
                sessions = s.sessions.map { if (it.id == moved.id) moved else it },
                openedSession = s.openedSession?.let { if (it.id == moved.id) moved else it },
                message = "Recherche déplacée dans « ${s.cloud.spaceName(spaceId)} ».",
            )
        }
        syncChanges()
    }

    fun moveTrail(trail: LaidTrail, spaceId: String?) = run {
        if (trail.spaceId == spaceId) return@run
        val moved = fullTrail(trail).copy(spaceId = spaceId, archived = false, lengthMeters = null, updatedAt = System.currentTimeMillis())
        withContext(Dispatchers.IO) { trailRepository.save(moved) }
        recordDeletion(SyncKind.TRAIL, trail.id, trail.spaceId)
        _state.update { s ->
            s.copy(
                trails = s.trails.map { if (it.id == moved.id) moved else it },
                openedTrail = s.openedTrail?.let { if (it.id == moved.id) moved else it },
                message = "Piste déplacée dans « ${s.cloud.spaceName(spaceId)} ».",
            )
        }
        syncChanges()
    }

    /** Partage le chien avec une équipe (toute l'équipe pourra le faire chercher), ou le reprend en perso. */
    fun moveDog(dog: Dog, spaceId: String?) {
        if (dog.spaceId == spaceId) return
        viewModelScope.launch {
            recordDeletion(SyncKind.DOG, dog.id, dog.spaceId)
            saveDogs(_state.value.dogs.map { if (it.id == dog.id) it.copy(spaceId = spaceId) else it }, _state.value.activeDogId)
        }
    }

    private fun cloudAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(cloud = it.cloud.copy(busy = true, message = null)) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: GetCredentialCancellationException) {
                // Choix du compte abandonné : rien à signaler.
            } catch (e: Exception) {
                _state.update { it.copy(cloud = it.cloud.copy(message = "Échec : ${e.message ?: e.javaClass.simpleName}")) }
            } finally {
                _state.update { it.copy(cloud = it.cloud.copy(busy = false)) }
            }
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
            trailNotes = emptyList(),
            loadedOnce = true,
            message = null,
        )
    }

    fun exitDemo() = _state.update {
        it.copy(
            demo = false, range = TimeRange.lastHours(3), segments = emptyList(), stats = null,
            trailLine = emptyList(), notes = emptyList(), trailNotes = emptyList(), loadedOnce = false, message = null,
        )
    }

    // ---------- Chiens ----------

    /** Importe (ou met à jour) les chiens du compte Tractive. */
    fun syncTractiveDogs() = run { fetchTractiveDogs() }

    private suspend fun fetchTractiveDogs() {
        val session = _state.value.tractive ?: return
        val pets = api.pets(session)
        val s = _state.value
        val merged = mergeTractivePets(s.dogs, pets, store.ignoredPetIds.first())
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
        val before = _state.value.dogs
        val stamped = stampChangedDogs(before, dogs, System.currentTimeMillis())
        store.saveDogs(stamped, activeDogId)
        _state.update { it.copy(dogs = stamped, activeDogId = activeDogId) }
        if (stamped != before) syncChanges()
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
            recordDeletion(SyncKind.DOG, dog.id, dog.spaceId)
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
    fun startTrailLaying(spaceId: String? = null) = run {
        if (_state.value.recording != null) return@run
        val recording = ActiveRecording(
            UUID.randomUUID().toString(), _state.value.dogName, TrackSource.PHONE, Instant.now().epochSecond, RecordingKind.TRAIL,
            spaceId = spaceId,
        )
        withContext(Dispatchers.IO) {
            PhoneRecordingService.bufferOf(getApplication()).clear()
            notesBuffer.clear()
        }
        PhoneRecordingService.start(getApplication(), layingTrail = true)
        store.saveActiveRecording(recording)
        store.saveLastSpaceId(recording.spaceId)
        _state.update { it.copy(lastSpaceId = recording.spaceId, recordingNotes = emptyList()) }
        _state.update { it.copy(recording = recording).clearedDisplay() }
    }

    /**
     * Démarre la recherche du chien. Pour le GPS du téléphone, l'autorisation de localisation doit déjà être accordée.
     * [remember] : enregistre [source] comme choix pour les recherches suivantes.
     * [trailId] : piste posée que le chien va suivre.
     */
    fun startRecording(source: TrackSource, remember: Boolean = false, trailId: String? = null, spaceId: String? = null) = run {
        if (_state.value.recording != null) return@run
        if (remember) {
            store.saveTrackSource(source)
            _state.update { it.copy(trackSource = source) }
        }
        val s = _state.value
        val recording = ActiveRecording(
            UUID.randomUUID().toString(), s.dogName, source, Instant.now().epochSecond, RecordingKind.SEARCH, trailId, s.activeDog?.id, spaceId,
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
        store.saveLastSpaceId(recording.spaceId)
        _state.update { it.copy(lastSpaceId = recording.spaceId) }
        _state.update { it.copy(recording = recording, recordingNotes = emptyList(), message = message).clearedDisplay() }
    }

    // ---------- Notes pendant la recherche, ou signalements du traceur pendant la pose ----------

    /** Note posée maintenant ; sa position sera retrouvée sur la trace (ou le parcours du traceur) à la fin. */
    fun addNote(type: NoteType, comment: String? = null) {
        val recording = _state.value.recording ?: return
        val note = SearchNote(UUID.randomUUID().toString(), Instant.now().epochSecond, type, comment?.trim()?.takeIf { it.isNotEmpty() })
        viewModelScope.launch {
            withContext(Dispatchers.IO) { notesBuffer.append(note) }
            if (_state.value.recording?.id == recording.id) _state.update { it.copy(recordingNotes = it.recordingNotes + note) }
        }
    }

    /** Précision ajoutée à la dernière note (« chien au portail », « route passante »…), après coup. */
    fun commentLastNote(comment: String) {
        val clean = comment.trim().takeIf { it.isNotEmpty() } ?: return
        val last = _state.value.recordingNotes.lastOrNull() ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val notes = notesBuffer.read().map { if (it.id == last.id) it.copy(comment = clean) else it }
                notesBuffer.clear()
                notes.forEach(notesBuffer::append)
            }
            _state.update { s -> s.copy(recordingNotes = s.recordingNotes.map { if (it.id == last.id) it.copy(comment = clean) else it }) }
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
        val updated = session.copy(notes = change(session.notes), updatedAt = System.currentTimeMillis())
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.save(updated) }
            syncChanges()
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
        // Signalements du traceur, placés sur son parcours.
        val notes = locateNotes(withContext(Dispatchers.IO) { notesBuffer.read() }, points)
        val trail = LaidTrail(
            recording.id, recording.startedAt, endedAt, points, updatedAt = System.currentTimeMillis(), spaceId = recording.spaceId,
            notes = notes,
        )
        withContext(Dispatchers.IO) {
            trailRepository.save(trail)
            notesBuffer.clear()
        }
        syncChanges()
        _state.update {
            it.copy(
                recording = null,
                trails = listOf(trail) + it.trails.filter { old -> old.id != trail.id },
                message = "Piste enregistrée. Laisse-la vieillir, puis lance la recherche.",
            )
        }
        showTrail(trail)
        fetchTrailWeather(trail)
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
            notes = locateNotes(notes, segments.flatten()), dogId = recording.dogId, updatedAt = System.currentTimeMillis(), spaceId = recording.spaceId,
            trailNotes = trail?.notes.orEmpty(), trailWeather = trail?.weather,
        )
        withContext(Dispatchers.IO) {
            repository.save(session)
            notesBuffer.clear()
        }
        syncChanges()
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
        fetchSessionWeather(session)
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

    fun openSession(opened: TrailSession) = run {
        // Recherche qui n'est plus qu'en ligne : téléchargée pour l'affichage (le téléphone garde le résumé).
        val session = fullSession(opened)
        showSession(session)
        fetchSessionWeather(session)
        // Recherche au collier enregistrée avant que Tractive ait transmis les positions : nouvel essai.
        val dogId = session.dogId ?: _state.value.dogs.find { it.name == session.dogName }?.id
        if (session.source == TrackSource.TRACTIVE && session.segments.isEmpty() && _state.value.trackerOf(dogId) != null) {
            val segments = fetchCollarTrack(dogId, session.startedAt, session.endedAt)
            if (segments.isEmpty()) {
                _state.update { it.copy(message = "Le collier n'a toujours pas transmis de positions pour cette recherche.") }
            } else {
                // Notes posées avant que le collier ait transmis ses positions : on les place maintenant.
                val updated = session.copy(
                    segments = segments, notes = locateNotes(session.notes, segments.flatten()), updatedAt = System.currentTimeMillis(),
                )
                withContext(Dispatchers.IO) { repository.save(updated) }
                syncChanges()
                _state.update { it.copy(sessions = it.sessions.map { old -> if (old.id == updated.id) updated else old }) }
                showSession(updated)
            }
        }
    }

    private fun showSession(session: TrailSession) = _state.update {
        it.copy(
            openedSession = session, openedTrail = null, demo = false, segments = session.segments,
            stats = TrackStats.of(session.segments), trailLine = session.trail, notes = session.notes, trailNotes = session.trailNotes,
            loadedOnce = true,
        )
    }

    fun openTrail(trail: LaidTrail) = run {
        val full = fullTrail(trail)
        showTrail(full)
        fetchTrailWeather(full)
    }

    // ---------- Météo (Open-Meteo), récupérée après coup : sans réseau, nouvel essai à la prochaine ouverture ----------

    /** La météo n'est disponible que sur les 3 derniers mois. */
    private fun weatherStillAvailable(epochSeconds: Long) = Instant.now().epochSecond - epochSeconds < 90 * 86_400L

    /** Météo au début de la pose, au premier point du parcours du traceur. */
    private fun fetchTrailWeather(trail: LaidTrail) {
        val start = trail.points.firstOrNull() ?: return
        if (trail.weather != null || trail.archived || !weatherStillAvailable(trail.startedAt)) return
        viewModelScope.launch {
            val weather = weatherApi.at(start.lat, start.lon, trail.startedAt) ?: return@launch
            val current = withContext(Dispatchers.IO) { trailRepository.load(trail.id) } ?: return@launch
            if (current.weather != null || current.archived) return@launch
            val updated = current.copy(weather = weather, updatedAt = System.currentTimeMillis())
            withContext(Dispatchers.IO) { trailRepository.save(updated) }
            _state.update { s ->
                s.copy(
                    trails = s.trails.map { if (it.id == updated.id) updated else it },
                    openedTrail = s.openedTrail?.let { if (it.id == updated.id) updated else it },
                )
            }
            syncChanges()
        }
    }

    /** Météo au départ du chien, au premier point de sa trace (ou au départ de la piste suivie). */
    private fun fetchSessionWeather(session: TrailSession) {
        val start = session.segments.firstOrNull()?.firstOrNull() ?: session.trail.firstOrNull() ?: return
        if (session.weather != null || session.archived || !weatherStillAvailable(session.startedAt)) return
        viewModelScope.launch {
            val weather = weatherApi.at(start.lat, start.lon, session.startedAt) ?: return@launch
            val current = withContext(Dispatchers.IO) { repository.load(session.id) } ?: return@launch
            if (current.weather != null || current.archived) return@launch
            val updated = current.copy(weather = weather, updatedAt = System.currentTimeMillis())
            withContext(Dispatchers.IO) { repository.save(updated) }
            _state.update { s ->
                s.copy(
                    sessions = s.sessions.map { if (it.id == updated.id) updated else it },
                    openedSession = s.openedSession?.let { if (it.id == updated.id) updated else it },
                )
            }
            syncChanges()
        }
    }

    private fun showTrail(trail: LaidTrail) = _state.update {
        it.copy(
            openedTrail = trail, openedSession = null, demo = false, segments = emptyList(),
            stats = null, trailLine = trail.points, notes = emptyList(), trailNotes = trail.notes, loadedOnce = true,
        )
    }

    fun closeSession() = _state.update { it.clearedDisplay() }

    /** Plus rien d'affiché sur la carte. */
    private fun UiState.clearedDisplay() = copy(
        demo = false, openedSession = null, openedTrail = null, segments = emptyList(), stats = null,
        trailLine = emptyList(), notes = emptyList(), trailNotes = emptyList(), loadedOnce = false,
    )

    fun deleteTrail(trail: LaidTrail) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { trailRepository.delete(trail.id) }
            recordDeletion(SyncKind.TRAIL, trail.id, trail.spaceId)
            syncChanges()
            _state.update { s ->
                val updated = s.copy(trails = s.trails.filter { it.id != trail.id })
                if (s.openedTrail?.id == trail.id) updated.clearedDisplay() else updated
            }
        }
    }

    fun deleteSession(session: TrailSession) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.delete(session.id) }
            recordDeletion(SyncKind.SESSION, session.id, session.spaceId)
            syncChanges()
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
