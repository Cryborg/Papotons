package com.wobblebot.papotons.speech

import com.wobblebot.papotons.domain.takeWhole
import org.junit.Assert.assertEquals
import org.junit.Test

private const val MAX_LENGTH = 5

class CutForSpeechTest {
    @Test
    fun `a text at the exact limit is kept whole`() {
        assertEquals("abcde", "abcde".takeWhole(MAX_LENGTH))
    }

    @Test
    fun `one character over the limit is cut`() {
        assertEquals("abcde", "abcdef".takeWhole(MAX_LENGTH))
    }

    @Test
    fun `an emoji straddling the limit is dropped whole`() {
        // "😀" est une paire de substitution : sa première moitié tombe en cinquième position.
        val text = "abcd😀"

        assertEquals("abcd", text.takeWhole(MAX_LENGTH))
    }
}
