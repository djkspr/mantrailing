package app.mantrailing.ui

import android.app.Activity
import android.os.ParcelFileDescriptor
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.mantrailing.MantrailingTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mantrailing.TimeRange
import app.mantrailing.UiState
import app.mantrailing.track.TrackStats
import app.mantrailing.tractive.Pet
import app.mantrailing.tractive.Position
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

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

        val date = LocalDate.of(2026, 11, 15)
        val segments = listOf(demoTrack(date))
        val state = UiState(
            ready = true,
            range = TimeRange(date, LocalTime.of(10, 12), LocalTime.of(10, 30)),
            segments = segments,
            stats = TrackStats.of(segments),
            loadedOnce = true,
        )
        rule.setContent {
            // Icônes de barre d'état foncées sur fond clair, comme dans l'appli (edge-to-edge).
            val view = LocalView.current
            SideEffect {
                val window = (view.context as Activity).window
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
            }
            MantrailingTheme { Surface { TrackScreen(state, Pet("demo", "Rex", "DEMO"), {}, {}, {}, {}, {}, {}) } }
        }

        waitForTiles()
        capture("1-trace-plan-ign")
        rule.onNodeWithText("Plan IGN").performClick()
        waitForTiles()
        capture("2-trace-photos-ign")
    }

    private fun waitForTiles() {
        rule.waitForIdle()
        Thread.sleep(8_000) // MapLibre charge les tuiles hors de Compose : pas d'état « idle » à attendre.
    }

    /** Capture prise par le shell, dans /data/local/tmp : elle survit à la désinstallation de l'appli par Gradle. */
    private fun capture(name: String) {
        val pfd = instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/store-$name.png")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() } // attend la fin de la commande
    }

    /** Recherche d'environ 15 min en mode LIVE (un point toutes les 3 s), avec hésitations et un retour en arrière. */
    private fun demoTrack(date: LocalDate): List<Position> {
        val random = Random(42)
        val start = date.atTime(10, 14).atZone(ZoneId.of("Europe/Paris")).toEpochSecond()
        // Grandes étapes de la piste (lat, lon) : sous-bois, chemin, lisière, arrivée.
        val waypoints = listOf(
            48.40540 to 2.68020, 48.40610 to 2.68130, 48.40655 to 2.68290, 48.40640 to 2.68420,
            48.40700 to 2.68500, 48.40675 to 2.68440, // demi-tour : le chien perd la piste
            48.40760 to 2.68560, 48.40850 to 2.68610, 48.40905 to 2.68740, 48.40960 to 2.68830,
        ).map { (lat, lon) -> lat + 0.0200 to lon - 0.0400 } // décalé en pleine forêt, loin des habitations
        val points = mutableListOf<Position>()
        var t = start
        for ((a, b) in waypoints.zipWithNext()) {
            val steps = 28
            for (i in 0 until steps) {
                val f = i.toDouble() / steps
                val wobble = sin(f * Math.PI * 3) * 0.00004
                val lat = a.first + (b.first - a.first) * f + wobble + random.nextDouble(-1.0, 1.0) * 0.00001
                val lon = a.second + (b.second - a.second) * f + cos(f * Math.PI * 2) * 0.00003 + random.nextDouble(-1.0, 1.0) * 0.000015
                points += Position(t, lat, lon, 120.0, uncertainty = 2.0 + random.nextInt(4), sensor = "GPS")
                t += 3
            }
        }
        return points
    }
}
