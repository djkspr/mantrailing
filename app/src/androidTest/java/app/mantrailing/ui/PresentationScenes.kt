package app.mantrailing.ui

import android.app.Activity
import android.os.ParcelFileDescriptor
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mantrailing.CloudState
import app.mantrailing.Invite
import app.mantrailing.MantrailingTheme
import app.mantrailing.UiState
import app.mantrailing.demo.DemoSession
import app.mantrailing.session.Dog
import app.mantrailing.session.SessionSummary
import app.mantrailing.session.TrackSource
import app.mantrailing.session.TrailSession
import app.mantrailing.sync.Team
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Séquences de la vidéo de présentation que l'émulateur ne peut pas jouer pour de vrai (équipes, sauvegarde
 * en ligne), avec les vrais écrans et des données fictives. Ignoré par défaut ; lancé avec presentation=true,
 * pendant que l'hôte filme l'écran.
 */
@RunWith(AndroidJUnit4::class)
class PresentationScenes {
    @get:Rule
    val rule = createComposeRule()

    private val famille = Team("t-fam", "Famille", "u1", mapOf("u1" to "Moi", "u2" to "Claire"))
    private val club = Team("t-club", "Club canin du Gâtinais", "u3", mapOf("u1" to "Moi", "u3" to "Paul", "u4" to "Léa", "u5" to "Hugo"))
    private val rex = Dog("d1", "Rex")
    private val nala = Dog("d2", "Nala", spaceId = "t-fam")

    @Test
    fun equipes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("presentation") == "true")

        val day = 86_400L
        val seg = DemoSession.segments
        val start = seg.first().first().time
        fun search(id: String, dog: Dog, daysAgo: Long, spaceId: String?) = TrailSession(
            id, dog.name, TrackSource.PHONE, start - daysAgo * day, start - daysAgo * day + 1_100,
            seg.map { s -> s.map { it.copy(time = it.time - daysAgo * day) } },
            trail = DemoSession.trail, trailEndedAt = start - daysAgo * day - 2_700, dogId = dog.id, spaceId = spaceId,
        )
        var sessions by mutableStateOf(
            listOf(
                search("s1", rex, 1, null),
                search("s2", nala, 3, "t-fam"),
                search("s3", rex, 6, "t-club"),
                search("s4", rex, 13, "t-club"),
                TrailSession(
                    "s5", "Rex", TrackSource.PHONE, start - 48 * day, start - 48 * day + 900, dogId = rex.id,
                    archived = true, summary = SessionSummary(1180.0, 290, 14.0, true), spaceId = null,
                ),
            ),
        )
        var cloud by mutableStateOf(
            CloudState(
                available = true, email = "moi@exemple.fr", teams = listOf(famille, club),
                lastSyncAt = System.currentTimeMillis() - 4 * 60_000,
            ),
        )
        var screen by mutableStateOf("settings")
        var askSpace by mutableStateOf(false)

        rule.setContent {
            val view = LocalView.current
            SideEffect {
                val window = (view.context as Activity).window
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
            }
            MantrailingTheme {
                Surface {
                    val state = UiState(ready = true, dogs = listOf(rex, nala), activeDogId = rex.id, cloud = cloud, sessions = sessions)
                    when (screen) {
                        "settings" -> SettingsScreen(
                            state,
                            SettingsActions(
                                cloud = CloudActions(
                                    onKeepDays = { cloud = cloud.copy(keepDays = it) },
                                    onInvite = { cloud = cloud.copy(invite = Invite(it.name, "K7PM4XQ2")) },
                                    onDismissInvite = { cloud = cloud.copy(invite = null) },
                                ),
                            ),
                        )
                        "home" -> {
                            TrackScreen(state, TrackActions())
                            if (askSpace) {
                                SpaceChoiceDialog("Recherche pour…", cloud.teams, selected = "t-club", onChoose = { askSpace = false }, onDismiss = {})
                            }
                        }
                        else -> SessionsScreen(
                            sessions, emptyList(), listOf(rex, nala),
                            SessionsActions(onMoveSession = { s, space -> sessions = sessions.map { if (it.id == s.id) it.copy(spaceId = space) else it } }),
                            teams = cloud.teams,
                        )
                    }
                }
            }
        }

        // Film tourné par le shell, dans /data/local/tmp : il survit à la désinstallation de l'appli par Gradle.
        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation
        shell.executeShellCommand("rm -f /data/local/tmp/8b-equipes.mp4").close()
        val recording = shell.executeShellCommand("screenrecord --bit-rate 8000000 --time-limit 90 /data/local/tmp/8b-equipes.mp4")
        pause(1.5)

        // Sauvegarde en ligne : Wi-Fi seulement, ce qui reste sur le téléphone.
        pause(1.0)
        rule.onNodeWithText("Sauvegarde en ligne").performScrollTo()
        pause(3.0)
        rule.onNodeWithTag("keep-days").performScrollTo()
        rule.onNodeWithText("90 jours").performClick()
        pause(1.2)
        rule.onNodeWithText("30 jours").performClick()
        pause(2.5)

        // Équipes : membres, invitation par code.
        rule.onNodeWithTag("team-t-club").performScrollTo()
        pause(3.0)
        rule.onAllNodesWithText("Inviter")[1].performClick()
        pause(4.5)
        rule.onNodeWithText("Fermer").performClick()
        pause(1.0)

        // Au départ : pour qui ?
        screen = "home"
        pause(1.5)
        askSpace = true
        pause(4.5)
        rule.onNodeWithTag("space-t-club").performClick()
        pause(1.0)

        // Mes pistes par espace, et partage après coup.
        screen = "sessions"
        pause(2.5)
        rule.onNodeWithText("Club canin du Gâtinais").performClick()
        pause(3.0)
        rule.onNodeWithText("Perso").performClick()
        pause(2.5)
        rule.onAllNodesWithText("Partager")[0].performClick()
        pause(2.0)
        rule.onNodeWithTag("space-t-fam").performClick()
        pause(1.0)
        rule.onNodeWithText("Tout").performClick()
        pause(3.0)

        ParcelFileDescriptor.AutoCloseInputStream(shell.executeShellCommand("pkill -INT screenrecord")).use { it.readBytes() }
        ParcelFileDescriptor.AutoCloseInputStream(recording).use { it.readBytes() } // attend la fin de l'enregistrement
    }

    private fun pause(seconds: Double) {
        rule.waitForIdle()
        Thread.sleep((seconds * 1000).toLong())
    }
}
