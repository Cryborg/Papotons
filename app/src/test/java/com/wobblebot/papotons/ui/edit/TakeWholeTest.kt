package com.wobblebot.papotons.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Test

class TakeWholeTest {
    private val maxLength = 200
    private val emoji = "😀"

    @Test
    fun `an emoji cut in half is dropped whole`() {
        val typed = "a".repeat(maxLength - 1) + emoji

        assertEquals("a".repeat(maxLength - 1), typed.takeWhole(maxLength))
    }

    @Test
    fun `an emoji that fits is kept whole`() {
        val typed = "a".repeat(maxLength - 2) + emoji

        assertEquals(typed, typed.takeWhole(maxLength))
    }

    @Test
    fun `a short text is unchanged`() {
        assertEquals("bonjour $emoji", "bonjour $emoji".takeWhole(maxLength))
    }
}
