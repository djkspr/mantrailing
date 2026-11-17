package app.mantrailing.update

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Parcours complet d'une mise à jour, avec le faux gestionnaire de Google Play. */
@RunWith(AndroidJUnit4::class)
class AppUpdatesTest {
    private val fake = FakeAppUpdateManager(InstrumentationRegistry.getInstrumentation().targetContext)
    private val updates = AppUpdates(fake)

    /** La fenêtre de confirmation de Google Play est simulée par le faux gestionnaire. */
    private val launcher = object : ActivityResultLauncher<IntentSenderRequest>() {
        override fun launch(input: IntentSenderRequest, options: ActivityOptionsCompat?) = Unit
        override fun unregister() = Unit
        override val contract: ActivityResultContract<IntentSenderRequest, *> = ActivityResultContracts.StartIntentSenderForResult()
    }

    @Test
    fun aJourRienAProposer() = runTest {
        fake.setUpdateNotAvailable()
        updates.check()
        assertEquals(UpdateState.None, updates.state.value)
    }

    @Test
    fun disponibleTelechargeeInstallee() = runTest {
        fake.setUpdateAvailable(37)
        updates.check()
        assertEquals(UpdateState.Available(37), updates.state.value)

        assertTrue(updates.start(launcher))
        assertTrue(fake.isConfirmationDialogVisible)
        fake.userAcceptsUpdate()
        fake.downloadStarts()
        fake.setTotalBytesToDownload(200)
        fake.setBytesDownloaded(50)
        assertEquals(UpdateState.Downloading(25), updates.state.value)

        fake.downloadCompletes()
        assertEquals(UpdateState.Downloaded, updates.state.value)

        // Rouverte plus tard, l'appli retrouve le téléchargement terminé.
        updates.check()
        assertEquals(UpdateState.Downloaded, updates.state.value)

        updates.install()
        assertTrue(fake.isInstallSplashScreenVisible)
        fake.installCompletes()
        assertEquals(UpdateState.None, updates.state.value)
    }

    @Test
    fun telechargementAnnuleLaMiseAJourResteProposee() = runTest {
        fake.setUpdateAvailable(37)
        updates.check()
        updates.start(launcher)
        fake.userAcceptsUpdate()
        fake.downloadStarts()
        fake.userCancelsDownload()
        assertEquals(UpdateState.Available(37), updates.state.value)
        assertFalse(fake.isInstallSplashScreenVisible)
    }

    @Test
    fun sansVerificationRienALancer() {
        assertFalse(updates.start(launcher))
    }
}
