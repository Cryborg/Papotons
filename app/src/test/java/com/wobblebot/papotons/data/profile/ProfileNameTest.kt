package com.wobblebot.papotons.data.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileNameTest {
    @Test
    fun `a name keeps single spaces between its words`() {
        assertEquals("Léo Martin", normalizedProfileName("  Léo \t  Martin \n"))
        assertEquals("", normalizedProfileName("   "))
    }

    @Test
    fun `only names the backup accepts are valid`() {
        assertTrue(isValidProfileName("Léo"))
        assertTrue(isValidProfileName("x".repeat(MAX_PROFILE_NAME_LENGTH)))
        assertFalse(isValidProfileName("x".repeat(MAX_PROFILE_NAME_LENGTH + 1)))
        assertFalse(isValidProfileName(" "))
        assertFalse(isValidProfileName(""))
    }
}
