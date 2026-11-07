package com.wobblebot.papotons.ui.settings

import android.content.Context
import android.database.sqlite.SQLiteException
import com.wobblebot.papotons.data.FakePreferences
import com.wobblebot.papotons.data.board.BoardRepository
import com.wobblebot.papotons.data.board.FakeBoardDao
import com.wobblebot.papotons.data.profile.FIRST_PROFILE_ID
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.domain.CellContent
import com.wobblebot.papotons.domain.HOME_BOARD_ID
import com.wobblebot.papotons.ui.board.FakePhotoStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

private const val HOUSE_PHOTO = "0a1b2c3d-0000-4000-8000-00000000000c.jpg"
private const val HOME_PHOTO = "0a1b2c3d-0000-4000-8000-00000000000d.jpg"

/** Un échange de rangs : l'écran au rang temporaire, le voisin à sa place, l'écran à celle du voisin. */
private const val SWAP_WRITES = 3

@OptIn(ExperimentalCoroutinesApi::class)
class ScreensViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val context = mockk<Context> {
        every { getString(any()) } answers { "label${firstArg<Int>()}" }
        every { filesDir } answers { File("build/tmp/no-files") }
    }
    private val dao = FakeBoardDao()
    private val repository = BoardRepository(dao, FakePreferences(), context)
    private val photos = FakePhotoStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(): ScreensViewModel = ScreensViewModel(repository, photos).also { viewModel ->
        backgroundScope.launch { viewModel.uiState.collect {} }
    }

    private fun screenIds() = dao.boards.value.filter { it.profileId == FIRST_PROFILE_ID && it.screenPosition != null }.sortedBy { it.screenPosition }.map { it.id }

    /** Une case avec photo posée à une place libre de [boardId]. */
    private suspend fun addPhotoWord(boardId: Long, photo: String) {
        val board = dao.boards.value.single { it.id == boardId }
        val taken = dao.cells.value.filter { it.boardId == boardId }.map { it.row to it.column }.toSet()
        val (row, column) = (0 until board.rowCount).flatMap { row -> (0 until board.columnCount).map { row to it } }.first { it !in taken }
        repository.addWord(boardId, row, column, CellContent("Mamie", null, null, photo, null))
    }

    @Test
    fun `deleting a screen deletes its photos, and only those`() = runTest(dispatcher) {
        repository.ensureHomeBoard()
        val house = repository.createScreen("Maison", BoardSize.MEDIUM)
        addPhotoWord(house, HOUSE_PHOTO)
        addPhotoWord(HOME_BOARD_ID, HOME_PHOTO)
        val viewModel = viewModel()

        viewModel.onDelete(house)

        assertEquals(listOf(HOUSE_PHOTO), photos.deleted)
        assertEquals(listOf(HOME_BOARD_ID), screenIds())
        assertNull(viewModel.uiState.value.failure)
    }

    @Test
    fun `a failed write is said, and a new try clears it`() = runTest(dispatcher) {
        repository.ensureHomeBoard()
        val viewModel = viewModel()
        dao.insertFailure = SQLiteException("disk full")

        viewModel.onCreate("Maison", BoardSize.LARGE_CELLS)

        assertEquals(ScreenFailure.NotCreated, viewModel.uiState.value.failure)
        assertFalse(viewModel.uiState.value.isWorking)
        dao.insertFailure = null
        viewModel.onCreate("Maison", BoardSize.LARGE_CELLS)
        assertNull(viewModel.uiState.value.failure)
        assertEquals(2, screenIds().size)
    }

    @Test
    fun `the last screen is not deleted, and the refusal is said`() = runTest(dispatcher) {
        repository.ensureHomeBoard()
        val viewModel = viewModel()

        viewModel.onDelete(HOME_BOARD_ID)

        assertEquals(ScreenFailure.NotDeleted, viewModel.uiState.value.failure)
        assertEquals(listOf(HOME_BOARD_ID), screenIds())
    }

    /** Deux appuis sur « Monter » pendant une écriture lente : un seul échange, le second ne défait pas le premier. */
    @Test
    fun `a second move while the first is written is ignored`() = runTest(dispatcher) {
        repository.ensureHomeBoard()
        val house = repository.createScreen("Maison", BoardSize.LARGE_CELLS)
        val viewModel = viewModel()
        val slowWrite = CompletableDeferred<Unit>()
        dao.screenPositionGate = slowWrite

        viewModel.onMove(house, -1)
        assertTrue(viewModel.uiState.value.isWorking)
        // Sans la garde, il partirait de l'ordre pas encore écrit et referait un échange.
        viewModel.onMove(house, -1)
        slowWrite.complete(Unit)

        assertEquals(listOf(house, HOME_BOARD_ID), screenIds())
        assertEquals(SWAP_WRITES, dao.screenPositionWrites)
        assertFalse(viewModel.uiState.value.isWorking)
    }
}
