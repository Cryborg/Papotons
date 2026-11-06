package com.wobblebot.papotons.data.board

import android.content.Context
import com.wobblebot.papotons.domain.HOME_BOARD_ID
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BoardRepositoryTest {
    private val context = mockk<Context> {
        every { getString(any()) } answers { "label${firstArg<Int>()}" }
    }

    @Test
    fun `the first launch inserts the demo boards once`() = runTest {
        val dao = FakeBoardDao()
        val repository = BoardRepository(dao, context)

        assertEquals(HOME_BOARD_ID, repository.ensureHomeBoard())
        repository.ensureHomeBoard()

        assertEquals(1, dao.insertCount)
        assertNotNull(repository.observeBoard(HOME_BOARD_ID).first())
    }

    @Test
    fun `existing boards are never overwritten by the demo`() = runTest {
        val dao = FakeBoardDao(boards = listOf(BoardEntity(id = HOME_BOARD_ID, name = "mine", rowCount = 1, columnCount = 1)))

        BoardRepository(dao, context).ensureHomeBoard()

        assertEquals(0, dao.insertCount)
        assertEquals(listOf("mine"), dao.boards.value.map { it.name })
    }

    @Test
    fun `a board is read back with each cell at its stored position`() = runTest {
        // Grille non carrée et positions asymétriques : une inversion ligne/colonne ou
        // nombre de lignes/colonnes dans la conversion se voit.
        val dao = FakeBoardDao(
            boards = listOf(BoardEntity(id = HOME_BOARD_ID, name = "home", rowCount = 2, columnCount = 3)),
            cells = listOf(
                CellEntity(id = 1, boardId = HOME_BOARD_ID, row = 1, column = 2, label = "corner", linkedBoardId = null),
                CellEntity(id = 2, boardId = HOME_BOARD_ID, row = 0, column = 1, label = "folder", linkedBoardId = 7),
            ),
        )

        val board = checkNotNull(BoardRepository(dao, context).observeBoard(HOME_BOARD_ID).first())

        assertEquals("home", board.name)
        assertEquals(2, board.rowCount)
        assertEquals(3, board.columnCount)
        val corner = board.cells.single { it.label == "corner" }
        assertEquals(1 to 2, corner.row to corner.column)
        val folder = board.cells.single { it.label == "folder" }
        assertEquals(0 to 1, folder.row to folder.column)
        assertEquals(7L, folder.linkedBoardId)
    }

    @Test
    fun `a database that lost its home board opens its smallest board`() = runTest {
        val dao = FakeBoardDao(
            boards = listOf(
                BoardEntity(id = 9, name = "later", rowCount = 1, columnCount = 1),
                BoardEntity(id = 5, name = "orphan", rowCount = 1, columnCount = 1),
            ),
        )

        assertEquals(5L, BoardRepository(dao, context).ensureHomeBoard())
        assertEquals(0, dao.insertCount)
    }

    @Test
    fun `an unknown board is observed as null`() = runTest {
        val repository = BoardRepository(FakeBoardDao(), context)

        assertEquals(null, repository.observeBoard(42).first())
    }
}
