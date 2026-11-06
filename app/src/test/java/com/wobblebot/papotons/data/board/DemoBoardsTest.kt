package com.wobblebot.papotons.data.board

import com.wobblebot.papotons.domain.HOME_BOARD_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Une faute de saisie dans la démo casserait l'index unique ou une clé étrangère au premier lancement. */
class DemoBoardsTest {
    private val seed = DemoBoards.build { resourceId -> "label$resourceId" }
    private val boardsById = seed.boards.associateBy { it.id }

    @Test
    fun `the home board exists`() {
        assertTrue(HOME_BOARD_ID in boardsById)
    }

    @Test
    fun `board ids are unique`() {
        assertEquals(seed.boards.size, boardsById.size)
    }

    @Test
    fun `every cell lies inside its board`() {
        for (cell in seed.cells) {
            val board = checkNotNull(boardsById[cell.boardId]) { "Cell ${cell.label} in unknown board ${cell.boardId}" }
            assertTrue("${cell.label} at (${cell.row}, ${cell.column})", cell.row in 0 until board.rowCount)
            assertTrue("${cell.label} at (${cell.row}, ${cell.column})", cell.column in 0 until board.columnCount)
        }
    }

    @Test
    fun `no two cells share a slot`() {
        val slots = seed.cells.map { Triple(it.boardId, it.row, it.column) }
        assertEquals(slots.size, slots.toSet().size)
    }

    @Test
    fun `every folder opens an existing board other than its own`() {
        val folders = seed.cells.filter { it.linkedBoardId != null }
        assertTrue(folders.isNotEmpty())
        for (folder in folders) {
            assertTrue(folder.label, folder.linkedBoardId in boardsById)
            assertTrue(folder.label, folder.linkedBoardId != folder.boardId)
        }
    }
}
