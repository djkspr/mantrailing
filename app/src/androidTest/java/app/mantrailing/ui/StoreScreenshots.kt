package app.mantrailing.ui

import android.app.Activity
import android.os.ParcelFileDescriptor
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mantrailing.MantrailingTheme
import app.mantrailing.UiState
import app.mantrailing.data.StoredSession
import app.mantrailing.demo.DemoSession
import app.mantrailing.session.TrackSource
import app.mantrailing.session.TrailSession
import app.mantrailing.track.TrackStats
import app.mantrailing.tractive.Pet
import app.mantrailing.tractive.Session
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Captures d'écran pour la fiche Play Store, avec une séance fictive (forêt de Fontainebleau).
 * Ignoré par défaut ; lancé par store/screenshots.ps1 (argument storeScreenshots=true).
 */
@RunWith(AndroidJUnit4::class)
class StoreScreenshots {
    @get:Rule
    val rule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun captures() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("storeScreenshots") == "true")

        // Séance de démonstration présentée comme une vraie recherche sur une piste posée (sans bandeau « démo »).
        val segments = DemoSession.segments
        val session = TrailSession(
            "demo", DemoSession.DOG_NAME, TrackSource.PHONE, segments.first().first().time, segments.last().last().time, segments,
            trailId = "demo-trail", trail = DemoSession.trail, trailEndedAt = DemoSession.trailEndedAt,
        )
        val state = UiState(
            ready = true,
            stored = StoredSession(Session("demo", "demo", 0), Pet("demo", DemoSession.DOG_NAME, "DEMO")),
            openedSession = session,
            segments = segments,
            stats = TrackStats.of(segments),
            trailLine = session.trail,
            loadedOnce = true,
        )
        rule.setContent {
            // Icônes de barre d'état foncées sur fond clair, comme dans l'appli (edge-to-edge).
            val view = LocalView.current
            SideEffect {
                val window = (view.context as Activity).window
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
            }
            MantrailingTheme { Surface { TrackScreen(state, TrackActions()) } }
        }

        waitForTiles()
        capture("1-trace-plan-ign")
        rule.onNodeWithText("Plan IGN").performClick()
        waitForTiles()
        capture("2-trace-photos-ign")
    }

    private fun waitForTiles() {
        rule.waitForIdle()
        Thread.sleep(12_000) // MapLibre charge les tuiles hors de Compose : pas d'état « idle » à attendre.
    }

    /** Capture prise par le shell, dans /data/local/tmp : elle survit à la désinstallation de l'appli par Gradle. */
    private fun capture(name: String) {
        val pfd = instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/store-$name.png")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() } // attend la fin de la commande
    }
}
