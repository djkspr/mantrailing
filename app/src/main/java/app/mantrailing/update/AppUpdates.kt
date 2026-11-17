package app.mantrailing.update

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Mise à jour de l'appli proposée par Google Play. */
sealed interface UpdateState {
    /** À jour, ou impossible à savoir (appli installée hors du Play Store, pas de réseau…). */
    data object None : UpdateState

    /** Nouvelle version sur le Play Store, pas encore téléchargée. */
    data class Available(val versionCode: Int) : UpdateState

    /** Téléchargement en tâche de fond ; l'appli reste utilisable. */
    data class Downloading(val percent: Int) : UpdateState

    /** Téléchargée : s'installe au redémarrage de l'appli. */
    data object Downloaded : UpdateState
}

/**
 * État de la mise à jour d'après Google Play. Mise à jour « souple » seulement : téléchargée pendant qu'on utilise
 * l'appli, installée quand on le choisit (jamais en pleine recherche).
 */
fun updateStateOf(availability: Int, installStatus: Int, flexibleAllowed: Boolean, versionCode: Int, bytesDownloaded: Long, totalBytes: Long): UpdateState =
    when {
        installStatus == InstallStatus.DOWNLOADED -> UpdateState.Downloaded
        installStatus == InstallStatus.DOWNLOADING || installStatus == InstallStatus.PENDING ->
            UpdateState.Downloading(percentOf(bytesDownloaded, totalBytes))
        availability == UpdateAvailability.UPDATE_AVAILABLE && flexibleAllowed -> UpdateState.Available(versionCode)
        else -> UpdateState.None
    }

fun percentOf(downloaded: Long, total: Long): Int = if (total <= 0) 0 else ((downloaded * 100) / total).toInt().coerceIn(0, 100)

/** Vérification et installation des mises à jour, branchées sur le gestionnaire de Google Play. */
class AppUpdates(private val manager: AppUpdateManager) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var info: AppUpdateInfo? = null

    // Progression du téléchargement, envoyée par Google Play pendant qu'il se fait.
    private val listener = InstallStateUpdatedListener { install ->
        _state.value = when (install.installStatus()) {
            InstallStatus.DOWNLOADING, InstallStatus.PENDING ->
                UpdateState.Downloading(percentOf(install.bytesDownloaded(), install.totalBytesToDownload()))
            InstallStatus.DOWNLOADED -> UpdateState.Downloaded
            InstallStatus.INSTALLED -> UpdateState.None
            // Téléchargement annulé ou en échec : la mise à jour reste proposée.
            else -> info?.let { UpdateState.Available(it.availableVersionCode()) } ?: UpdateState.None
        }
    }

    init {
        manager.registerListener(listener)
    }

    fun dispose() = manager.unregisterListener(listener)

    /** Demande à Google Play s'il existe une version plus récente (à l'ouverture et au retour dans l'appli). */
    suspend fun check() {
        val latest = try {
            manager.requestAppUpdateInfo()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // Pas de Play Store, pas de réseau : rien à proposer, on réessaiera.
        }
        info = latest
        _state.value = updateStateOf(
            latest.updateAvailability(), latest.installStatus(), latest.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
            latest.availableVersionCode(), latest.bytesDownloaded(), latest.totalBytesToDownload(),
        )
    }

    /** Lance le téléchargement (Google Play affiche sa confirmation). Faux : rien à mettre à jour. */
    fun start(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean {
        val current = info ?: return false
        return manager.startUpdateFlowForResult(current, launcher, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build())
    }

    /** Installe la version téléchargée : l'appli redémarre. */
    fun install() {
        manager.completeUpdate()
    }
}
