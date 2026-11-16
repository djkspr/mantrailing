package app.mantrailing.data

import android.content.Context
import app.mantrailing.session.LaidTrailRepository
import app.mantrailing.session.SessionRepository
import app.mantrailing.sync.SyncJournal
import java.io.File

/** Emplacements des données dans le stockage privé de l'appli, partagés par l'écran et la synchronisation. */
object AppStorage {
    fun sessions(context: Context) = SessionRepository(File(context.filesDir, "sessions"))
    fun trails(context: Context) = LaidTrailRepository(File(context.filesDir, "trails"))

    /** Journal de synchronisation d'un espace (équipe, ou perso si [spaceId] est null). */
    fun syncJournal(context: Context, spaceId: String?) =
        SyncJournal(File(context.filesDir, "sync/${spaceId?.let { "team-$it" } ?: "perso"}.json"))

    /** Tous les journaux : déconnexion du compte. */
    fun resetAllJournals(context: Context) {
        File(context.filesDir, "sync").listFiles()?.forEach { it.delete() }
    }
}
