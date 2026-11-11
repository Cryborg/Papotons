package com.wobblebot.papotons.ui.board

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/** `SavedStateHandle` en mémoire ne sérialise rien : seul un vrai aller-retour prouve la mort du processus. */
class SavedWordTest {
    private fun roundTrip(word: SavedWord): Any {
        val bytes = ByteArrayOutputStream().also { output -> ObjectOutputStream(output).use { it.writeObject(word) } }.toByteArray()
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
    }

    @Test
    fun `a complete saved word survives serialization`() {
        val word = SavedWord("manger", "VERB", 42L, "photo.jpg", isConjugated = true)

        assertEquals(word, roundTrip(word))
    }

    @Test
    fun `a saved word without picture survives serialization`() {
        val word = SavedWord("bonjour", null, null)

        assertEquals(word, roundTrip(word))
    }
}
