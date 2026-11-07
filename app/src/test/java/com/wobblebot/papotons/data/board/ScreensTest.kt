package com.wobblebot.papotons.data.board

import com.wobblebot.papotons.data.profile.ProfileEntity
import com.wobblebot.papotons.domain.HOME_BOARD_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(hidden.id, legacyFirstScreenOf(startBoardId = hidden.id, boards, linked))
    }

    @Test
    fun `a start board that is a folder gives way to the standard home`() {
        assertEquals(home.id, legacyFirstScreenOf(startBoardId = folder.id, boards, linked))
    }

    @Test
    fun `a start board of someone else gives way to the standard home`() {
        assertEquals(home.id, legacyFirstScreenOf(startBoardId = 500, boards, linked))
    }

    @Test
    fun `without a standard home, the smallest board that no folder opens`() {
        assertEquals(hidden.id, legacyFirstScreenOf(startBoardId = null, listOf(folder, hidden), linked))
    }

    /** Données abîmées : toutes les grilles sont visées. Il faut quand même un écran. */
    @Test
    fun `when every board is opened by a folder, the smallest board`() {
        assertEquals(folder.id, legacyFirstScreenOf(startBoardId = null, listOf(home, folder), linked + home.id))
    }

    @Test
    fun `no board, no screen`() {
        assertNull(legacyFirstScreenOf(startBoardId = 1, emptyList(), emptySet()))
    }

    /** Plusieurs utilisateurs : chacun son premier écran, choisi parmi SES grilles. */
    @Test
    fun `each profile gets one first screen among its own boards`() {
        val leo = ProfileEntity(1, "Léo", startBoardId = null)
        val anna = ProfileEntity(2, "Anna", startBoardId = 1001)
        val zoe = ProfileEntity(3, "Zoé", startBoardId = 1)
        val boards = listOf(
            BoardEntity(1, "home", 1, 1, profileId = 1, coreBoardId = HOME_BOARD_ID),
            BoardEntity(1000, "home", 1, 1, profileId = 2, coreBoardId = HOME_BOARD_ID),
            BoardEntity(1001, "medium", 1, 1, profileId = 2, coreBoardId = 7),
            BoardEntity(1002, "food", 1, 1, profileId = 2, coreBoardId = 2),
            BoardEntity(1100, "home", 1, 1, profileId = 3, coreBoardId = HOME_BOARD_ID),
        )
        val cells = listOf(CellEntity(boardId = 1000, row = 0, column = 0, label = "manger", linkedBoardId = 1002, pictogramId = null, category = null))

        val screens = withFirstScreens(listOf(leo, anna, zoe), boards, cells).filter { it.screenPosition != null }

        assertEquals(mapOf(1L to 1L, 2L to 1001L, 3L to 1100L), screens.associate { it.profileId to it.id })
        assertTrue(screens.all { it.screenPosition == FIRST_SCREEN_POSITION })
    }
}

