package com.wobblebot.papotons.speech

import org.junit.Assert.assertEquals
import org.junit.Test

private const val MAX_LENGTH = 5

class CutForSpeechTest {
    @Test
    fun `a text at the exact limit is kept whole`() {
        assertEquals("abcde", cutForSpeech("abcde", MAX_LENGTH))
    }

    @Test
    fun `one character over the limit is cut`() {
        assertEquals("abcde", cutForSpeech("abcdef", MAX_LENGTH))
    }

    @Test
    fun `an emoji straddling the limit is dropped whole`() {
        // "😀" est une paire de substitution : sa première moitié tombe en cinquième position.
        val text = "abcd😀"

        assertEquals("abcd", cutForSpeech(text, MAX_LENGTH))
    }
}
