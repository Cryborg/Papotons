package com.wobblebot.papotons.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckPolicyTest {
    private val interval = UpdateCheckPolicy.RECHECK_INTERVAL_MS
    private val immediatePriority = UpdateCheckPolicy.IMMEDIATE_MIN_PRIORITY

    @Test
    fun `the first launch always checks`() {
        assertTrue(UpdateCheckPolicy.isCheckDue(lastCheckAtMs = null, nowMs = 0L))
    }

    @Test
    fun `a quick return does not check again`() {
        assertFalse(UpdateCheckPolicy.isCheckDue(lastCheckAtMs = 1_000L, nowMs = 1_000L + interval - 1))
    }

    @Test
    fun `a return after a long absence checks again`() {
        assertTrue(UpdateCheckPolicy.isCheckDue(lastCheckAtMs = 1_000L, nowMs = 1_000L + interval))
    }

    @Test
    fun `any version is proposed when none was declined`() {
        assertTrue(UpdateCheckPolicy.mayPropose(availableVersionCode = 9, declinedVersionCode = null))
    }

    @Test
    fun `a declined version is not proposed again`() {
        assertFalse(UpdateCheckPolicy.mayPropose(availableVersionCode = 9, declinedVersionCode = 9))
    }

    @Test
    fun `a version newer than the declined one is proposed`() {
        assertTrue(UpdateCheckPolicy.mayPropose(availableVersionCode = 10, declinedVersionCode = 9))
    }

    @Test
    fun `an ordinary release is flexible`() {
        assertEquals(UpdateKind.Flexible, choose(updatePriority = 0))
    }

    @Test
    fun `a release under the priority threshold stays flexible`() {
        assertEquals(UpdateKind.Flexible, choose(updatePriority = immediatePriority - 1))
    }

    @Test
    fun `a high priority release is immediate`() {
        assertEquals(UpdateKind.Immediate, choose(updatePriority = immediatePriority))
    }

    @Test
    fun `an immediate update ignores a previous refusal`() {
        assertEquals(
            UpdateKind.Immediate,
            choose(updatePriority = immediatePriority, availableVersionCode = 9, declinedVersionCode = 9)
        )
    }

    @Test
    fun `a high priority release falls back to flexible when immediate is not allowed`() {
        assertEquals(UpdateKind.Flexible, choose(updatePriority = immediatePriority, isImmediateAllowed = false))
    }

    @Test
    fun `a declined flexible release is not proposed again`() {
        assertNull(choose(updatePriority = 0, availableVersionCode = 9, declinedVersionCode = 9))
    }

    @Test
    fun `nothing is proposed when no update type is allowed`() {
        assertNull(choose(updatePriority = 0, isFlexibleAllowed = false))
    }

    private fun choose(
        updatePriority: Int,
        isImmediateAllowed: Boolean = true,
        isFlexibleAllowed: Boolean = true,
        availableVersionCode: Int = 9,
        declinedVersionCode: Int? = null
    ): UpdateKind? = UpdateCheckPolicy.chooseUpdateKind(
        updatePriority = updatePriority,
        isImmediateAllowed = isImmediateAllowed,
        isFlexibleAllowed = isFlexibleAllowed,
        availableVersionCode = availableVersionCode,
        declinedVersionCode = declinedVersionCode
    )
}
