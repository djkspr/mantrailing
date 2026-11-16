package app.mantrailing.demo

import app.mantrailing.TimeRange
import app.mantrailing.tractive.Position
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Séance fictive (forêt de Fontainebleau) pour découvrir l'appli sans collier :
 * mode démonstration, examen Google Play et captures d'écran du Store.
 */
object DemoSession {
    const val DOG_NAME = "Rex"

    /**
     * Compte de démonstration (pour l'examen Google Play, rubrique « Accès à l'application ») :
     * saisi dans le formulaire de connexion, il ouvre la séance fictive sans contacter Tractive.
     */
    const val EMAIL = "demo@mantrailing.app"
    const val PASSWORD = "demo-mantrailing"

    fun isDemoAccount(email: String, password: String) =
        email.trim().equals(EMAIL, ignoreCase = true) && password == PASSWORD

    val date: LocalDate = LocalDate.of(2026, 11, 15)
    val range = TimeRange(date, LocalTime.of(10, 12), LocalTime.of(10, 30))

    /** Recherche d'environ 12 min en mode LIVE (un point toutes les 3 s), avec une hésitation et un retour en arrière. */
    val segments: List<List<Position>> by lazy { listOf(track()) }

    private fun track(): List<Position> {
        val random = Random(42)
        val start = date.atTime(10, 14).atZone(ZoneId.of("Europe/Paris")).toEpochSecond()
        // Grandes étapes de la piste (lat, lon), en pleine forêt, loin des habitations.
        val waypoints = listOf(
            48.42540 to 2.64020, 48.42610 to 2.64130, 48.42655 to 2.64290, 48.42640 to 2.64420,
            48.42700 to 2.64500, 48.42675 to 2.64440, // demi-tour : le chien perd la piste
            48.42760 to 2.64560, 48.42850 to 2.64610, 48.42905 to 2.64740, 48.42960 to 2.64830,
        )
        val points = mutableListOf<Position>()
        var t = start
        for ((a, b) in waypoints.zipWithNext()) {
            val steps = 28
            for (i in 0 until steps) {
                val f = i.toDouble() / steps
                val lat = a.first + (b.first - a.first) * f + sin(f * Math.PI * 3) * 0.00004 + random.nextDouble(-1.0, 1.0) * 0.00001
                val lon = a.second + (b.second - a.second) * f + cos(f * Math.PI * 2) * 0.00003 + random.nextDouble(-1.0, 1.0) * 0.000015
                points += Position(t, lat, lon, 120.0, uncertainty = 2.0 + random.nextInt(4), sensor = "GPS")
                t += 3
            }
        }
        return points
    }
}
