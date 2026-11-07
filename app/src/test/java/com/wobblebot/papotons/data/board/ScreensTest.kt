package com.wobblebot.papotons.data.board

import com.wobblebot.papotons.domain.HOME_BOARD_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** La règle du premier écran, partagée par la migration 4 → 5 et la lecture des anciennes sauvegardes. */
class ScreensTest {
    // L'accueil standard a un id plus grand que d'autres grilles : une règle « plus petit id » le raterait.
    private val folder = BoardOrigin(id = 3, coreBoardId = 2)
    private val hidden = BoardOrigin(id = 4, coreBoardId = 13)
    private val home = BoardOrigin(id = 9, coreBoardId = HOME_BOARD_ID)
    private val boards = listOf(folder, hidden, home)
    private val linked = setOf(folder.id)

    @Test
    fun `the chosen start board wins when it is not a folder`() {
        assertEquals(hidden.id, firstScreenOf(startBoardId = hidden.id, boards, linked))
    }

    @Test
    fun `a start board that is a folder gives way to the standard home`() {
        assertEquals(home.id, firstScreenOf(startBoardId = folder.id, boards, linked))
    }

    @Test
    fun `a start board of someone else gives way to the standard home`() {
        assertEquals(home.id, firstScreenOf(startBoardId = 500, boards, linked))
    }

    @Test
    fun `without a standard home, the smallest board that no folder opens`() {
        assertEquals(hidden.id, firstScreenOf(startBoardId = null, listOf(folder, hidden), linked))
    }

    /** Données abîmées : toutes les grilles sont visées. Il faut quand même un écran. */
    @Test
    fun `when every board is opened by a folder, the smallest board`() {
        assertEquals(folder.id, firstScreenOf(startBoardId = null, listOf(home, folder), linked + home.id))
    }

    @Test
    fun `no board, no screen`() {
        assertNull(firstScreenOf(startBoardId = 1, emptyList(), emptySet()))
    }
}
