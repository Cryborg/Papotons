package com.wobblebot.papotons.data.caregiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaregiverCodeTest {
    private val salt = ByteArray(16) { it.toByte() }
    private val stored = CaregiverCode.hash("4721", salt)

    @Test
    fun `only the chosen code matches`() {
        assertTrue(CaregiverCode.matches("4721", salt, stored))
        assertFalse(CaregiverCode.matches("4712", salt, stored))
        assertFalse(CaregiverCode.matches("0000", salt, stored))
    }

    @Test
    fun `the same code under another salt does not match`() {
        val otherSalt = ByteArray(16) { (it + 1).toByte() }

        assertFalse(CaregiverCode.matches("4721", otherSalt, stored))
        assertFalse(CaregiverCode.hash("4721", otherSalt).contentEquals(stored))
    }

    @Test
    fun `codes that are not four digits are refused`() {
        assertFalse(isValidCaregiverCode("472"))
        assertFalse(isValidCaregiverCode("47211"))
        assertFalse(isValidCaregiverCode("47a1"))
        assertFalse(isValidCaregiverCode("４７２１"))
        assertFalse(CaregiverCode.matches("47211", salt, stored))
        assertTrue(isValidCaregiverCode("0007"))
    }

    @Test
    fun `new salts differ`() {
        assertFalse(CaregiverCode.newSalt().contentEquals(CaregiverCode.newSalt()))
    }
}
