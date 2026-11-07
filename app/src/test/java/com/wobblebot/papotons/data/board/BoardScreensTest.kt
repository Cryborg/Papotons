package com.wobblebot.papotons.data.board

import android.content.Context
import com.wobblebot.papotons.data.FakePreferences
import com.wobblebot.papotons.data.backup.MAX_BOARDS
import com.wobblebot.papotons.data.backup.MAX_BOARD_ID
import com.wobblebot.papotons.data.backup.MAX_CELLS
import com.wobblebot.papotons.data.profile.FIRST_PROFILE_ID
import com.wobblebot.papotons.data.profile.ProfileEntity
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.domain.CellContent
import com.wobblebot.papotons.domain.HOME_BOARD_ID
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** Les écrans côté données : premier écran, création dans une taille, suppression, ordre, dossiers. */
class BoardScreensTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = mockk<Context> {
        every { getString(any()) } answers { "label${firstArg<Int>()}" }
        every { filesDir } answers { folder.root }
    }

    private val seed = CoreVocabulary.build { "label$it" }

    /** Toute la graine d'avant les écrans (trois tailles), comme une base migrée : le premier écran vient de [startBoardId]. */
    private fun migratedDao(startBoardId: Long? = null): FakeBoardDao =
        FakeBoardDao(seed.boards, seed.cells, listOf(ProfileEntity(FIRST_PROFILE_ID, "Léo", startBoardId)))

    private suspend fun freshRepository(dao: FakeBoardDao = FakeBoardDao()): BoardRepository =
        BoardRepository(dao, FakePreferences(), context).also { it.ensureHomeBoard() }

    private fun FakeBoardDao.screenIds(profileId: Long = FIRST_PROFILE_ID) =
        boards.value.filter { it.profileId == profileId && it.screenPosition != null }.sortedBy { it.screenPosition }.map { it.id }

    @Test
    fun `a migrated base opens its former start board`() = runTest {
        val dao = migratedDao(startBoardId = BoardSize.LARGE_CELLS.homeBoardId)

        assertEquals(BoardSize.LARGE_CELLS.homeBoardId, BoardRepository(dao, FakePreferences(), context).ensureHomeBoard())
    }

    @Test
    fun `unreadable preferences still open the first screen`() = runTest {
        val preferences = FakePreferences().apply { readFailure = IOException("unreadable") }
        val dao = migratedDao(startBoardId = BoardSize.MEDIUM.homeBoardId)

        assertEquals(BoardSize.MEDIUM.homeBoardId, BoardRepository(dao, preferences, context).ensureHomeBoard())
    }

    /** Base d'avant les tailles : l'id 7 y est un dossier de l'adulte, jamais un écran. */
    @Test
    fun `a former start board opened by a folder is not taken as the first screen`() = runTest {
        val dao = FakeBoardDao(
            listOf(BoardEntity(HOME_BOARD_ID, "home", 2, 2), BoardEntity(7, "Mamie", 4, 6)),
            listOf(CellEntity(id = 1, boardId = HOME_BOARD_ID, row = 0, column = 0, label = "Mamie", linkedBoardId = 7, pictogramId = null, category = null)),
            listOf(ProfileEntity(FIRST_PROFILE_ID, "Léo", startBoardId = 7)),
        )

        assertEquals(HOME_BOARD_ID, BoardRepository(dao, FakePreferences(), context).ensureHomeBoard())
        assertEquals(listOf(HOME_BOARD_ID), dao.screenIds())
    }

    /** Base abîmée sans aucun écran : on parle quand même, depuis la plus petite grille. */
    @Test
    fun `a profile without screens opens its smallest board`() = runTest {
        val dao = FakeBoardDao(listOf(BoardEntity(5, "five", 1, 1, profileId = FIRST_PROFILE_ID), BoardEntity(3, "three", 1, 1, profileId = FIRST_PROFILE_ID)))
        dao.boards.value = dao.boards.value.map { it.copy(screenPosition = null) }

        assertEquals(3L, BoardRepository(dao, FakePreferences(), context).ensureHomeBoard())
    }

    @Test
    fun `a fresh install gets the standard home only, as its first screen`() = runTest {
        val dao = FakeBoardDao()
        freshRepository(dao)

        assertEquals(seed.subtree(HOME_BOARD_ID).boards.map { it.id }, dao.boards.value.map { it.id })
        assertEquals(listOf(HOME_BOARD_ID), dao.screenIds())
    }

    @Test
    fun `a new screen gets the vocabulary of its size, after the last screen, without opening`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val homeBefore = repository.ensureHomeBoard()

        val screenId = repository.createScreen("Maison", BoardSize.LARGE_CELLS)

        assertEquals(listOf(HOME_BOARD_ID, screenId), dao.screenIds())
        val created = dao.boards.value.filter { it.id >= FIRST_USER_BOARD_ID }
        assertEquals(listOf(BoardSize.LARGE_CELLS.homeBoardId, 14L), created.map { it.coreBoardId })
        assertEquals("Maison", created.single { it.id == screenId }.name)
        assertNull(created.single { it.id != screenId }.screenPosition)
        // Les cases aux positions de l'instantané (invariant 3).
        val originalPlaces = seed.cells.filter { it.boardId == BoardSize.LARGE_CELLS.homeBoardId }.map { Triple(it.row, it.column, it.label) }
        assertEquals(originalPlaces.toSet(), dao.cells.value.filter { it.boardId == screenId }.map { Triple(it.row, it.column, it.label) }.toSet())
        assertEquals(homeBefore, repository.ensureHomeBoard())
        assertTrue(repository.observeUnsavedChanges().first())
    }

    @Test
    fun `screens beyond the limit are refused`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        repeat(MAX_SCREENS - 1) { repository.createScreen("Écran $it", BoardSize.LARGE_CELLS) }
        val boardCount = dao.boards.value.size

        assertThrows(TooManyScreensException::class.java) { runBlocking { repository.createScreen("De trop", BoardSize.LARGE_CELLS) } }
        assertEquals(boardCount, dao.boards.value.size)
    }

    @Test
    fun `deleting a screen takes its folders but not those of another screen`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val home = dao.boards.value.map { it.id }.toSet()
        val school = repository.createScreen("École", BoardSize.LARGE_CELLS)
        val schoolBoards = dao.boards.value.map { it.id }.toSet() - home
        val house = repository.createScreen("Maison", BoardSize.MEDIUM)

        repository.deleteScreen(house)

        assertEquals(home + schoolBoards, dao.boards.value.map { it.id }.toSet())
        assertEquals(listOf(HOME_BOARD_ID, school), dao.screenIds())
    }

    @Test
    fun `the last screen cannot be deleted`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val boards = dao.boards.value

        assertThrows(LastScreenException::class.java) { runBlocking { repository.deleteScreen(HOME_BOARD_ID) } }
        assertEquals(boards, dao.boards.value)
    }

    /** Un id périmé (une restauration a réutilisé les ids) ne supprime jamais un dossier. */
    @Test
    fun `a board that is not a screen is never deleted as one`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        repository.createScreen("Maison", BoardSize.LARGE_CELLS)
        val folderId = seed.subtree(HOME_BOARD_ID).boards.first { it.id != HOME_BOARD_ID }.id
        val boards = dao.boards.value

        assertThrows(ScreenNotFoundException::class.java) { runBlocking { repository.deleteScreen(folderId) } }
        assertThrows(ScreenNotFoundException::class.java) { runBlocking { repository.renameScreen(folderId, "Autre") } }
        assertEquals(boards, dao.boards.value)
    }

    /** Données abîmées : une case ouvre un écran. Rien n'est supprimé à moitié. */
    @Test
    fun `a screen still opened by a folder is not deleted`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val house = repository.createScreen("Maison", BoardSize.LARGE_CELLS)
        dao.insertCell(CellEntity(boardId = HOME_BOARD_ID, row = 6, column = 7, label = "door", linkedBoardId = house, pictogramId = null, category = null))
        val boards = dao.boards.value

        assertThrows(ScreenStillLinkedException::class.java) { runBlocking { repository.deleteScreen(house) } }
        assertEquals(boards, dao.boards.value)
    }

    @Test
    fun `a screen is renamed`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val house = repository.createScreen("Maison", BoardSize.LARGE_CELLS)

        repository.renameScreen(house, "Chez papi")

        assertEquals("Chez papi", dao.boards.value.single { it.id == house }.name)
    }

    /** Une suppression laisse un trou dans les rangs : monter échange avec le vrai voisin. */
    @Test
    fun `moving a screen swaps it with its real neighbor, even across a gap`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val school = repository.createScreen("École", BoardSize.LARGE_CELLS)
        val park = repository.createScreen("Parc", BoardSize.LARGE_CELLS)
        val house = repository.createScreen("Maison", BoardSize.LARGE_CELLS)
        repository.deleteScreen(park)

        repository.moveScreen(house, -1)
        assertEquals(listOf(HOME_BOARD_ID, house, school), dao.screenIds())

        repository.moveScreen(HOME_BOARD_ID, -1)
        assertEquals(listOf(HOME_BOARD_ID, house, school), dao.screenIds())

        repository.moveScreen(HOME_BOARD_ID, 1)
        assertEquals(listOf(house, HOME_BOARD_ID, school), dao.screenIds())
        assertEquals(house, repository.ensureHomeBoard())
    }

    @Test
    fun `a folder of the adult takes the folder size of its screen and an id above the core vocabulary`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val medium = repository.createScreen("Moyen", BoardSize.MEDIUM)
        val freeRow = BoardSize.MEDIUM.homeRowCount - 1
        val freeColumn = (0 until BoardSize.MEDIUM.homeColumnCount).first { column -> dao.cells.value.none { it.boardId == medium && it.row == freeRow && it.column == column } }

        repository.addFolder(medium, freeRow, freeColumn, CellContent("Animaux", null, null, null, null), BoardSize.MEDIUM)

        val board = dao.boards.value.single { it.name == "Animaux" }
        assertEquals(BoardSize.MEDIUM.folderRowCount to BoardSize.MEDIUM.folderColumnCount, board.rowCount to board.columnCount)
        assertTrue("id ${board.id}", board.id >= FIRST_USER_BOARD_ID)
        assertNull(board.screenPosition)
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

    /** Un écran ne part jamais avec un dossier qui l'ouvrait (données abîmées). */
    @Test
    fun `deleting a folder never deletes a screen`() = runTest {
        val dao = FakeBoardDao(
            listOf(
                BoardEntity(HOME_BOARD_ID, "home", 2, 2, profileId = FIRST_PROFILE_ID, screenPosition = 0),
                BoardEntity(1000, "house", 1, 1, profileId = FIRST_PROFILE_ID, screenPosition = 1),
                BoardEntity(500, "other", 1, 1, profileId = FIRST_PROFILE_ID),
            ),
            listOf(CellEntity(id = 1, boardId = 500, row = 0, column = 0, label = "door", linkedBoardId = 1000, pictogramId = null, category = null)),
        )
        val repository = BoardRepository(dao, FakePreferences(), context)
        val folderCell = checkNotNull(repository.observeBoard(500).first()).cells.single()

        repository.deleteCell(folderCell, boardId = 500, openRootBoardId = 500)

        assertTrue(dao.boards.value.any { it.id == 1000L })
    }

    /** Un id d'écran d'un autre utilisateur (périmé après une restauration) : rien ne bouge chez personne. */
    @Test
    fun `screen actions never reach the screens of another profile`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val leoHouse = repository.createScreen("Maison", BoardSize.LARGE_CELLS)
        val anna = repository.createProfile("Anna")
        repository.switchProfile(anna)
        val annaHome = dao.screenIds(anna).single()
        val boards = dao.boards.value

        // Anna n'a qu'un écran : le dernier de SON profil, même si Léo en a deux.
        assertThrows(LastScreenException::class.java) { runBlocking { repository.deleteScreen(annaHome) } }
        assertThrows(ScreenNotFoundException::class.java) { runBlocking { repository.deleteScreen(leoHouse) } }
        assertThrows(ScreenNotFoundException::class.java) { runBlocking { repository.moveScreen(leoHouse, -1) } }
        assertThrows(ScreenNotFoundException::class.java) { runBlocking { repository.renameScreen(leoHouse, "Volé") } }
        repository.moveScreen(annaHome, -1)

        assertEquals(boards, dao.boards.value)
    }

    /** Créé après une suppression qui a laissé un trou : toujours en dernier, jamais sur un rang pris. */
    @Test
    fun `a screen created after a deletion still comes last`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val school = repository.createScreen("École", BoardSize.LARGE_CELLS)
        val park = repository.createScreen("Parc", BoardSize.LARGE_CELLS)
        val house = repository.createScreen("Maison", BoardSize.LARGE_CELLS)
        repository.deleteScreen(park)

        val garden = repository.createScreen("Jardin", BoardSize.LARGE_CELLS)

        assertEquals(listOf(HOME_BOARD_ID, school, house, garden), dao.screenIds())
    }

    /** L'export doit rester possible : jamais plus de grilles que la sauvegarde n'en accepte, borne exacte. */
    @Test
    fun `a screen that would exceed the boards of a backup is refused, one that fits is created`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val largeBoardCount = seed.subtree(BoardSize.LARGE_CELLS.homeBoardId).boards.size
        val firstFillerId = 100_000L
        fun fillUpTo(boardCount: Int) {
            val missing = boardCount - dao.boards.value.size
            dao.boards.value += (0 until missing).map { BoardEntity(firstFillerId + it, "rempli", 1, 1, profileId = FIRST_PROFILE_ID) }
        }
        fillUpTo(MAX_BOARDS - largeBoardCount + 1)

        assertThrows(TooManyScreensException::class.java) { runBlocking { repository.createScreen("De trop", BoardSize.LARGE_CELLS) } }
        assertEquals(MAX_BOARDS - largeBoardCount + 1, dao.boards.value.size)

        dao.boards.value = dao.boards.value.filter { it.id != firstFillerId }
        repository.createScreen("Juste", BoardSize.LARGE_CELLS)
        assertEquals(MAX_BOARDS, dao.boards.value.size)
    }

    @Test
    fun `a screen that would exceed the cells of a backup is refused, one that fits is created`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val largeCellCount = seed.subtree(BoardSize.LARGE_CELLS.homeBoardId).cells.size
        dao.extraCellCount = MAX_CELLS - dao.cells.value.size - largeCellCount + 1
        val boardCount = dao.boards.value.size

        assertThrows(TooManyScreensException::class.java) { runBlocking { repository.createScreen("De trop", BoardSize.LARGE_CELLS) } }
        assertEquals(boardCount, dao.boards.value.size)

        dao.extraCellCount -= 1
        repository.createScreen("Juste", BoardSize.LARGE_CELLS)
        assertEquals(MAX_CELLS, dao.countCells())
    }

    /** Une sauvegarde retouchée peut porter une grille d'id très haut : l'écran suivant ne doit pas dépasser la borne. */
    @Test
    fun `a screen whose board ids would pass the backup limit is refused, one that fits is created`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        val largeBoardCount = seed.subtree(BoardSize.LARGE_CELLS.homeBoardId).boards.size
        val tooHigh = MAX_BOARD_ID - largeBoardCount + 1
        dao.boards.value += BoardEntity(tooHigh, "rempli", 1, 1, profileId = FIRST_PROFILE_ID)

        assertThrows(TooManyScreensException::class.java) { runBlocking { repository.createScreen("De trop", BoardSize.LARGE_CELLS) } }

        dao.boards.value = dao.boards.value.map { if (it.id == tooHigh) it.copy(id = tooHigh - 1) else it }
        repository.createScreen("Juste", BoardSize.LARGE_CELLS)
        assertEquals(MAX_BOARD_ID, dao.boards.value.maxOf { it.id })
    }

    /** Un rang maximal ne vient que d'une sauvegarde retouchée : l'écran suivant ne déborde pas, il est refusé. */
    @Test
    fun `a screen after the largest accepted position is refused instead of overflowing`() = runTest {
        val dao = FakeBoardDao()
        val repository = freshRepository(dao)
        dao.boards.value = dao.boards.value.map { if (it.id == HOME_BOARD_ID) it.copy(screenPosition = MAX_SCREEN_POSITION - 1) else it }
        val last = repository.createScreen("Maison", BoardSize.LARGE_CELLS)
        assertEquals(MAX_SCREEN_POSITION, dao.boards.value.single { it.id == last }.screenPosition)

        assertThrows(TooManyScreensException::class.java) { runBlocking { repository.createScreen("École", BoardSize.LARGE_CELLS) } }
        assertEquals(listOf(HOME_BOARD_ID, last), dao.screenIds())
    }
}

