package com.wobblebot.papotons.ui.board

import android.content.Context
import androidx.lifecycle.ViewModelStore
import com.wobblebot.papotons.data.board.BoardEntity
import com.wobblebot.papotons.data.board.BoardRepository
import com.wobblebot.papotons.data.board.CellEntity
import com.wobblebot.papotons.data.board.FakeBoardDao
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.HOME_BOARD_ID
import com.wobblebot.papotons.speech.SpeechStatus
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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

@OptIn(ExperimentalCoroutinesApi::class)
class BoardViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val speaker = FakeSpeaker()
    private val context = mockk<Context> {
        every { getString(any()) } answers { "label${firstArg<Int>()}" }
    }

    // Accueil → dossier « A » (grille 2) → dossier « B » (grille 3) : une pile de trois
    // distingue « Retour » (un cran) de « Accueil » (la racine).
    private val boards = listOf(
        BoardEntity(id = HOME_BOARD_ID, name = "home", rowCount = 2, columnCount = 2),
        BoardEntity(id = 2, name = "second", rowCount = 1, columnCount = 2),
        BoardEntity(id = 3, name = "third", rowCount = 2, columnCount = 1),
    )
    private val cells = listOf(
        CellEntity(id = 1, boardId = HOME_BOARD_ID, row = 0, column = 0, label = "je", linkedBoardId = null, pictogramId = null, category = null),
        CellEntity(id = 2, boardId = HOME_BOARD_ID, row = 0, column = 1, label = "veux", linkedBoardId = null, pictogramId = null, category = null),
        CellEntity(id = 3, boardId = HOME_BOARD_ID, row = 1, column = 0, label = "A", linkedBoardId = 2, pictogramId = null, category = null),
        CellEntity(id = 4, boardId = HOME_BOARD_ID, row = 1, column = 1, label = "lost", linkedBoardId = 99, pictogramId = null, category = null),
        CellEntity(id = 5, boardId = 2, row = 0, column = 0, label = "B", linkedBoardId = 3, pictogramId = null, category = null),
        CellEntity(id = 6, boardId = 3, row = 0, column = 0, label = "rouge", linkedBoardId = null, pictogramId = null, category = null),
        CellEntity(id = 8, boardId = 3, row = 1, column = 0, label = "ai", linkedBoardId = null, category = "VERB", pictogramId = null),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.startViewModel(dao: FakeBoardDao = FakeBoardDao(boards, cells)): BoardViewModel {
        val viewModel = BoardViewModel(BoardRepository(dao, context), speaker)
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    private fun BoardViewModel.state() = uiState.value

    private fun BoardViewModel.tap(label: String) {
        val cell: Cell = checkNotNull(state().board).grid.flatten().filterNotNull().first { it.label == label }
        onCellClick(cell)
    }

    @Test
    fun `nothing is shown until the first launch insertion is done`() = runTest(dispatcher) {
        val dao = FakeBoardDao().apply { insertGate = CompletableDeferred() }
        val viewModel = startViewModel(dao)

        assertNull(viewModel.state().board)
        checkNotNull(dao.insertGate).complete(Unit)

        assertEquals(HOME_BOARD_ID, viewModel.state().board?.id)
    }

    @Test
    fun `the board is shown with its name and each cell at its position`() = runTest(dispatcher) {
        // Grille 2 × 3 non carrée : des dimensions inversées déplaceraient « coin ».
        val dao = FakeBoardDao(
            boards = listOf(BoardEntity(id = HOME_BOARD_ID, name = "wide", rowCount = 2, columnCount = 3)),
            cells = listOf(CellEntity(id = 1, boardId = HOME_BOARD_ID, row = 1, column = 2, label = "coin", linkedBoardId = null, pictogramId = null, category = null)),
        )

        val board = checkNotNull(startViewModel(dao).state().board)

        assertEquals("wide", board.name)
        assertEquals(2, board.grid.size)
        assertEquals(listOf(3, 3), board.grid.map { it.size })
        assertEquals("coin", board.grid[1][2]?.label)
    }

    @Test
    fun `waking the screen up never shows the loading state again`() = runTest(dispatcher) {
        val viewModel = BoardViewModel(BoardRepository(FakeBoardDao(boards, cells), context), speaker)
        val firstCollector = backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.tap("A")
        firstCollector.cancel()
        advanceTimeBy(STOP_TIMEOUT_MS + 1)

        val states = mutableListOf<BoardUiState>()
        backgroundScope.launch { viewModel.uiState.collect { states += it } }

        assertTrue(states.isNotEmpty())
        assertTrue(states.all { it.board?.id == 2L })
    }

    @Test
    fun `a word is spoken and added at the end of the message`() = runTest(dispatcher) {
        val viewModel = startViewModel()

        viewModel.tap("veux")
        viewModel.tap("je")

        assertEquals(listOf("veux", "je"), speaker.spoken)
        assertEquals(listOf("veux", "je"), viewModel.state().message)
        assertTrue(viewModel.state().isComposingMessage)
    }

    @Test
    fun `a folder opens its board without speaking or adding anything`() = runTest(dispatcher) {
        val viewModel = startViewModel()

        viewModel.tap("A")

        assertEquals(2L, viewModel.state().board?.id)
        assertEquals(true, viewModel.state().board?.canGoBack)
        assertTrue(speaker.spoken.isEmpty())
        assertTrue(viewModel.state().message.isEmpty())
    }

    @Test
    fun `back goes up one board`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        viewModel.tap("A")
        viewModel.tap("B")

        viewModel.onBack()

        assertEquals(2L, viewModel.state().board?.id)
        assertEquals(true, viewModel.state().board?.canGoBack)
    }

    @Test
    fun `home goes back to the root board`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        viewModel.tap("A")
        viewModel.tap("B")

        viewModel.onHome()

        assertEquals(HOME_BOARD_ID, viewModel.state().board?.id)
        assertEquals(false, viewModel.state().board?.canGoBack)
    }

    @Test
    fun `back on the home board stays there`() = runTest(dispatcher) {
        val viewModel = startViewModel()

        viewModel.onBack()

        assertEquals(HOME_BOARD_ID, viewModel.state().board?.id)
        assertEquals(false, viewModel.state().board?.canGoBack)
    }

    @Test
    fun `a folder whose board is missing leads back where it was`() = runTest(dispatcher) {
        val viewModel = startViewModel()

        viewModel.tap("lost")

        assertEquals(HOME_BOARD_ID, viewModel.state().board?.id)
    }

    @Test
    fun `a missing board deeper in the stack leads back one level only`() = runTest(dispatcher) {
        val dao = FakeBoardDao(
            boards,
            cells + CellEntity(id = 7, boardId = 2, row = 0, column = 1, label = "gone", linkedBoardId = 98, pictogramId = null, category = null),
        )
        val viewModel = startViewModel(dao)
        viewModel.tap("A")

        viewModel.tap("gone")

        assertEquals(2L, viewModel.state().board?.id)
        assertEquals(true, viewModel.state().board?.canGoBack)
    }

    @Test
    fun `a second tap while the next board loads does not stack it twice`() = runTest(dispatcher) {
        val dao = FakeBoardDao(boards, cells)
        val viewModel = startViewModel(dao)
        dao.areReadsHeld.value = true

        viewModel.tap("A")
        viewModel.tap("A")
        dao.areReadsHeld.value = false
        viewModel.onBack()

        assertEquals(HOME_BOARD_ID, viewModel.state().board?.id)
    }

    @Test
    fun `a second back while the previous board loads goes up one level only`() = runTest(dispatcher) {
        val dao = FakeBoardDao(boards, cells)
        val viewModel = startViewModel(dao)
        viewModel.tap("A")
        viewModel.tap("B")
        dao.areReadsHeld.value = true

        viewModel.onBack()
        viewModel.onBack()
        dao.areReadsHeld.value = false

        assertEquals(2L, viewModel.state().board?.id)
    }

    @Test
    fun `a sentence is built across folders and survives going home`() = runTest(dispatcher) {
        val viewModel = startViewModel()

        viewModel.tap("je")
        viewModel.tap("A")
        viewModel.tap("B")
        viewModel.tap("rouge")
        viewModel.onHome()

        assertEquals(listOf("je", "rouge"), viewModel.state().message)
    }

    @Test
    fun `the whole sentence is spoken in order`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        viewModel.tap("veux")
        viewModel.tap("je")
        speaker.spoken.clear()

        viewModel.onSpeakMessage()

        assertEquals(listOf("veux je"), speaker.spoken)
    }

    @Test
    fun `an empty sentence is never sent to the voice`() = runTest(dispatcher) {
        val viewModel = startViewModel()

        viewModel.onSpeakMessage()

        assertTrue(speaker.spoken.isEmpty())
    }

    @Test
    fun `je before a verb is shown and spoken elided, and delete removes the last touched word`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        viewModel.tap("je")
        viewModel.tap("A")
        viewModel.tap("B")
        viewModel.tap("ai")
        speaker.spoken.clear()

        assertEquals(listOf("j'ai"), viewModel.state().message)
        viewModel.onSpeakMessage()
        assertEquals(listOf("j'ai"), speaker.spoken)
        viewModel.onDeleteLastWord()
        assertEquals(listOf("je"), viewModel.state().message)
    }

    @Test
    fun `delete removes only the last word even when it is repeated`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        viewModel.tap("je")
        viewModel.tap("veux")
        viewModel.tap("je")

        viewModel.onDeleteLastWord()

        assertEquals(listOf("je", "veux"), viewModel.state().message)
    }

    @Test
    fun `delete and clear on an empty sentence change nothing`() = runTest(dispatcher) {
        val viewModel = startViewModel()

        viewModel.onDeleteLastWord()
        viewModel.onClearMessage()

        assertTrue(viewModel.state().message.isEmpty())
    }

    @Test
    fun `clear empties the message`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        viewModel.tap("veux")
        viewModel.tap("je")

        viewModel.onClearMessage()

        assertTrue(viewModel.state().message.isEmpty())
        assertFalse(viewModel.state().isComposingMessage)
    }

    @Test
    fun `the voice status reaches the screen`() = runTest(dispatcher) {
        val viewModel = startViewModel()

        speaker.status.value = SpeechStatus.Unavailable

        assertEquals(SpeechStatus.Unavailable, viewModel.state().speechStatus)
    }

    @Test
    fun `recovering the voice asks the speaker`() = runTest(dispatcher) {
        val viewModel = startViewModel()

        viewModel.recoverSpeech()

        assertEquals(1, speaker.recoverCount)
    }

    @Test
    fun `the voice is released with the view model`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        val store = ViewModelStore()
        store.put("board", viewModel)

        store.clear()

        assertTrue(speaker.isShutDown)
    }
}
