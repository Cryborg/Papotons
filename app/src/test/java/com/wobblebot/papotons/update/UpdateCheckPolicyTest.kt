package com.wobblebot.papotons.update

import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckPolicyTest {

    private fun status(
        availability: Int = UpdateAvailability.UPDATE_AVAILABLE,
        installStatus: Int = InstallStatus.UNKNOWN,
        isFlexibleAllowed: Boolean = true,
        isImmediateAllowed: Boolean = true,
    ) = UpdateCheckPolicy.status(availability, installStatus, isFlexibleAllowed, isImmediateAllowed)

    @Test
    fun `a newer version is offered, never started`() {
        assertEquals(UpdateStatus.Available, status())
    }

    @Test
    fun `an up to date app shows nothing`() {
        assertEquals(UpdateStatus.None, status(availability = UpdateAvailability.UPDATE_NOT_AVAILABLE))
    }

    @Test
    fun `an unknown answer shows nothing`() {
        assertEquals(UpdateStatus.None, status(availability = UpdateAvailability.UNKNOWN))
    }

    @Test
    fun `a version Play lets neither way shows nothing`() {
        assertEquals(UpdateStatus.None, status(isFlexibleAllowed = false, isImmediateAllowed = false))
    }

    @Test
    fun `an accepted download still said available is downloading`() {
        assertEquals(UpdateStatus.Downloading, status(installStatus = InstallStatus.DOWNLOADING))
        assertEquals(UpdateStatus.Downloading, status(installStatus = InstallStatus.PENDING))
    }

    @Test
    fun `a downloaded version waits for the adult`() {
        assertEquals(UpdateStatus.ReadyToInstall, status(installStatus = InstallStatus.DOWNLOADED))
    }

    @Test
    fun `an immediate update left halfway is offered again`() {
        assertEquals(UpdateStatus.Available, status(availability = UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS))
    }

    @Test
    fun `flexible is preferred whatever the priority`() {
        assertEquals(UpdateKind.Flexible, UpdateCheckPolicy.chooseUpdateKind(isFlexibleAllowed = true, isImmediateAllowed = true))
    }

    @Test
    fun `immediate only when flexible is refused`() {
        assertEquals(UpdateKind.Immediate, UpdateCheckPolicy.chooseUpdateKind(isFlexibleAllowed = false, isImmediateAllowed = true))
    }

    @Test
    fun `nothing when Play allows no flow`() {
        assertNull(UpdateCheckPolicy.chooseUpdateKind(isFlexibleAllowed = false, isImmediateAllowed = false))
    }
}
