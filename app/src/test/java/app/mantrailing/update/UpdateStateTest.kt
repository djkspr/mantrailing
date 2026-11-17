package app.mantrailing.update

import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateStateTest {
    private fun state(availability: Int, install: Int = InstallStatus.UNKNOWN, flexible: Boolean = true, done: Long = 0, total: Long = 0) =
        updateStateOf(availability, install, flexible, versionCode = 37, bytesDownloaded = done, totalBytes = total)

    @Test
    fun `nouvelle version proposee seulement si la mise a jour souple est permise`() {
        assertEquals(UpdateState.Available(37), state(UpdateAvailability.UPDATE_AVAILABLE))
        assertEquals(UpdateState.None, state(UpdateAvailability.UPDATE_AVAILABLE, flexible = false))
        assertEquals(UpdateState.None, state(UpdateAvailability.UPDATE_NOT_AVAILABLE))
        assertEquals(UpdateState.None, state(UpdateAvailability.UNKNOWN))
    }

    @Test
    fun `telechargement en cours ou termine, retrouve a la reouverture de l'appli`() {
        assertEquals(UpdateState.Downloading(25), state(UpdateAvailability.UPDATE_AVAILABLE, InstallStatus.DOWNLOADING, done = 25, total = 100))
        assertEquals(UpdateState.Downloading(0), state(UpdateAvailability.UPDATE_AVAILABLE, InstallStatus.PENDING))
        assertEquals(UpdateState.Downloaded, state(UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS, InstallStatus.DOWNLOADED))
    }

    @Test
    fun `pourcentage borne et sans division par zero`() {
        assertEquals(0, percentOf(10, 0))
        assertEquals(50, percentOf(1_000, 2_000))
        assertEquals(100, percentOf(3_000, 2_000))
    }
}
