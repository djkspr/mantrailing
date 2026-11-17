package app.mantrailing.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mantrailing.TimeRange
import app.mantrailing.CloudState
import app.mantrailing.UiState
import app.mantrailing.session.Dog
import app.mantrailing.demo.DemoSession
import app.mantrailing.session.ActiveRecording
import app.mantrailing.session.DEFAULT_QUICK_NOTES
import app.mantrailing.session.LaidTrail
import app.mantrailing.session.NoteType
import app.mantrailing.session.SearchNote
import app.mantrailing.session.RecordingKind
import app.mantrailing.session.TrackSource
import app.mantrailing.session.TrailSession
import app.mantrailing.track.TrackStats
import app.mantrailing.tractive.Position
import app.mantrailing.tractive.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class ScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val rex = Dog("d1", "Rex", "p1", "TRK1")
    private val nala = Dog("d2", "Nala")
    private val session = Session("u1", "tok", 0)
    private val connected = UiState(ready = true, tractive = session, dogs = listOf(rex, nala), activeDogId = rex.id)

    // --- Accueil sans compte (ce que voit l'examinateur Google Play) ---

    @Test
    fun accueil_sans_compte_propose_connexion_et_demo() {
        var settings = 0
        var demo = 0
        rule.setContent {
            MaterialTheme { TrackScreen(UiState(ready = true), TrackActions(onOpenSettings = { settings++ }, onStartDemo = { demo++ })) }
        }

        rule.onNodeWithText("Mantrailing").assertIsDisplayed()
        rule.onNodeWithText("Activer LIVE").assertDoesNotExist()
        rule.onNodeWithTag("load").assertDoesNotExist()
        rule.onNodeWithText("Ajouter mes chiens").performClick()
        rule.onNodeWithText("Voir une séance de démonstration").performClick()

        assertEquals(1, settings)
        assertEquals(1, demo)
    }

    @Test
    fun menu_donne_acces_aux_parametres() {
        var settings = 0
        rule.setContent { MaterialTheme { TrackScreen(UiState(ready = true), TrackActions(onOpenSettings = { settings++ })) } }

        rule.onNodeWithContentDescription("Menu").performClick()
        rule.onNodeWithText("Paramètres").performClick()

        assertEquals(1, settings)
    }

    @Test
    fun seance_de_demonstration_affichee_avec_bandeau() {
        var exit = 0
        val state = UiState(
            ready = true, demo = true, range = DemoSession.range,
            segments = DemoSession.segments, stats = TrackStats.of(DemoSession.segments), trailLine = DemoSession.trail,
        )
        rule.setContent { MaterialTheme { TrackScreen(state, TrackActions(onExitDemo = { exit++ })) } }

        rule.onNodeWithText("Rex (démo)").assertIsDisplayed()
        rule.onNodeWithText("Séance de démonstration : données fictives. Piste de 45 min.").assertIsDisplayed()
        rule.onNodeWithTag("welcome").assertDoesNotExist()
        rule.onNodeWithText("Activer LIVE").assertDoesNotExist()
        rule.onNodeWithTag("stats").assertIsDisplayed()
        // La démo montre la comparaison avec la piste du traceur.
        rule.onNodeWithTag("deviation").assertIsDisplayed()
        rule.onNodeWithText("Atteinte").assertIsDisplayed()
        rule.onNodeWithText("Quitter").performClick()

        assertEquals(1, exit)
    }

    // --- Notes pendant la recherche ---

    @Test
    fun notes_rapides_pendant_la_recherche() {
        val added = mutableListOf<NoteType>()
        var undos = 0
        val start = Instant.now().epochSecond - 200
        val recording = ActiveRecording("r1", "Rex", TrackSource.PHONE, start, RecordingKind.SEARCH)
        val notes = listOf(SearchNote("1", start + 30, NoteType.HESITATION), SearchNote("2", start + 150, NoteType.TRACK_LOST))
        rule.setContent {
            MaterialTheme {
                TrackScreen(
                    UiState(ready = true, recording = recording, recordingNotes = notes),
                    TrackActions(onAddNote = { type, _ -> added += type }, onUndoNote = { undos++ }),
                )
            }
        }

        rule.onNodeWithTag("quick-notes").assertIsDisplayed()
        rule.onNodeWithText("Hésitation").performClick()
        rule.onNodeWithText("Erreur de ma part").performClick()
        rule.onNodeWithText("2 notes · dernière : Perte de piste (à 2 min 30s)").assertIsDisplayed()
        rule.onNodeWithText("Annuler").performClick()

        assertEquals(listOf(NoteType.HESITATION, NoteType.HANDLER_ERROR), added)
        assertEquals(1, undos)
    }

    @Test
    fun pendant_la_pose_seulement_les_notes_du_traceur() {
        val recording = ActiveRecording("t1", "Rex", TrackSource.PHONE, Instant.now().epochSecond, RecordingKind.TRAIL)
        rule.setContent { MaterialTheme { TrackScreen(UiState(ready = true, recording = recording), TrackActions()) } }
        rule.onNodeWithTag("quick-notes").assertIsDisplayed()
        rule.onNodeWithText("Danger").assertIsDisplayed()
        rule.onNodeWithText("Hésitation").assertDoesNotExist()
    }

    @Test
    fun pose_en_cours_distance_parcourue() {
        val recording = ActiveRecording("t1", "Rex", TrackSource.PHONE, Instant.now().epochSecond - 300, RecordingKind.TRAIL)
        val waiting = mutableStateOf(UiState(ready = true, recording = recording))
        rule.setContent { MaterialTheme { TrackScreen(waiting.value, TrackActions()) } }
        rule.onNodeWithText("En attente du GPS du téléphone…").assertIsDisplayed()
        rule.onNodeWithText("Pose de la piste", substring = true).assertTextContains("0 m", substring = true)

        // 0,001° de latitude ≈ 111 m : 2 pas de 111 m, puis plus d'un kilomètre.
        val track = (0..2).map { Position(it * 60L, 48.0 + it * 0.001, 2.0) }
        waiting.value = waiting.value.copy(recordingTrack = track, recordingPoints = track.size)
        rule.onNodeWithText("Pose de la piste", substring = true).assertTextContains("222 m", substring = true)
        rule.onNodeWithText("Parcours du traceur au GPS du téléphone · 3 positions").assertIsDisplayed()

        val long = (0..10).map { Position(it * 60L, 48.0 + it * 0.001, 2.0) }
        waiting.value = waiting.value.copy(recordingTrack = long, recordingPoints = long.size)
        rule.onNodeWithText("Pose de la piste", substring = true).assertTextContains("1,11 km", substring = true)
    }

    @Test
    fun autre_note_et_note_libre() {
        val picked = mutableListOf<Pair<NoteType, String?>>()
        rule.setContent { MaterialTheme { AllNotesDialog(onPick = { t, c -> picked += t to c }, onDismiss = {}) } }

        rule.onNodeWithText("L'environnement").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Vent").performScrollTo().performClick()
        rule.onNodeWithText("Ajouter la note libre").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("free-note").performScrollTo().performTextInput("Biche croisée")
        rule.onNodeWithText("Ajouter la note libre").performScrollTo().performClick()

        assertEquals(listOf(NoteType.WIND to null, NoteType.FREE to "Biche croisée"), picked)
    }

    @Test
    fun notes_d_une_recherche_completees_puis_supprimees() {
        val updated = mutableListOf<SearchNote>()
        val deleted = mutableListOf<SearchNote>()
        val dog = listOf(Position(1_000, 48.0, 2.0), Position(1_300, 48.001, 2.0))
        val notes = listOf(
            SearchNote("n1", 1_100, NoteType.HESITATION, lat = 48.0, lon = 2.0),
            SearchNote("n2", 1_200, NoteType.FREE, comment = "Biche croisée", lat = 48.001, lon = 2.0),
        )
        val session = TrailSession("s1", "Rex", TrackSource.PHONE, 1_000, 1_300, listOf(dog), notes = notes)
        val state = UiState(ready = true, openedSession = session, segments = session.segments, stats = TrackStats.of(session.segments), notes = notes)
        rule.setContent {
            MaterialTheme { TrackScreen(state, TrackActions(onUpdateNote = { updated += it }, onDeleteNote = { deleted += it })) }
        }

        rule.onNodeWithTag("notes-button").assertIsDisplayed().performClick()
        rule.onNodeWithText("Notes de la recherche").assertIsDisplayed()
        rule.onNodeWithText("Biche croisée").assertIsDisplayed()
        rule.onNodeWithText("Hésitation").performClick()
        rule.onNodeWithText("Le chien · à 1 min 40s du départ").assertIsDisplayed()
        rule.onNodeWithTag("note-comment").performTextInput("au carrefour")
        rule.onNodeWithText("Enregistrer").performClick()
        assertEquals("au carrefour", updated.single().comment)

        rule.onNodeWithTag("notes-button").performClick()
        rule.onNodeWithText("Biche croisée").performClick()
        rule.onNodeWithText("Supprimer la note").performClick()
        rule.onNodeWithText("Supprimer définitivement").performClick()
        assertEquals("n2", deleted.single().id)
    }

    @Test
    fun parametres_notes_rapides_six_au_maximum() {
        val changes = mutableListOf<List<NoteType>>()
        rule.setContent { MaterialTheme { SettingsScreen(UiState(ready = true), SettingsActions(onQuickNotes = { changes += it }), SettingsPage.NOTES) } }

        rule.onNodeWithText("Boutons affichés pendant la recherche (6/6)", substring = true).assertExists()
        rule.onNodeWithTag("quick-WIND").assertIsNotEnabled() // déjà 6 notes rapides
        rule.onNodeWithTag("quick-HESITATION").performScrollTo().performClick() // décochée

        assertEquals(DEFAULT_QUICK_NOTES - NoteType.HESITATION, changes.single())
    }

    // --- Piste du traceur ---

    @Test
    fun accueil_poser_une_piste() {
        var lays = 0
        rule.setContent { MaterialTheme { TrackScreen(UiState(ready = true), TrackActions(onLayTrail = { lays++ })) } }

        rule.onNodeWithTag("lay").assertIsEnabled().performClick()

        assertEquals(1, lays)
    }

    @Test
    fun pose_de_piste_en_cours() {
        var stops = 0
        val recording = ActiveRecording("t1", "Rex", TrackSource.PHONE, Instant.now().epochSecond - 30, RecordingKind.TRAIL)
        rule.setContent {
            MaterialTheme { TrackScreen(UiState(ready = true, recording = recording, recordingPoints = 12), TrackActions(onStopRecording = { stops++ })) }
        }

        rule.onNodeWithText("Pose de la piste", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Parcours du traceur au GPS du téléphone · 12 positions").assertIsDisplayed()
        rule.onNodeWithTag("lay").assertDoesNotExist()
        rule.onNodeWithText("Terminer la pose").performClick()

        assertEquals(1, stops)
    }

    private val laidTrail = LaidTrail(
        "t1", 1_794_830_000, 1_794_830_300,
        (0..30).map { Position(1_794_830_000L + it * 10, 48.4254 + it * 0.0001, 2.6402) },
    )

    @Test
    fun recherche_en_cours_sur_une_piste_posee() {
        val now = Instant.now().epochSecond
        val trail = laidTrail.copy(endedAt = now - 1800)
        val recording = ActiveRecording("r1", "Rex", TrackSource.PHONE, now, RecordingKind.SEARCH, trailId = trail.id)
        rule.setContent { MaterialTheme { TrackScreen(UiState(ready = true, recording = recording, trails = listOf(trail)), TrackActions()) } }

        rule.onNodeWithText("Sur la piste posée il y a 30 min (affichée à l'arrivée)").assertIsDisplayed()
    }

    @Test
    fun piste_posee_affichee_puis_recherche_lancee() {
        var started: LaidTrail? = null
        val state = UiState(ready = true, openedTrail = laidTrail, trailLine = laidTrail.points)
        rule.setContent { MaterialTheme { TrackScreen(state, TrackActions(onStartSearchOnTrail = { started = it })) } }

        rule.onNodeWithText("Piste posée").assertIsDisplayed()
        rule.onNodeWithTag("trail-stats").assertIsDisplayed()
        rule.onNodeWithText("0,33 km").assertIsDisplayed()
        rule.onNodeWithText("Lancer la recherche").performClick()

        assertEquals(laidTrail, started)
    }

    @Test
    fun rapport_pdf_depuis_une_piste_ou_une_recherche_affichee() {
        var exports = 0
        val state = mutableStateOf(UiState(ready = true, openedTrail = laidTrail, trailLine = laidTrail.points))
        rule.setContent { MaterialTheme { TrackScreen(state.value, TrackActions(onExportPdf = { exports++ })) } }

        rule.onNodeWithText("PDF").performClick()
        assertEquals(1, exports)

        // Pendant la création : « PDF… », un nouvel appui ne relance rien.
        state.value = state.value.copy(exporting = true)
        rule.onNodeWithText("PDF…").performClick()
        assertEquals(1, exports)
    }

    @Test
    fun choix_de_la_piste_au_depart_de_la_recherche() {
        val chosen = mutableListOf<LaidTrail?>()
        rule.setContent {
            MaterialTheme { TrailChoiceDialog(listOf(laidTrail), now = laidTrail.endedAt + 2700, onChoose = { chosen += it }, onDismiss = {}) }
        }

        rule.onNodeWithText("La piste ne sera affichée qu'à l'arrivée du chien.").assertIsDisplayed()
        rule.onNodeWithText("Posée il y a 45 min").performClick()
        rule.onNodeWithText("Sans piste").performClick()

        assertEquals(listOf(laidTrail, null), chosen)
    }

    @Test
    fun recherche_comparee_a_la_piste() {
        val dog = laidTrail.points.map { it.copy(lon = it.lon + 0.0001) } // ~7 m à l'est de la piste
        val session = TrailSession(
            "s1", "Rex", TrackSource.PHONE, laidTrail.endedAt + 2700, laidTrail.endedAt + 3300, listOf(dog),
            trailId = laidTrail.id, trail = laidTrail.points, trailEndedAt = laidTrail.endedAt,
        )
        val state = UiState(ready = true, openedSession = session, segments = session.segments, stats = TrackStats.of(session.segments), trailLine = session.trail)
        rule.setContent { MaterialTheme { TrackScreen(state, TrackActions()) } }

        rule.onNodeWithText("piste de 45 min", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("deviation").assertIsDisplayed()
        rule.onAllNodesWithText("7 m").assertCountEquals(2) // écart moyen et écart max
        rule.onAllNodesWithText("100 %").assertCountEquals(2) // sur la piste et piste couverte
        rule.onNodeWithText("Atteinte").assertIsDisplayed()
    }

    @Test
    fun mes_pistes_avec_pistes_posees() {
        var opened: LaidTrail? = null
        val deleted = mutableListOf<String>()
        val searched = TrailSession("s1", "Rex", TrackSource.PHONE, 1_794_840_000, 1_794_840_600, trailId = "autre")
        val other = laidTrail.copy(id = "autre", startedAt = 1_794_820_000, endedAt = 1_794_820_300)
        rule.setContent {
            MaterialTheme {
                SessionsScreen(listOf(searched), listOf(laidTrail, other), emptyList(), SessionsActions(onOpenTrail = { opened = it }, onDeleteTrail = { deleted += it.id }))
            }
        }

        rule.onNodeWithText("Pistes posées").assertIsDisplayed()
        rule.onNodeWithText("Recherches").assertIsDisplayed()
        rule.onNodeWithText("Pas encore travaillée").assertIsDisplayed()
        rule.onNodeWithText("Piste travaillée").assertIsDisplayed()
        rule.onNodeWithTag("trail-t1").performClick()
        rule.onAllNodesWithText("Supprimer")[0].performClick()
        rule.onNodeWithText("Supprimer définitivement").performClick()

        assertEquals(laidTrail, opened)
        assertEquals(listOf("t1"), deleted)
    }

    // --- Accueil connecté ---

    @Test
    fun trace_affiche_le_bilan_et_alerte_sans_mode_live() {
        val segments = listOf(
            listOf(
                Position(0, 44.000, 2.0, uncertainty = 2.0, sensor = "GPS"),
                Position(150, 44.001, 2.0, uncertainty = 2.0, sensor = "GPS"),
                Position(450, 44.002, 2.0, uncertainty = 2.0, sensor = "GPS"),
            ),
        )
        val state = connected.copy(segments = segments, stats = TrackStats.of(segments), loadedOnce = true)
        rule.setContent { MaterialTheme { TrackScreen(state, TrackActions()) } }

        rule.onNodeWithText("Rex ▾").assertIsDisplayed()
        rule.onNodeWithTag("welcome").assertDoesNotExist()
        rule.onNodeWithText("7 min 30s").assertIsDisplayed() // durée
        rule.onNodeWithText("5 min").assertIsDisplayed() // intervalle médian
        rule.onNodeWithText("0,22 km").assertIsDisplayed()
        rule.onNodeWithText("Points trop espacés", substring = true).assertIsDisplayed()
    }

    @Test
    fun trace_en_mode_live_sans_alerte() {
        val segments = listOf((0L..10L).map { Position(it * 3, 44.0 + it * 0.0001, 2.0, uncertainty = 3.0, sensor = "GPS") })
        val state = connected.copy(segments = segments, stats = TrackStats.of(segments))
        rule.setContent { MaterialTheme { TrackScreen(state, TrackActions()) } }

        rule.onNodeWithText("3 s").assertIsDisplayed()
        rule.onNodeWithText("Points trop espacés", substring = true).assertDoesNotExist()
    }

    @Test
    fun boutons_charger_et_live() {
        var loads = 0
        var live: Boolean? = null
        rule.setContent { MaterialTheme { TrackScreen(connected, TrackActions(onLoad = { loads++ }, onLive = { live = it })) } }

        rule.onNodeWithTag("load").assertDoesNotExist() // recherche par période repliée dans le menu
        openPeriodSearch()
        rule.onNodeWithTag("load").performClick()
        rule.onNodeWithText("Activer LIVE").performClick()

        assertEquals(1, loads)
        assertEquals(true, live)
    }

    @Test
    fun periode_invalide_bloque_le_chargement() {
        val range = TimeRange(LocalDate.of(2026, 11, 16), LocalTime.of(14, 0), LocalTime.of(13, 0))
        rule.setContent { MaterialTheme { TrackScreen(connected.copy(range = range), TrackActions()) } }

        openPeriodSearch()
        rule.onNodeWithText("L'heure de fin doit être après le début.").assertIsDisplayed()
        rule.onNodeWithTag("load").assertIsNotEnabled()
    }

    @Test
    fun message_cliquable_pour_le_fermer() {
        var dismissed = false
        rule.setContent {
            MaterialTheme { TrackScreen(connected.copy(message = "Mode LIVE arrêté."), TrackActions(onDismissMessage = { dismissed = true })) }
        }
        rule.onNodeWithText("Mode LIVE arrêté.").performClick()
        assertTrue(dismissed)
    }

    private fun openPeriodSearch() {
        rule.onNodeWithContentDescription("Menu").performClick()
        rule.onNodeWithText("Trace du collier sur une période").performClick()
    }

    // --- Enregistrement d'une piste ---

    @Test
    fun accueil_bouton_demarrer_une_piste() {
        var starts = 0
        rule.setContent { MaterialTheme { TrackScreen(UiState(ready = true), TrackActions(onStartRecording = { starts++ })) } }

        rule.onNodeWithTag("record").assertIsEnabled().performClick()

        assertEquals(1, starts)
    }

    @Test
    fun piste_en_cours_au_gps_du_telephone() {
        val stops = mutableListOf<NoteType?>()
        val recording = ActiveRecording("r1", "Rex", TrackSource.PHONE, Instant.now().epochSecond - 125)
        val state = UiState(ready = true, recording = recording, recordingPoints = 42)
        rule.setContent { MaterialTheme { TrackScreen(state, TrackActions(onStopRecording = { stops += it })) } }

        rule.onNodeWithText("Recherche de Rex", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Sans piste enregistrée").assertIsDisplayed()
        rule.onNodeWithText("GPS du téléphone · 42 positions").assertIsDisplayed()
        rule.onNodeWithTag("record").assertDoesNotExist()
        rule.onNodeWithTag("welcome").assertDoesNotExist()

        // Terminer demande l'issue de la recherche ; « Continuer » ne termine rien.
        rule.onNodeWithText("Terminer la recherche").performClick()
        rule.onNodeWithText("Fin de la recherche").assertIsDisplayed()
        rule.onNodeWithText("Continuer la recherche").performClick()
        assertTrue(stops.isEmpty())

        rule.onNodeWithText("Terminer la recherche").performClick()
        rule.onNodeWithText("Trouvé").performClick()
        rule.onNodeWithText("Terminer la recherche").performClick()
        rule.onNodeWithText("Terminer sans préciser").performClick()

        assertEquals(listOf(NoteType.FOUND, null), stops)
    }

    @Test
    fun issue_deja_notee_terminer_directement() {
        val stops = mutableListOf<NoteType?>()
        val start = Instant.now().epochSecond - 60
        val recording = ActiveRecording("r1", "Rex", TrackSource.PHONE, start)
        val state = UiState(ready = true, recording = recording, recordingNotes = listOf(SearchNote("n", start + 50, NoteType.ABANDON)))
        rule.setContent { MaterialTheme { TrackScreen(state, TrackActions(onStopRecording = { stops += it })) } }

        rule.onNodeWithText("Terminer la recherche").performClick()

        rule.onNodeWithText("Fin de la recherche").assertDoesNotExist()
        assertEquals(listOf<NoteType?>(null), stops)
    }

    @Test
    fun piste_en_cours_au_collier() {
        val recording = ActiveRecording("r1", "Rex", TrackSource.TRACTIVE, Instant.now().epochSecond)
        rule.setContent { MaterialTheme { TrackScreen(connected.copy(recording = recording), TrackActions()) } }

        rule.onNodeWithText("Collier Tractive (mode LIVE)", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Activer LIVE").assertDoesNotExist()
    }

    @Test
    fun choix_de_la_source_propose_le_collier() {
        val chosen = mutableListOf<TrackSource>()
        rule.setContent { MaterialTheme { SourceChoiceDialog(onChoose = { chosen += it }, onDismiss = {}) } }

        rule.onNodeWithText("Ce choix sera gardé", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Collier Tractive").performClick()
        rule.onNodeWithText("GPS du téléphone").performClick()

        assertEquals(listOf(TrackSource.TRACTIVE, TrackSource.PHONE), chosen)
    }

    @Test
    fun piste_enregistree_affichee_avec_bandeau() {
        var closes = 0
        val session = TrailSession(
            "s1", "Rex", TrackSource.PHONE, 1_794_834_120, 1_794_834_720,
            listOf(listOf(Position(1_794_834_120, 44.0, 2.0, sensor = "PHONE_GPS"), Position(1_794_834_123, 44.0001, 2.0, sensor = "PHONE_GPS"))),
        )
        val state = UiState(ready = true, openedSession = session, segments = session.segments, stats = TrackStats.of(session.segments))
        rule.setContent { MaterialTheme { TrackScreen(state, TrackActions(onCloseSession = { closes++ })) } }

        rule.onNodeWithText("Recherche du", substring = true).assertIsDisplayed()
        rule.onNodeWithText("GPS du téléphone", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("stats").assertIsDisplayed()
        rule.onNodeWithText("Fermer").performClick()

        assertEquals(1, closes)
    }

    @Test
    fun menu_mes_pistes() {
        var opens = 0
        rule.setContent { MaterialTheme { TrackScreen(UiState(ready = true), TrackActions(onOpenSessions = { opens++ })) } }

        rule.onNodeWithContentDescription("Menu").performClick()
        rule.onNodeWithText("Mes pistes").performClick()

        assertEquals(1, opens)
    }

    // --- Mes pistes ---

    private val savedSessions = listOf(
        TrailSession("s2", "Rex", TrackSource.TRACTIVE, 1_794_900_000, 1_794_900_900),
        TrailSession(
            "s1", "Rex", TrackSource.PHONE, 1_794_834_120, 1_794_834_720,
            listOf(listOf(Position(0, 44.0, 2.0), Position(3, 44.001, 2.0))),
        ),
    )

    @Test
    fun mes_pistes_liste_et_ouverture() {
        var opened: TrailSession? = null
        rule.setContent { MaterialTheme { SessionsScreen(savedSessions, emptyList(), emptyList(), SessionsActions(onOpen = { opened = it })) } }

        rule.onNodeWithText("Trace pas encore récupérée · 15 min").assertIsDisplayed()
        rule.onNodeWithText("0,11 km · 10 min").assertIsDisplayed()
        rule.onNodeWithTag("session-s1").performClick()

        assertEquals("s1", opened?.id)
    }

    @Test
    fun mes_pistes_suppression_avec_confirmation() {
        val deleted = mutableListOf<String>()
        rule.setContent { MaterialTheme { SessionsScreen(savedSessions, emptyList(), emptyList(), SessionsActions(onDelete = { deleted += it.id })) } }

        rule.onAllNodesWithText("Supprimer")[1].performClick()
        rule.onNodeWithText("Annuler").performClick()
        assertTrue(deleted.isEmpty())

        rule.onAllNodesWithText("Supprimer")[1].performClick()
        rule.onNodeWithText("Supprimer définitivement").performClick()
        assertEquals(listOf("s1"), deleted)
    }

    @Test
    fun mes_pistes_vide() {
        rule.setContent { MaterialTheme { SessionsScreen(emptyList(), emptyList(), emptyList(), SessionsActions()) } }
        rule.onNodeWithText("Rien d'enregistré", substring = true).assertIsDisplayed()
    }

    // --- Paramètres ---

    @Test
    fun parametres_source_de_la_trace() {
        val chosen = mutableListOf<TrackSource>()
        rule.setContent { MaterialTheme { SettingsScreen(connected, SettingsActions(onTrackSource = { chosen += it }), SettingsPage.COLLARS) } }

        rule.onNodeWithText("Trace du chien").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("GPS du téléphone").performScrollTo().performClick()
        rule.onNodeWithText("Collier Tractive").performScrollTo().performClick()

        assertEquals(listOf(TrackSource.PHONE, TrackSource.TRACTIVE), chosen)
    }

    @Test
    fun parametres_collier_indisponible_sans_compte() {
        val chosen = mutableListOf<TrackSource>()
        rule.setContent { MaterialTheme { SettingsScreen(UiState(ready = true), SettingsActions(onTrackSource = { chosen += it }), SettingsPage.COLLARS) } }

        rule.onNodeWithText("Connecte d'abord un compte Tractive ci-dessus.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Collier Tractive").performScrollTo().performClick()

        assertTrue(chosen.isEmpty())
    }

    // --- Paramètres ---

    @Test
    fun parametres_connexion_activee_une_fois_les_champs_remplis() {
        var sent: Pair<String, String>? = null
        rule.setContent { MaterialTheme { SettingsScreen(UiState(ready = true), SettingsActions(onLogin = { e, p -> sent = e to p }), SettingsPage.COLLARS) } }

        rule.onNodeWithText("Se connecter").assertIsNotEnabled()
        rule.onNodeWithTag("email").performTextInput("moi@example.com")
        rule.onNodeWithText("Se connecter").assertIsNotEnabled()
        rule.onNodeWithTag("password").performTextInput("secret")
        rule.onNodeWithText("Se connecter").assertIsEnabled().performClick()

        assertEquals("moi@example.com" to "secret", sent)
    }

    @Test
    fun parametres_affichent_le_message_d_erreur() {
        rule.setContent {
            MaterialTheme { SettingsScreen(UiState(ready = true, message = "Email ou mot de passe incorrect."), SettingsActions(), SettingsPage.COLLARS) }
        }
        rule.onNodeWithText("Email ou mot de passe incorrect.").assertIsDisplayed()
    }

    // --- Chiens ---

    @Test
    fun parametres_liste_des_chiens_et_choix_du_chien_qui_cherche() {
        val selected = mutableListOf<Dog>()
        rule.setContent { MaterialTheme { SettingsScreen(connected, SettingsActions(onSelectDog = { selected += it }), SettingsPage.DOGS) } }

        rule.onNodeWithText("Le chien coché est celui qui cherche", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Porte un collier Tractive").assertIsDisplayed()
        rule.onNodeWithText("Sans collier : trace au GPS du téléphone").assertIsDisplayed()
        rule.onNodeWithText("Nala").performClick()

        assertEquals(listOf(nala), selected)
    }

    @Test
    fun parametres_ajouter_renommer_retirer_un_chien() {
        val added = mutableListOf<String>()
        val renamed = mutableListOf<Pair<Dog, String>>()
        val deleted = mutableListOf<Dog>()
        rule.setContent {
            MaterialTheme {
                SettingsScreen(
                    connected,
                    SettingsActions(onAddDog = { added += it }, onRenameDog = { d, n -> renamed += d to n }, onDeleteDog = { deleted += it }),
                    SettingsPage.DOGS,
                )
            }
        }

        rule.onNodeWithTag("add-dog").performClick()
        rule.onNodeWithText("Ajouter").assertIsNotEnabled()
        rule.onNodeWithTag("dog-name").performTextInput("Gaïa")
        rule.onNodeWithText("Ajouter").performClick()

        // Sans équipe, pas de partage dans le menu.
        rule.onNodeWithContentDescription("Actions pour Nala").performClick()
        rule.onNodeWithText("Partager avec…").assertDoesNotExist()
        rule.onNodeWithText("Renommer").performClick()
        rule.onNodeWithTag("dog-name").performTextInput(" la belle")
        rule.onNodeWithText("Enregistrer").performClick()

        // Retirer demande confirmation ; un chien Tractive ne sera pas réimporté.
        rule.onNodeWithContentDescription("Actions pour Rex").performClick()
        rule.onNodeWithText("Retirer").performClick()
        rule.onNodeWithText("Il ne sera pas réimporté", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Annuler").performClick()
        rule.onNodeWithContentDescription("Actions pour Nala").performClick()
        rule.onNodeWithText("Retirer").performClick()
        rule.onNodeWithText("Retirer Nala ?").assertIsDisplayed()
        rule.onNodeWithText("Retirer").performClick()

        assertEquals(listOf("Gaïa"), added)
        assertEquals(listOf(nala to "Nala la belle"), renamed)
        assertEquals(listOf(nala), deleted)
    }

    @Test
    fun accueil_choix_du_chien_qui_cherche() {
        val selected = mutableListOf<Dog>()
        var settings = 0
        rule.setContent {
            MaterialTheme { TrackScreen(connected, TrackActions(onSelectDog = { selected += it }, onOpenSettings = { settings++ })) }
        }

        rule.onNodeWithTag("welcome").assertDoesNotExist()
        rule.onNodeWithText("Rex ▾").performClick()
        rule.onNodeWithText("Nala").performClick()
        rule.onNodeWithText("Rex ▾").performClick()
        rule.onNodeWithText("Gérer les chiens…").performClick()

        assertEquals(listOf(nala), selected)
        assertEquals(1, settings)
    }

    @Test
    fun accueil_chien_sans_collier_pas_de_live() {
        rule.setContent { MaterialTheme { TrackScreen(connected.copy(activeDogId = nala.id), TrackActions()) } }

        rule.onNodeWithText("Nala ▾").assertIsDisplayed()
        rule.onNodeWithText("Activer LIVE").assertDoesNotExist()
    }

    @Test
    fun mes_pistes_filtre_par_chien() {
        val sessions = listOf(
            TrailSession("s1", "Rex", TrackSource.PHONE, 1_794_900_000, 1_794_900_600, dogId = rex.id),
            TrailSession("s2", "Nala", TrackSource.PHONE, 1_794_800_000, 1_794_800_600, dogId = nala.id),
            TrailSession("s3", "Rex", TrackSource.PHONE, 1_794_700_000, 1_794_700_600), // d'avant les chiens multiples
        )
        rule.setContent { MaterialTheme { SessionsScreen(sessions, emptyList(), listOf(rex, nala), SessionsActions()) } }

        rule.onNodeWithTag("session-s2").assertIsDisplayed()
        rule.onNodeWithText("Rex").performClick()
        rule.onNodeWithTag("session-s1").assertIsDisplayed()
        rule.onNodeWithTag("session-s3").assertIsDisplayed()
        rule.onNodeWithTag("session-s2").assertDoesNotExist()
        rule.onNodeWithText("Tous les chiens").performClick()
        rule.onNodeWithTag("session-s2").assertIsDisplayed()
    }

    @Test
    fun mes_pistes_un_seul_chien_sans_filtre() {
        rule.setContent { MaterialTheme { SessionsScreen(savedSessions, emptyList(), listOf(rex), SessionsActions()) } }
        rule.onNodeWithTag("dog-filter").assertDoesNotExist()
    }

    @Test
    fun parametres_connecte_deconnexion_et_retour() {
        var logout = 0
        var back = 0
        var syncs = 0
        rule.setContent {
            MaterialTheme { SettingsScreen(connected, SettingsActions(onLogout = { logout++ }, onBack = { back++ }, onSyncDogs = { syncs++ }), SettingsPage.COLLARS) }
        }

        rule.onNodeWithText("Compte Tractive connecté").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Avec collier : Rex").assertIsDisplayed()
        rule.onNodeWithText("Actualiser les chiens").performClick()
        assertEquals(1, syncs)
        rule.onNodeWithText("Se déconnecter").performClick()
        // Retour : d'abord au menu des paramètres, puis à l'accueil.
        rule.onNodeWithContentDescription("Retour").performClick()
        rule.onNodeWithText("Paramètres").assertIsDisplayed()
        assertEquals(0, back)
        rule.onNodeWithContentDescription("Retour").performClick()

        assertEquals(1, logout)
        assertEquals(1, back)
    }

    @Test
    fun parametres_menu_court_qui_resume_chaque_rubrique() {
        rule.setContent { MaterialTheme { SettingsScreen(connected.copy(cloud = CloudState(available = true)), SettingsActions()) } }

        // Une ligne par rubrique, avec son état ; aucun réglage affiché tant qu'on n'ouvre pas la rubrique.
        rule.onNodeWithText("Rex (actif), Nala").assertIsDisplayed()
        rule.onNodeWithText("Tractive connecté · trace par le collier").assertIsDisplayed()
        rule.onNodeWithText("Désactivée").assertIsDisplayed()
        rule.onNodeWithText("6 boutons pendant la recherche").assertIsDisplayed()
        rule.onNodeWithTag("settings-about").assertIsDisplayed()
        rule.onNodeWithTag("tractive-account").assertDoesNotExist()

        rule.onNodeWithTag("settings-collars").performClick()
        rule.onNodeWithText("Colliers GPS").assertIsDisplayed()
        rule.onNodeWithTag("tractive-account").assertIsDisplayed()
        rule.onNodeWithText("Trace du chien").performScrollTo().assertIsDisplayed()

        rule.onNodeWithContentDescription("Retour").performClick()
        rule.onNodeWithTag("settings-about").performClick()
        rule.onNodeWithText("Politique de confidentialité").assertIsDisplayed()
    }

    @Test
    fun parametres_equipes_demandent_la_sauvegarde_en_ligne() {
        rule.setContent { MaterialTheme { SettingsScreen(UiState(ready = true, cloud = CloudState(available = true)), SettingsActions(), SettingsPage.TEAMS) } }

        rule.onNodeWithText("activez-la d'abord", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Sauvegarde en ligne").performClick()
        rule.onNodeWithText("Pistes, recherches et chiens sont copiés", substring = true).assertIsDisplayed()
    }
}
