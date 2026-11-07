package com.wobblebot.papotons.ui.board

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.wobblebot.papotons.data.FakePreferences
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
import java.io.File

/** Nom persisté du marqueur : le changer ferait passer toute installation existante pour neuve. */
private val CORE_VOCABULARY_SEEDED = booleanPreferencesKey("core_vocabulary_seeded")

@OptIn(ExperimentalCoroutinesApi::class)
class BoardViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val speaker = FakeSpeaker()
    private val volume = FakeMediaVolume()
    private val preferences = FakePreferences()
    private val context = mockk<Context> {
        every { getString(any()) } answers { "label${firstArg<Int>()}" }
        // Aucune copie de base corrompue.
        every { filesDir } returns File("build/tmp/no-files")
    }

    // Accueil → dossier « A » (grille 2) → dossier « B » (grille 3) : une pile de trois
    // distingue « Retour » (un cran) de « Accueil » (la racine).
    private val boards = listOf(
        BoardEntity(id = HOME_BOARD_ID, name = "home", rowCount = 2, columnCount = 2),
        BoardEntity(id = 2, name = "second", rowCount = 1, columnCount = 2),
        BoardEntity(id = 3, name = "third", rowCount = 2, columnCount = 1),
    )
    private val cells = listOf(
        CellEntity(id = 1, boardId = HOME_BOARD_ID, row = 0, column = 0, label = "je", linkedBoardId = null, pictogramId = 10, category = null),
        CellEntity(id = 2, boardId = HOME_BOARD_ID, row = 0, column = 1, label = "veux", linkedBoardId = null, pictogramId = null, category = null),
        CellEntity(id = 3, boardId = HOME_BOARD_ID, row = 1, column = 0, label = "A", linkedBoardId = 2, pictogramId = null, category = null),
        CellEntity(id = 4, boardId = HOME_BOARD_ID, row = 1, column = 1, label = "lost", linkedBoardId = 99, pictogramId = null, category = null),
        CellEntity(id = 5, boardId = 2, row = 0, column = 0, label = "B", linkedBoardId = 3, pictogramId = null, category = null),
        CellEntity(id = 6, boardId = 3, row = 0, column = 0, label = "rouge", linkedBoardId = null, pictogramId = null, category = null),
        CellEntity(id = 8, boardId = 3, row = 1, column = 0, label = "ai", linkedBoardId = null, category = "VERB", pictogramId = 80),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.startViewModel(
        dao: FakeBoardDao = FakeBoardDao(boards, cells),
        savedState: SavedStateHandle = SavedStateHandle(),
        voice: FakeSpeaker = speaker,
    ): BoardViewModel {
        val viewModel = BoardViewModel(BoardRepository(dao, preferences, context), voice, volume, savedState)
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    private fun BoardViewModel.state() = uiState.value

    private fun BoardViewModel.messageTexts() = state().message.map { it.text }

    private fun BoardViewModel.tap(label: String) {
        val cell: Cell = checkNotNull(state().board).grid.flatten().filterNotNull().first { it.label == label }
        onCellClick(cell)
    }

    @Test
    fun `nothing is shown until the first launch insertion is done`() = runTest(dispatcher) {
        val dao = FakeBoardDao().apply { insertGate = CompletableDeferred() }
        val viewModel = startViewModel(dao)

        assertNull(viewModel.state().board)
        // Le marqueur suit l'insertion, jamais ne la précède.
        assertNull(preferences.current[CORE_VOCABULARY_SEEDED])
        checkNotNull(dao.insertGate).complete(Unit)

        assertEquals(HOME_BOARD_ID, viewModel.state().board?.id)
        assertEquals(true, preferences.current[CORE_VOCABULARY_SEEDED])
    }

    @Test
    fun `lost boards raise an alert that only an acknowledgement clears`() = runTest(dispatcher) {
        preferences.updateData { it.toMutablePreferences().apply { set(CORE_VOCABULARY_SEEDED, true) } }
        val viewModel = startViewModel(FakeBoardDao())

        // On peut parler tout de suite : le vocabulaire de base est remis.
        assertEquals(HOME_BOARD_ID, viewModel.state().board?.id)
        assertTrue(viewModel.state().hasLostBoards)

        viewModel.onAcknowledgeLostBoards()

        assertFalse(viewModel.state().hasLostBoards)
    }

    @Test
    fun `a home board emptied while in use comes back with an alert`() = runTest(dispatcher) {
        val dao = FakeBoardDao()
        val viewModel = startViewModel(dao)
        viewModel.tap(checkNotNull(viewModel.state().board).grid.flatten().filterNotNull().first { it.linkedBoardId == null }.label)

        // Base vidée en cours d'usage.
        dao.cells.value = emptyList()
        dao.boards.value = emptyList()

        assertEquals(HOME_BOARD_ID, viewModel.state().board?.id)
        assertTrue(viewModel.state().hasLostBoards)
        assertEquals(2, dao.insertCount)
    }

    @Test
    fun `the home board is reopened only once while the reopening is pending`() = runTest(dispatcher) {
        val dao = FakeBoardDao()
        startViewModel(dao)
        dao.insertGate = CompletableDeferred()

        dao.boards.value = emptyList()
        // D'autres émissions de l'accueil absent pendant que la réinsertion attend.
        dao.cells.value = emptyList()
        dao.cells.value = listOf(CellEntity(id = 50, boardId = 99, row = 0, column = 0, label = "x", linkedBoardId = null, pictogramId = null, category = null))
        checkNotNull(dao.insertGate).complete(Unit)

        assertEquals(2, dao.insertCount)
    }

    @Test
    fun `a first launch raises no lost boards alert`() = runTest(dispatcher) {
        val viewModel = startViewModel(FakeBoardDao())

        assertEquals(HOME_BOARD_ID, viewModel.state().board?.id)
        assertFalse(viewModel.state().hasLostBoards)
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
        val viewModel = BoardViewModel(BoardRepository(FakeBoardDao(boards, cells), preferences, context), speaker, volume, SavedStateHandle())
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
        // Touchés vite, les deux mots doivent s'entendre : aucun ne coupe le précédent.
        assertEquals(listOf(false, false), speaker.interruptions)
        assertEquals(listOf("veux", "je"), viewModel.messageTexts())
        // Chaque mot garde le picto de sa case ; « veux » est en texte seul.
        assertEquals(listOf(emptyList(), listOf(10L)), viewModel.state().message.map { it.pictogramIds })
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

        assertEquals(listOf("je", "rouge"), viewModel.messageTexts())
    }

    @Test
    fun `the whole sentence is spoken in order`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        viewModel.tap("veux")
        viewModel.tap("je")
        speaker.spoken.clear()
        speaker.interruptions.clear()

        viewModel.onSpeakMessage()

        assertEquals(listOf("veux je"), speaker.spoken)
        // La phrase remplace les mots encore en attente au lieu de passer après eux.
        assertEquals(listOf(true), speaker.interruptions)
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

        assertEquals(listOf("j'ai"), viewModel.messageTexts())
        // « j'ai » montre les deux pictos touchés, celui de « je » puis celui du verbe.
        assertEquals(listOf(listOf(10L, 80L)), viewModel.state().message.map { it.pictogramIds })
        viewModel.onSpeakMessage()
        assertEquals(listOf("j'ai"), speaker.spoken)
        viewModel.onDeleteLastWord()
        assertEquals(listOf("je"), viewModel.messageTexts())
    }

    @Test
    fun `delete removes only the last word even when it is repeated`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        viewModel.tap("je")
        viewModel.tap("veux")
        viewModel.tap("je")

        viewModel.onDeleteLastWord()

        assertEquals(listOf("je", "veux"), viewModel.messageTexts())
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
        // Les mots touchés en rafale qui attendent encore leur tour se taisent avec le message.
        assertEquals(1, speaker.stopCount)
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

    /** Ce que le système garde quand il tue le processus : une copie, le premier ViewModel libéré. */
    private fun BoardViewModel.dieAndKeep(savedState: SavedStateHandle): SavedStateHandle {
        ViewModelStore().apply { put("board", this@dieAndKeep) }.clear()
        return SavedStateHandle(savedState.keys().associateWith { savedState.get<Any>(it) })
    }

    @Test
    fun `the sentence and the open board survive the process death`() = runTest(dispatcher) {
        val savedState = SavedStateHandle()
        val first = startViewModel(savedState = savedState)
        first.tap("je")
        first.tap("A")
        first.tap("B")
        first.tap("ai")

        val second = startViewModel(savedState = first.dieAndKeep(savedState), voice = FakeSpeaker())

        // Pile de trois grilles : la troisième revient, pas l'accueil ni la deuxième.
        assertEquals(3L, second.state().board?.id)
        assertTrue(second.state().board?.canGoBack == true)
        // « j'ai » se refait : la catégorie du verbe est revenue avec le mot.
        assertEquals(listOf("j'ai"), second.messageTexts())
        assertEquals(listOf(listOf(10L, 80L)), second.state().message.map { it.pictogramIds })
    }

    @Test
    fun `a saved stack that does not start at home is dropped for home`() = runTest(dispatcher) {
        val savedState = SavedStateHandle(mapOf(NAVIGATION_KEY to longArrayOf(2, 3)))

        assertEquals(HOME_BOARD_ID, startViewModel(savedState = savedState).state().board?.id)
        // L'état sauvé suit : la pile rejetée ne revient pas à la mort suivante.
        assertEquals(listOf(HOME_BOARD_ID), savedState.get<LongArray>(NAVIGATION_KEY)?.toList())
    }

    @Test
    fun `a saved word whose category no longer exists comes back without color`() {
        val word = SavedWord("x", "GONE", 7).toSentenceWord()

        assertNull(word.category)
        assertEquals("x" to 7L, word.label to word.pictogramId)
    }

    @Test
    fun `a board gone from the saved stack is left, the sentence stays`() = runTest(dispatcher) {
        val savedState = SavedStateHandle()
        val first = startViewModel(savedState = savedState)
        first.tap("veux")
        val kept = first.dieAndKeep(savedState).apply { set(NAVIGATION_KEY, longArrayOf(HOME_BOARD_ID, 2, 99)) }

        val second = startViewModel(savedState = kept, voice = FakeSpeaker())

        assertEquals(2L, second.state().board?.id)
        assertEquals(listOf("veux"), second.messageTexts())
    }

    @Test
    fun `going back, deleting a word and clearing all reach the saved state`() = runTest(dispatcher) {
        val savedState = SavedStateHandle()
        val viewModel = startViewModel(savedState = savedState)
        viewModel.tap("je")
        viewModel.tap("veux")
        viewModel.tap("A")

        viewModel.onBack()
        viewModel.onDeleteLastWord()

        assertEquals(listOf(HOME_BOARD_ID), savedState.get<LongArray>(NAVIGATION_KEY)?.toList())
        assertEquals(listOf("je"), savedState.get<ArrayList<SavedWord>>(MESSAGE_KEY)?.map { it.label })

        viewModel.onClearMessage()

        assertEquals(emptyList<SavedWord>(), savedState.get<ArrayList<SavedWord>>(MESSAGE_KEY))
    }

    @Test
    fun `the saved stack stays saved while home is being prepared`() = runTest(dispatcher) {
        // Le processus peut mourir à nouveau pendant l'attente : la pile sauvée doit y être encore.
        val savedState = SavedStateHandle(mapOf(NAVIGATION_KEY to longArrayOf(HOME_BOARD_ID, 2)))
        val dao = FakeBoardDao().apply { insertGate = CompletableDeferred() }

        startViewModel(dao, savedState)

        assertEquals(listOf(HOME_BOARD_ID, 2L), savedState.get<LongArray>(NAVIGATION_KEY)?.toList())
        checkNotNull(dao.insertGate).complete(Unit)
    }

    @Test
    fun `a muted volume shows, and goes away once raised`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        volume.deviceIsMuted = true

        // Lu au moment de parler : « Ne pas déranger » ne se signale pas toujours.
        viewModel.tap("je")
        assertTrue(viewModel.state().isMuted)

        viewModel.onMutedAlertClick()
        assertFalse(viewModel.state().isMuted)
        assertFalse(viewModel.state().isVolumeLockedDialogOpen)
    }

    @Test
    fun `speaking the sentence and coming back to the app read the volume again`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        viewModel.onSpeakMessage()
        // Phrase vide : rien n'est dit, rien n'est relu.
        assertEquals(0, volume.refreshCount)
        viewModel.tap("je")
        assertEquals(1, volume.refreshCount)

        viewModel.onSpeakMessage()
        assertEquals(2, volume.refreshCount)
        viewModel.recoverSpeech()
        assertEquals(3, volume.refreshCount)
    }

    @Test
    fun `a volume that cannot be raised opens the dialog, which the sound coming back closes for good`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        volume.deviceIsMuted = true
        volume.canRaise = false
        volume.refresh()

        viewModel.onMutedAlertClick()
        assertTrue(viewModel.state().isVolumeLockedDialogOpen)

        // L'adulte coupe « Ne pas déranger » sans fermer la modale : elle part, et ne revient pas
        // quand le son est recoupé plus tard.
        volume.deviceIsMuted = false
        volume.refresh()
        assertFalse(viewModel.state().isVolumeLockedDialogOpen)
        volume.deviceIsMuted = true
        volume.refresh()
        assertTrue(viewModel.state().isMuted)
        assertFalse(viewModel.state().isVolumeLockedDialogOpen)
    }

    @Test
    fun `closing the volume dialog keeps the alert`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        volume.deviceIsMuted = true
        volume.canRaise = false
        viewModel.onMutedAlertClick()
        assertTrue(viewModel.state().isVolumeLockedDialogOpen)

        viewModel.onVolumeLockedDialogClose()

        assertFalse(viewModel.state().isVolumeLockedDialogOpen)
        assertTrue(viewModel.state().isMuted)
    }

    @Test
    fun `the volume watch is released with the view model`() = runTest(dispatcher) {
        val viewModel = startViewModel()

        ViewModelStore().apply { put("board", viewModel) }.clear()

        assertTrue(volume.isReleased)
    }
}
