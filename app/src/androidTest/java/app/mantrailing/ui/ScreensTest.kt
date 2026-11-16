package app.mantrailing.ui

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
import app.mantrailing.UiState
import app.mantrailing.data.StoredSession
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
import app.mantrailing.tractive.Pet
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

    private val pet = Pet("p1", "Rex", "TRK1")
    private val session = Session("u1", "tok", 0)
    private val connected = UiState(ready = true, stored = StoredSession(session, pet))

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
        rule.onNodeWithText("Connecter un collier Tractive").performClick()
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
    fun pas_de_notes_pendant_la_pose_de_piste() {
        val recording = ActiveRecording("t1", "Rex", TrackSource.PHONE, Instant.now().epochSecond, RecordingKind.TRAIL)
        rule.setContent { MaterialTheme { TrackScreen(UiState(ready = true, recording = recording), TrackActions()) } }
        rule.onNodeWithTag("quick-notes").assertDoesNotExist()
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
        rule.setContent { MaterialTheme { SettingsScreen(UiState(ready = true), SettingsActions(onQuickNotes = { changes += it })) } }

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
                SessionsScreen(listOf(searched), listOf(laidTrail, other), SessionsActions(onOpenTrail = { opened = it }, onDeleteTrail = { deleted += it.id }))
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

        rule.onNodeWithText("Rex").assertIsDisplayed()
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

        rule.onNodeWithText("Recherche en cours", substring = true).assertIsDisplayed()
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
        rule.setContent { MaterialTheme { SessionsScreen(savedSessions, emptyList(), SessionsActions(onOpen = { opened = it })) } }

        rule.onNodeWithText("Trace pas encore récupérée · 15 min").assertIsDisplayed()
        rule.onNodeWithText("0,11 km · 10 min").assertIsDisplayed()
        rule.onNodeWithTag("session-s1").performClick()

        assertEquals("s1", opened?.id)
    }

    @Test
    fun mes_pistes_suppression_avec_confirmation() {
        val deleted = mutableListOf<String>()
        rule.setContent { MaterialTheme { SessionsScreen(savedSessions, emptyList(), SessionsActions(onDelete = { deleted += it.id })) } }

        rule.onAllNodesWithText("Supprimer")[1].performClick()
        rule.onNodeWithText("Annuler").performClick()
        assertTrue(deleted.isEmpty())

        rule.onAllNodesWithText("Supprimer")[1].performClick()
        rule.onNodeWithText("Supprimer définitivement").performClick()
        assertEquals(listOf("s1"), deleted)
    }

    @Test
    fun mes_pistes_vide() {
        rule.setContent { MaterialTheme { SessionsScreen(emptyList(), emptyList(), SessionsActions()) } }
        rule.onNodeWithText("Rien d'enregistré", substring = true).assertIsDisplayed()
    }

    // --- Paramètres ---

    @Test
    fun parametres_source_de_la_trace() {
        val chosen = mutableListOf<TrackSource>()
        rule.setContent { MaterialTheme { SettingsScreen(connected, SettingsActions(onTrackSource = { chosen += it })) } }

        rule.onNodeWithText("Trace du chien").assertIsDisplayed()
        rule.onNodeWithText("GPS du téléphone").performClick()
        rule.onNodeWithText("Collier Tractive").performClick()

        assertEquals(listOf(TrackSource.PHONE, TrackSource.TRACTIVE), chosen)
    }

    @Test
    fun parametres_collier_indisponible_sans_compte() {
        val chosen = mutableListOf<TrackSource>()
        rule.setContent { MaterialTheme { SettingsScreen(UiState(ready = true), SettingsActions(onTrackSource = { chosen += it })) } }

        rule.onNodeWithText("Connecte d'abord un collier ci-dessus.").assertIsDisplayed()
        rule.onNodeWithText("Collier Tractive").performClick()

        assertTrue(chosen.isEmpty())
    }

    // --- Paramètres ---

    @Test
    fun parametres_connexion_activee_une_fois_les_champs_remplis() {
        var sent: Pair<String, String>? = null
        rule.setContent { MaterialTheme { SettingsScreen(UiState(ready = true), SettingsActions(onLogin = { e, p -> sent = e to p })) } }

        rule.onNodeWithText("Politique de confidentialité").performScrollTo().assertIsDisplayed()
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
            MaterialTheme { SettingsScreen(UiState(ready = true, message = "Email ou mot de passe incorrect."), SettingsActions()) }
        }
        rule.onNodeWithText("Email ou mot de passe incorrect.").assertIsDisplayed()
    }

    @Test
    fun parametres_choix_du_chien() {
        var picked: Pet? = null
        val state = UiState(ready = true, stored = StoredSession(session, null), pets = listOf(pet, Pet("p2", "Mirza", null)))
        rule.setContent { MaterialTheme { SettingsScreen(state, SettingsActions(onPickPet = { picked = it })) } }

        rule.onNodeWithText("Pas de collier associé").assertIsDisplayed()
        rule.onNodeWithText("Rex").performClick()

        assertEquals(pet, picked)
    }

    @Test
    fun parametres_connecte_deconnexion_et_retour() {
        var logout = 0
        var back = 0
        rule.setContent { MaterialTheme { SettingsScreen(connected, SettingsActions(onLogout = { logout++ }, onBack = { back++ })) } }

        rule.onNodeWithText("Connecté : Rex").assertIsDisplayed()
        rule.onNodeWithText("Se déconnecter").performClick()
        rule.onNodeWithContentDescription("Retour").performClick()

        assertEquals(1, logout)
        assertEquals(1, back)
    }
}
