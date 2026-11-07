package com.wobblebot.papotons.data.board

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.wobblebot.papotons.data.FakePreferences
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.domain.CellContent
import com.wobblebot.papotons.domain.HOME_BOARD_ID
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** Copie de la clé privée du dépôt : un renommage perdrait le choix de tous les utilisateurs. */
private val START_BOARD_ID = longPreferencesKey("start_board_id")

/** Les tailles de cases (MVP-8) côté données : écran de départ, dossiers de l'adulte. */
class BoardSizeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = mockk<Context> {
        every { getString(any()) } answers { "label${firstArg<Int>()}" }
        every { filesDir } answers { folder.root }
    }

    private fun seededDao(): FakeBoardDao {
        val seed = CoreVocabulary.build { "label$it" }
        return FakeBoardDao(seed.boards, seed.cells)
    }

    @Test
    fun `the launch opens the home of the chosen size`() = runTest {
        val preferences = FakePreferences(mutablePreferencesOf(START_BOARD_ID to BoardSize.LARGE_CELLS.homeBoardId))

        assertEquals(BoardSize.LARGE_CELLS.homeBoardId, BoardRepository(seededDao(), preferences, context).ensureHomeBoard())
    }

    @Test
    fun `an unreadable choice opens the standard home instead of crashing`() = runTest {
        val preferences = FakePreferences(mutablePreferencesOf(START_BOARD_ID to BoardSize.MEDIUM.homeBoardId))
        val repository = BoardRepository(seededDao(), preferences, context)
        repository.ensureHomeBoard()

        preferences.readFailure = IOException("unreadable")

        assertEquals(HOME_BOARD_ID, repository.ensureHomeBoard())
        // Lue de nouveau, la préférence n'a pas été écrasée par le repli.
        preferences.readFailure = null
        assertEquals(BoardSize.MEDIUM.homeBoardId, repository.ensureHomeBoard())
    }

    /** Grilles restaurées sans cet accueil : repli, et le choix est oublié pour ne pas revenir plus tard. */
    @Test
    fun `a choice whose home is gone opens the standard home and is forgotten`() = runTest {
        val preferences = FakePreferences(mutablePreferencesOf(START_BOARD_ID to BoardSize.LARGE_CELLS.homeBoardId))
        val dao = FakeBoardDao(listOf(BoardEntity(HOME_BOARD_ID, "home", 2, 2)))

        assertEquals(HOME_BOARD_ID, BoardRepository(dao, preferences, context).ensureHomeBoard())
        assertNull(preferences.current[START_BOARD_ID])
    }

    /** L'oubli est au mieux : s'il ne s'écrit pas, on parle quand même, sans plantage à chaque lancement. */
    @Test
    fun `a choice that cannot be forgotten still opens the standard home`() = runTest {
        val preferences = FakePreferences(mutablePreferencesOf(START_BOARD_ID to BoardSize.LARGE_CELLS.homeBoardId))
        preferences.writeFailure = IOException("read only")
        val dao = FakeBoardDao(listOf(BoardEntity(HOME_BOARD_ID, "home", 2, 2)))

        assertEquals(HOME_BOARD_ID, BoardRepository(dao, preferences, context).ensureHomeBoard())
    }

    /** Base d'avant les tailles : l'id 7 y est un dossier de l'adulte, pas un accueil. */
    @Test
    fun `a size home that is a folder of the adult is never opened as a start`() = runTest {
        val dao = FakeBoardDao(
            listOf(BoardEntity(HOME_BOARD_ID, "home", 2, 2), BoardEntity(7, "Mamie", 4, 6)),
            listOf(CellEntity(id = 1, boardId = HOME_BOARD_ID, row = 0, column = 0, label = "Mamie", linkedBoardId = 7, pictogramId = null, category = null)),
        )
        val repository = BoardRepository(dao, FakePreferences(), context)
        repository.ensureHomeBoard()

        assertFalse(repository.setStartBoard(BoardSize.MEDIUM))
        assertEquals(HOME_BOARD_ID, repository.ensureHomeBoard())
    }

    @Test
    fun `a choice that cannot be written changes nothing and says so`() = runTest {
        val preferences = FakePreferences()
        val repository = BoardRepository(seededDao(), preferences, context)
        repository.ensureHomeBoard()
        preferences.writeFailure = IOException("disk full")

        assertThrows(IOException::class.java) { runBlocking { repository.setStartBoard(BoardSize.MEDIUM) } }
        preferences.writeFailure = null
        assertEquals(HOME_BOARD_ID, repository.ensureHomeBoard())
    }

    @Test
    fun `a folder of the adult takes the folder size of its set and an id above the core vocabulary`() = runTest {
        val dao = seededDao()
        val repository = BoardRepository(dao, FakePreferences(), context)
        repository.ensureHomeBoard()

        // Ligne 4 de l'accueil des moyennes : libre sous les premières colonnes.
        repository.addFolder(BoardSize.MEDIUM.homeBoardId, 4, 0, CellContent("Animaux", null, null, null, null), BoardSize.MEDIUM)

        val board = dao.boards.value.single { it.name == "Animaux" }
        assertEquals(BoardSize.MEDIUM.folderRowCount to BoardSize.MEDIUM.folderColumnCount, board.rowCount to board.columnCount)
        assertTrue("id ${board.id}", board.id >= FIRST_USER_BOARD_ID)
    }

    @Test
    fun `folders of the adult take successive ids from the reserved start, or after a larger one`() = runTest {
        val dao = FakeBoardDao(listOf(BoardEntity(HOME_BOARD_ID, "home", 2, 3)))
        val repository = BoardRepository(dao, FakePreferences(), context)
        for (column in 0 until 3) repository.addFolder(HOME_BOARD_ID, 0, column, CellContent("d$column", null, null, null, null), BoardSize.STANDARD)

        assertEquals(listOf(1000L, 1001L, 1002L), dao.cells.value.sortedBy { it.column }.map { it.linkedBoardId })

        // Une base restaurée peut déjà dépasser la réserve.
        dao.boards.value += BoardEntity(1500, "restored", 1, 1)
        repository.addFolder(HOME_BOARD_ID, 1, 0, CellContent("next", null, null, null, null), BoardSize.STANDARD)
        assertEquals(1501L, dao.cells.value.single { it.label == "next" }.linkedBoardId)
    }

    /**
     * Un accueil de taille ne doit jamais partir avec un dossier qui l'ouvrait (sauvegarde bricolée).
     * Choix assumé : dans une base d'avant les tailles, un dossier de l'adulte à l'id 7 ou 13 reste
     * après la suppression de sa case (caché, rien de perdu), plutôt que risquer un vrai accueil.
     */
    @Test
    fun `deleting a folder never deletes the home of a size`() = runTest {
        for (size in BoardSize.entries) {
            val dao = FakeBoardDao(
                listOf(BoardEntity(HOME_BOARD_ID, "home", 2, 2)) + listOfNotNull(BoardEntity(size.homeBoardId, "size home", 1, 1).takeIf { size != BoardSize.STANDARD }) + BoardEntity(500, "other", 1, 1),
                listOf(CellEntity(id = 1, boardId = 500, row = 0, column = 0, label = "door", linkedBoardId = size.homeBoardId, pictogramId = null, category = null)),
            )
            val repository = BoardRepository(dao, FakePreferences(), context)
            val folderCell = checkNotNull(repository.observeBoard(500).first()).cells.single()

            repository.deleteCell(folderCell, boardId = 500, openRootBoardId = 500)

            assertTrue("$size home deleted", dao.boards.value.any { it.id == size.homeBoardId })
        }
    }
}
