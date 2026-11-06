package com.wobblebot.papotons.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordCategoryTest {
    /** Noms déjà écrits en base : les retirer ou les renommer décolorerait les grilles installées. */
    private val persistedNames = listOf("PEOPLE", "VERB", "DESCRIPTOR", "NOUN", "SOCIAL", "QUESTION", "NEGATION", "LITTLE_WORD")

    @Test
    fun `every persisted name is still read back`() {
        for (name in persistedNames) {
            assertEquals(name, WordCategory.fromStoredName(name)?.name)
        }
    }

    @Test
    fun `an unknown name from a future version is read as no category`() {
        assertNull(WordCategory.fromStoredName("FUTURE_CATEGORY"))
    }

    @Test
    fun `a cell without category stays without category`() {
        assertNull(WordCategory.fromStoredName(null))
    }
}
