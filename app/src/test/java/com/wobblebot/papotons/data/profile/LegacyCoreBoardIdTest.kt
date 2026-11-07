package com.wobblebot.papotons.data.profile

import org.junit.Assert.assertEquals
import org.junit.Test

/** La règle commune à la migration 3 → 4 et aux sauvegardes au format 1. */
class LegacyCoreBoardIdTest {
    @Test
    fun `boards of the core vocabulary keep their id as origin, adult boards have none`() {
        assertEquals(1L, legacyCoreBoardId(1, isLinked = false))
        assertEquals(2L, legacyCoreBoardId(2, isLinked = true))
        assertEquals(999L, legacyCoreBoardId(999, isLinked = false))
        assertEquals(null, legacyCoreBoardId(1000, isLinked = false))
    }

    @Test
    fun `a size home opened by a folder was an adult folder, a free one is the home`() {
        assertEquals(7L, legacyCoreBoardId(7, isLinked = false))
        assertEquals(null, legacyCoreBoardId(7, isLinked = true))
        assertEquals(13L, legacyCoreBoardId(13, isLinked = false))
        assertEquals(null, legacyCoreBoardId(13, isLinked = true))
    }
}
