package com.wobblebot.papotons.data.board

import android.content.Context
import com.wobblebot.papotons.data.FakePreferences
import com.wobblebot.papotons.domain.CellContent
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.domain.HOME_BOARD_ID
import com.wobblebot.papotons.domain.WordCategory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import android.database.sqlite.SQLiteConstraintException
import com.wobblebot.papotons.data.CORRUPTED_DATABASES_DIRECTORY
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Ce que l'adulte fait des grilles en mode édition (MVP-3). */
class BoardEditingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = mockk<Context> {
        every { getString(any()) } answers { "label${firstArg<Int>()}" }
        every { filesDir } answers { folder.root }
    }
    private val home = BoardEntity(id = HOME_BOARD_ID, name = "home", rowCount = 2, columnCount = 3)

    private fun cell(id: Long, boardId: Long, row: Int, column: Int, label: String, linkedBoardId: Long? = null) =
        CellEntity(id = id, boardId = boardId, row = row, column = column, label = label, linkedBoardId = linkedBoardId, pictogramId = null, category = null)

    private fun repository(dao: FakeBoardDao) = BoardRepository(dao, FakePreferences(), context)

    @Test
    fun `a word lands exactly where it was asked, never on a taken slot`() = runTest {
        val dao = FakeBoardDao(listOf(home), listOf(cell(1, HOME_BOARD_ID, 0, 0, "je")))
        val repository = repository(dao)

        repository.addWord(HOME_BOARD_ID, 1, 2, CellContent("eau", 32464, WordCategory.NOUN, null, null))

        val added = dao.cells.value.single { it.label == "eau" }
        assertEquals(Triple(HOME_BOARD_ID, 1, 2), Triple(added.boardId, added.row, added.column))
        assertEquals(32464L, added.pictogramId)
        assertEquals("NOUN", added.category)
        assertThrows(SQLiteConstraintException::class.java) { runBlocking { repository.addWord(HOME_BOARD_ID, 0, 0, CellContent("x", null, null, null, null)) } }
        assertEquals(listOf("je", "eau"), dao.cells.value.map { it.label })
    }

    @Test
    fun `a folder brings its own empty board named after it`() = runTest {
        val dao = FakeBoardDao(listOf(home))

        repository(dao).addFolder(HOME_BOARD_ID, 1, 0, CellContent("Animaux", null, WordCategory.NOUN, null, null), BoardSize.STANDARD)

        val folderCell = dao.cells.value.single()
        val folderBoard = dao.boards.value.single { it.id == folderCell.linkedBoardId }
        assertEquals("Animaux", folderBoard.name)
        assertEquals(4 to 6, folderBoard.rowCount to folderBoard.columnCount)
        assertEquals(1 to 0, folderCell.row to folderCell.column)
    }

    @Test
    fun `an edit changes the content, never the position, and renames a folder board`() = runTest {
        val dao = FakeBoardDao(listOf(home, BoardEntity(2, "old", 1, 1)), listOf(cell(5, HOME_BOARD_ID, 1, 2, "old", linkedBoardId = 2)))
        val repository = repository(dao)
        val folderCell = checkNotNull(repository.observeBoard(HOME_BOARD_ID).first()).cells.single()

        repository.updateCell(folderCell, CellContent("new", 7, WordCategory.VERB, null, null))

        val updated = dao.cells.value.single()
        assertEquals(Triple("new", 7L, "VERB"), Triple(updated.label, updated.pictogramId, updated.category))
        assertEquals(1 to 2, updated.row to updated.column)
        assertEquals("new", dao.boards.value.single { it.id == 2L }.name)
    }

    @Test
    fun `deleting a folder removes its subtree but never home or the open root`() = runTest {
        // Accueil → A (9) → B (3). La racine ouverte (ici 9 elle-même, cas d'un accueil perdu) reste.
        val dao = FakeBoardDao(
            listOf(home, BoardEntity(9, "A", 1, 1), BoardEntity(3, "B", 1, 1)),
            listOf(cell(1, HOME_BOARD_ID, 0, 0, "A", linkedBoardId = 9), cell(2, 9, 0, 0, "B", linkedBoardId = 3)),
        )
        val repository = repository(dao)
        val folderCell = checkNotNull(repository.observeBoard(HOME_BOARD_ID).first()).cells.single()

        repository.deleteCell(folderCell, boardId = HOME_BOARD_ID, openRootBoardId = HOME_BOARD_ID)
        assertEquals(listOf(HOME_BOARD_ID), dao.boards.value.map { it.id })

        val other = FakeBoardDao(
            listOf(home, BoardEntity(9, "A", 1, 1), BoardEntity(3, "B", 1, 1)),
            listOf(cell(1, HOME_BOARD_ID, 0, 0, "A", linkedBoardId = 9), cell(2, 9, 0, 0, "B", linkedBoardId = 3)),
        )
        repository(other).deleteCell(checkNotNull(repository(other).observeBoard(HOME_BOARD_ID).first()).cells.single(), HOME_BOARD_ID, openRootBoardId = 9)
        // A protégée garde B, qu'elle vise encore.
        assertEquals(setOf(HOME_BOARD_ID, 9L, 3L), other.boards.value.map { it.id }.toSet())
    }

    @Test
    fun `deleting a word removes only that cell`() = runTest {
        val dao = FakeBoardDao(listOf(home), listOf(cell(1, HOME_BOARD_ID, 0, 0, "je"), cell(2, HOME_BOARD_ID, 0, 1, "veux")))
        val repository = repository(dao)
        val word = checkNotNull(repository.observeBoard(HOME_BOARD_ID).first()).cells.single { it.label == "je" }

        repository.deleteCell(word, HOME_BOARD_ID, HOME_BOARD_ID)

        assertEquals(listOf("veux"), dao.cells.value.map { it.label })
        assertEquals(listOf(HOME_BOARD_ID), dao.boards.value.map { it.id })
    }

    @Test
    fun `the board holding the cell is never deleted, even when the folder leads back to it`() = runTest {
        // Grille 9 (atteinte par un import, sans lien depuis l'accueil) : un dossier qui la vise
        // elle-même. Sa suppression la rendrait orpheline ; elle porte la case, elle reste.
        val dao = FakeBoardDao(
            listOf(home, BoardEntity(9, "A", 1, 2)),
            listOf(cell(2, 9, 0, 0, "self", linkedBoardId = 9)),
        )
        val repository = repository(dao)
        val selfLink = checkNotNull(repository.observeBoard(9).first()).cells.single()

        repository.deleteCell(selfLink, boardId = 9, openRootBoardId = HOME_BOARD_ID)

        assertTrue(dao.boards.value.any { it.id == 9L })
    }

    @Test
    fun `editing a word renames no board`() = runTest {
        val dao = FakeBoardDao(listOf(home, BoardEntity(2, "keep", 1, 1)), listOf(cell(2, HOME_BOARD_ID, 0, 0, "je")))
        val repository = repository(dao)

        repository.updateCell(checkNotNull(repository.observeBoard(HOME_BOARD_ID).first()).cells.single(), CellContent("moi", null, null, null, null))

        assertEquals("keep", dao.boards.value.single { it.id == 2L }.name)
    }

    @Test
    fun `every kind of edit marks the copies made before it`() = runTest {
        val marker = File(folder.root, "$CORRUPTED_DATABASES_DIRECTORY.edited")
        val dao = FakeBoardDao(listOf(home), listOf(cell(1, HOME_BOARD_ID, 0, 0, "je")))
        val repository = repository(dao)
        val edits = listOf<suspend () -> Unit>(
            { repository.addWord(HOME_BOARD_ID, 0, 1, CellContent("eau", null, null, null, null)) },
            { repository.addFolder(HOME_BOARD_ID, 0, 2, CellContent("Jeux", null, null, null, null), BoardSize.STANDARD) },
            { repository.updateCell(checkNotNull(repository.observeBoard(HOME_BOARD_ID).first()).cells.first(), CellContent("moi", null, null, null, null)) },
            { repository.deleteCell(checkNotNull(repository.observeBoard(HOME_BOARD_ID).first()).cells.first(), HOME_BOARD_ID, HOME_BOARD_ID) },
        )
        for ((index, edit) in edits.withIndex()) {
            val copy = index + 1
            File(folder.root, "$CORRUPTED_DATABASES_DIRECTORY/00000$copy-x").mkdirs()

            edit()

            assertEquals("edit $index", "$copy", marker.readText())
        }
    }

    @Test
    fun `an edit marker that cannot be written stops the edit before the database`() = runTest {
        File(folder.root, "$CORRUPTED_DATABASES_DIRECTORY/000001-x").mkdirs()
        // Un dossier à la place du marqueur : le renommage échoue.
        File(folder.root, "$CORRUPTED_DATABASES_DIRECTORY.edited/occupied").mkdirs()
        val dao = FakeBoardDao(listOf(home))

        assertThrows(IOException::class.java) { runBlocking { repository(dao).addWord(HOME_BOARD_ID, 0, 0, CellContent("eau", null, null, null, null)) } }

        assertEquals(emptyList<CellEntity>(), dao.cells.value)
    }

    @Test
    fun `a deleted folder gives back the photos of every cell it took`() = runTest {
        val dao = FakeBoardDao(
            listOf(home, BoardEntity(9, "A", 1, 2)),
            listOf(
                cell(1, HOME_BOARD_ID, 0, 0, "A", linkedBoardId = 9).copy(photoFile = "folder.jpg"),
                cell(2, 9, 0, 0, "Mamie").copy(photoFile = "mamie.jpg"),
                cell(3, HOME_BOARD_ID, 0, 1, "reste").copy(photoFile = "reste.jpg"),
            ),
        )
        val repository = repository(dao)
        val folderCell = checkNotNull(repository.observeBoard(HOME_BOARD_ID).first()).cells.single { it.label == "A" }

        val photos = repository.deleteCell(folderCell, HOME_BOARD_ID, HOME_BOARD_ID)

        assertEquals(setOf("folder.jpg", "mamie.jpg"), photos.toSet())
    }

    @Test
    fun `editing only the label keeps the photo and what the cell says`() = runTest {
        val dao = FakeBoardDao(listOf(home), listOf(cell(1, HOME_BOARD_ID, 0, 0, "Mamie").copy(photoFile = "m.jpg", spokenText = "Mamie Jeanne")))
        val repository = repository(dao)
        val cell = checkNotNull(repository.observeBoard(HOME_BOARD_ID).first()).cells.single()

        repository.updateCell(cell, CellContent("Mamou", cell.pictogramId, cell.category, cell.photoFile, cell.spokenText))

        val updated = dao.cells.value.single()
        assertEquals(Triple("Mamou", "m.jpg", "Mamie Jeanne"), Triple(updated.label, updated.photoFile, updated.spokenText))
    }

    private fun placeOf(dao: FakeBoardDao, label: String) = dao.cells.value.single { it.label == label }.let { it.row to it.column }

    private suspend fun BoardRepository.cellNamed(label: String) = checkNotNull(observeBoard(HOME_BOARD_ID).first()).cells.single { it.label == label }

    @Test
    fun `a cell moved to a free slot lands there and only there`() = runTest {
        val dao = FakeBoardDao(listOf(home), listOf(cell(1, HOME_BOARD_ID, 0, 0, "je"), cell(2, HOME_BOARD_ID, 0, 1, "veux")))
        val repository = repository(dao)

        repository.moveCell(HOME_BOARD_ID, repository.cellNamed("je"), toRow = 1, toColumn = 2)

        assertEquals(1 to 2, placeOf(dao, "je"))
        assertEquals(0 to 1, placeOf(dao, "veux"))
    }

    /** Lignes et colonnes différentes : un échange qui les inverserait se verrait. */
    @Test
    fun `a cell dropped on another swaps places with it`() = runTest {
        val dao = FakeBoardDao(listOf(home), listOf(cell(1, HOME_BOARD_ID, 1, 0, "je"), cell(2, HOME_BOARD_ID, 0, 2, "veux"), cell(3, HOME_BOARD_ID, 0, 1, "pas")))
        val repository = repository(dao)

        repository.moveCell(HOME_BOARD_ID, repository.cellNamed("je"), toRow = 0, toColumn = 2)

        assertEquals(0 to 2, placeOf(dao, "je"))
        assertEquals(1 to 0, placeOf(dao, "veux"))
        assertEquals(0 to 1, placeOf(dao, "pas"))
    }

    /** La case lue au début du geste a déjà bougé (un autre déplacement), d'une ligne ou d'une colonne seulement : rien n'est écrit. */
    @Test
    fun `a cell that moved since it was read is not moved again`() = runTest {
        for ((row, column) in listOf(1 to 0, 0 to 2)) {
            val dao = FakeBoardDao(listOf(home), listOf(cell(1, HOME_BOARD_ID, 0, 0, "je"), cell(2, HOME_BOARD_ID, 0, 1, "veux")))
            val repository = repository(dao)
            val stale = repository.cellNamed("je")
            repository.moveCell(HOME_BOARD_ID, stale, toRow = row, toColumn = column)

            assertThrows("($row, $column)", CellChangedException::class.java) { runBlocking { repository.moveCell(HOME_BOARD_ID, stale, toRow = 1, toColumn = 1) } }
            assertEquals(row to column, placeOf(dao, "je"))
            assertEquals(0 to 1, placeOf(dao, "veux"))
        }
    }

    @Test
    fun `a move outside the grid or of a deleted cell is refused`() = runTest {
        val dao = FakeBoardDao(listOf(home), listOf(cell(1, HOME_BOARD_ID, 0, 0, "je")))
        val repository = repository(dao)
        val je = repository.cellNamed("je")

        for ((row, column) in listOf(2 to 0, -1 to 0, 0 to 3, 0 to -1)) {
            assertThrows("($row, $column)", CellChangedException::class.java) { runBlocking { repository.moveCell(HOME_BOARD_ID, je, toRow = row, toColumn = column) } }
        }
        assertEquals(0 to 0, placeOf(dao, "je"))
        dao.deleteCell(je.id)
        assertThrows(CellNotFoundException::class.java) { runBlocking { repository.moveCell(HOME_BOARD_ID, je, toRow = 1, toColumn = 1) } }
    }

    /** La case d'une autre grille (grille affichée périmée) : rien n'est écrit. */
    @Test
    fun `a cell is only moved within its own board`() = runTest {
        val other = BoardEntity(id = 2, name = "other", rowCount = 2, columnCount = 3)
        val dao = FakeBoardDao(listOf(home, other), listOf(cell(1, HOME_BOARD_ID, 0, 0, "je")))
        val repository = repository(dao)
        val je = repository.cellNamed("je")

        assertThrows(CellChangedException::class.java) { runBlocking { repository.moveCell(2, je, toRow = 1, toColumn = 1) } }
        assertEquals(0 to 0, placeOf(dao, "je"))
    }

    /** Lâchée à sa place : aucune case n'est écrite. */
    @Test
    fun `a cell dropped on its own place writes nothing`() = runTest {
        val dao = FakeBoardDao(listOf(home), listOf(cell(1, HOME_BOARD_ID, 0, 0, "je")))
        val repository = repository(dao)
        dao.cellPositionFailure = SQLiteConstraintException("no write expected")

        repository.moveCell(HOME_BOARD_ID, repository.cellNamed("je"), toRow = 0, toColumn = 0)

        assertEquals(0 to 0, placeOf(dao, "je"))
    }
}

