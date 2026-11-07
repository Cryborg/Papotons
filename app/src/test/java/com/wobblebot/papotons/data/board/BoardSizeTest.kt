package com.wobblebot.papotons.data.board

import android.content.Context
import com.wobblebot.papotons.data.FakePreferences
import com.wobblebot.papotons.data.profile.FIRST_PROFILE_ID
import com.wobblebot.papotons.data.profile.ProfileEntity
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.domain.CellContent
import com.wobblebot.papotons.domain.HOME_BOARD_ID
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** Les tailles de cases (MVP-8) côté données : écran de départ par utilisateur, dossiers de l'adulte. */
class BoardSizeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = mockk<Context> {
        every { getString(any()) } answers { "label${firstArg<Int>()}" }
        every { filesDir } answers { folder.root }
    }

    private fun seededDao(startBoardId: Long? = null): FakeBoardDao {
        val seed = CoreVocabulary.build { "label$it" }
        return FakeBoardDao(seed.boards, seed.cells, listOf(ProfileEntity(FIRST_PROFILE_ID, "Léo", startBoardId)))
    }

    @Test
    fun `the launch opens the home of the chosen size`() = runTest {
        val dao = seededDao(startBoardId = BoardSize.LARGE_CELLS.homeBoardId)

        assertEquals(BoardSize.LARGE_CELLS.homeBoardId, BoardRepository(dao, FakePreferences(), context).ensureHomeBoard())
    }

    @Test
    fun `unreadable preferences still open the chosen start board`() = runTest {
        val preferences = FakePreferences().apply { readFailure = IOException("unreadable") }
        val dao = seededDao(startBoardId = BoardSize.MEDIUM.homeBoardId)

        assertEquals(BoardSize.MEDIUM.homeBoardId, BoardRepository(dao, preferences, context).ensureHomeBoard())
    }

    /** Grilles restaurées sans cet accueil : repli sur l'accueil standard, sans plantage. */
    @Test
    fun `a choice whose home is gone opens the standard home`() = runTest {
        val dao = FakeBoardDao(listOf(BoardEntity(HOME_BOARD_ID, "home", 2, 2)), profiles = listOf(ProfileEntity(FIRST_PROFILE_ID, "Léo", startBoardId = 13)))

        assertEquals(HOME_BOARD_ID, BoardRepository(dao, FakePreferences(), context).ensureHomeBoard())
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

        assertNull(repository.setStartBoard(BoardSize.MEDIUM))
        assertEquals(HOME_BOARD_ID, repository.ensureHomeBoard())
    }

    @Test
    fun `a chosen size is kept on the open profile and goes into the backup`() = runTest {
        val dao = seededDao()
        val repository = BoardRepository(dao, FakePreferences(), context)
        repository.ensureHomeBoard()

        assertEquals(BoardSize.MEDIUM.homeBoardId, repository.setStartBoard(BoardSize.MEDIUM))

        assertEquals(BoardSize.MEDIUM.homeBoardId, dao.profiles.value.single().startBoardId)
        assertEquals(BoardSize.MEDIUM.homeBoardId, repository.ensureHomeBoard())
        assertTrue(repository.observeUnsavedChanges().first())
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
        dao.boards.value += BoardEntity(1500, "restored", 1, 1, profileId = FIRST_PROFILE_ID)
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
            // Accueils posés explicitement : sinon la commodité du faux DAO prendrait un 7 visé pour un dossier.
            val sizeHome = BoardEntity(size.homeBoardId, "size home", 1, 1, profileId = FIRST_PROFILE_ID, coreBoardId = size.homeBoardId)
            val dao = FakeBoardDao(
                listOf(BoardEntity(HOME_BOARD_ID, "home", 2, 2, profileId = FIRST_PROFILE_ID, coreBoardId = HOME_BOARD_ID)) +
                    listOfNotNull(sizeHome.takeIf { size != BoardSize.STANDARD }) + BoardEntity(500, "other", 1, 1),
                listOf(CellEntity(id = 1, boardId = 500, row = 0, column = 0, label = "door", linkedBoardId = size.homeBoardId, pictogramId = null, category = null)),
            )
            val repository = BoardRepository(dao, FakePreferences(), context)
            val folderCell = checkNotNull(repository.observeBoard(500).first()).cells.single()

            repository.deleteCell(folderCell, boardId = 500, openRootBoardId = 500)

            assertTrue("$size home deleted", dao.boards.value.any { it.id == size.homeBoardId })
        }
    }
}
