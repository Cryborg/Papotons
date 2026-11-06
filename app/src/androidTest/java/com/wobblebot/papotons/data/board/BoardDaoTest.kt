package com.wobblebot.papotons.data.board

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wobblebot.papotons.data.PapotonsDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Ce que le DAO factice des tests JVM ne sait pas faire : index, clés étrangères, conflits. */
@RunWith(AndroidJUnit4::class)
class BoardDaoTest {
    private lateinit var database: PapotonsDatabase
    private lateinit var boardDao: BoardDao

    private val home = BoardEntity(id = 1, name = "home", rowCount = 2, columnCount = 2)
    private val folderBoard = BoardEntity(id = 2, name = "folder", rowCount = 1, columnCount = 1)

    private fun word(row: Int, column: Int, label: String) =
        CellEntity(boardId = home.id, row = row, column = column, label = label, linkedBoardId = null, pictogramId = null, category = null)

    private val folder = CellEntity(boardId = home.id, row = 1, column = 1, label = "folder", linkedBoardId = folderBoard.id, pictogramId = null, category = null)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, PapotonsDatabase::class.java).build()
        boardDao = database.boardDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun seedingFillsAnEmptyDatabaseOnlyOnce() = runBlocking {
        assertTrue(boardDao.seedIfEmpty(listOf(home, folderBoard), listOf(word(0, 0, "je"), folder)))
        assertFalse(boardDao.seedIfEmpty(listOf(home.copy(id = 3)), emptyList()))

        val stored = checkNotNull(boardDao.observeBoard(home.id).first())
        assertEquals(setOf("je", "folder"), stored.cells.map { it.label }.toSet())
        assertEquals(2, boardDao.countBoards())
    }

    @Test
    fun aFailedSeedLeavesNothingBehindAndCanBeRetried() = runBlocking {
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { boardDao.seedIfEmpty(listOf(home), listOf(word(0, 0, "a"), word(0, 0, "b"))) }
        }

        assertEquals(0, boardDao.countBoards())
        assertTrue(boardDao.seedIfEmpty(listOf(home), listOf(word(0, 0, "a"))))
    }

    @Test
    fun cellsAreReadBackAtTheirStoredPositions() = runBlocking {
        boardDao.insertAll(listOf(home), listOf(word(1, 0, "low"), word(0, 1, "right")))

        val stored = checkNotNull(boardDao.observeBoard(home.id).first())

        assertEquals(1 to 0, stored.cells.single { it.label == "low" }.let { it.row to it.column })
        assertEquals(0 to 1, stored.cells.single { it.label == "right" }.let { it.row to it.column })
    }

    @Test
    fun twoCellsCannotShareASlot() {
        runBlocking { boardDao.insertAll(listOf(home), listOf(word(0, 1, "je"))) }

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { boardDao.insertAll(emptyList(), listOf(word(0, 1, "veux"))) }
        }
    }

    @Test
    fun reinsertingABoardFailsAndKeepsItsCells() = runBlocking {
        boardDao.insertAll(listOf(home), listOf(word(0, 0, "je")))

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { boardDao.insertAll(listOf(home.copy(name = "other")), emptyList()) }
        }

        val stored = checkNotNull(boardDao.observeBoard(home.id).first())
        assertEquals("home", stored.board.name)
        assertEquals(listOf("je"), stored.cells.map { it.label })
    }

    @Test
    fun aBoardOpenedByAFolderCannotBeDeleted() = runBlocking {
        boardDao.insertAll(listOf(home, folderBoard), listOf(folder))

        assertThrows(SQLiteConstraintException::class.java) {
            database.openHelper.writableDatabase.execSQL("DELETE FROM boards WHERE id = ${folderBoard.id}")
        }

        val stored = checkNotNull(boardDao.observeBoard(home.id).first())
        assertEquals(folderBoard.id, stored.cells.single().linkedBoardId)
    }
}
