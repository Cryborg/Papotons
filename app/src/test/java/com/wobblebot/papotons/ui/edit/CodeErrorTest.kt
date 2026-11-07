package com.wobblebot.papotons.ui.edit

import com.wobblebot.papotons.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeErrorTest {
    @Test
    fun `a new code is four digits typed twice the same`() {
        assertNull(codeError("4721", "4721"))
        assertEquals(R.string.caregiver_code_invalid, codeError("472", "472"))
        assertEquals(R.string.caregiver_code_mismatch, codeError("4721", "4712"))
        assertEquals(R.string.caregiver_code_invalid, codeError("", ""))
        // Un code trop court se dit avant une différence.
        assertEquals(R.string.caregiver_code_invalid, codeError("472", "4712"))
        assertEquals(R.string.caregiver_code_mismatch, codeError("4721", ""))
    }
}
