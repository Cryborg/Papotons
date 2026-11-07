package com.wobblebot.papotons.ui.board

import com.wobblebot.papotons.data.backup.BackupContent
import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.wobblebot.papotons.data.FakePreferences
import com.wobblebot.papotons.data.board.BoardEntity
import com.wobblebot.papotons.data.board.BoardRepository
import com.wobblebot.papotons.data.board.CellEntity
import com.wobblebot.papotons.data.board.FakeBoardDao
import com.wobblebot.papotons.data.pictogram.PictogramResult
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.CellContent
import com.wobblebot.papotons.domain.WordCategory
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.domain.HOME_BOARD_ID
import com.wobblebot.papotons.domain.Picture
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import android.net.Uri
import org.junit.Test
import java.io.File
import java.io.IOException

/** Nom persisté du marqueur : le changer ferait passer toute installation existante pour neuve. */
private val CORE_VOCABULARY_SEEDED = booleanPreferencesKey("core_vocabulary_seeded")

@OptIn(ExperimentalCoroutinesApi::class)
class BoardViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val speaker = FakeSpeaker()
    private val volume = FakeMediaVolume()
    private val arasaac = FakeArasaacClient()
    private val pictogramStore = FakePictogramStore()
    private val photos = FakePhotoStore()
    private var nowMs = 0L
    private var filesDirectory = File("build/tmp/no-files")
    private val preferences = FakePreferences()
    private val context = mockk<Context> {
        every { getString(any()) } answers { "label${firstArg<Int>()}" }
        // Aucune copie de base corrompue, sauf dans un test qui en pose.
        every { filesDir } answers { filesDirectory }
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
        val viewModel = BoardViewModel(BoardRepository(dao, preferences, context).also { it.fileDispatcher = dispatcher }, voice, volume, arasaac, pictogramStore, photos, savedState).also { it.elapsedRealtimeMs = { nowMs } }
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
        val viewModel = BoardViewModel(BoardRepository(FakeBoardDao(boards, cells), preferences, context), speaker, volume, arasaac, pictogramStore, photos, SavedStateHandle()).also { it.elapsedRealtimeMs = { nowMs } }
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
        assertEquals(listOf(emptyList(), listOf(10L)), viewModel.state().message.map { token -> token.pictures.map { (it as Picture.Pictogram).id } })
        assertTrue(viewModel.state().isBusy)
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
        assertEquals(listOf(listOf(10L, 80L)), viewModel.state().message.map { token -> token.pictures.map { (it as Picture.Pictogram).id } })
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
        assertFalse(viewModel.state().isBusy)
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
        assertEquals(listOf(listOf(10L, 80L)), second.state().message.map { token -> token.pictures.map { (it as Picture.Pictogram).id } })
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
        assertEquals("x" to Picture.Pictogram(7), word.label to word.picture)
    }

    @Test
    fun `a saved photo comes back, and wins over a pictogram`() {
        val word = SavedWord("Mamie", null, 7, photoFile = "a1b2.jpg").toSentenceWord()

        assertEquals(Picture.Photo("a1b2.jpg"), word.picture)
        assertEquals(word, SavedWord.from(word).toSentenceWord())
    }

    @Test
    fun `a cell says its own text, and that text enters the sentence`() = runTest(dispatcher) {
        val dao = FakeBoardDao(
            listOf(BoardEntity(id = HOME_BOARD_ID, name = "home", rowCount = 1, columnCount = 1)),
            listOf(CellEntity(id = 1, boardId = HOME_BOARD_ID, row = 0, column = 0, label = "Mamie", linkedBoardId = null, pictogramId = 7, category = null, photoFile = "m.jpg", spokenText = "Mamie Jeanne")),
        )
        val viewModel = startViewModel(dao)

        viewModel.tap("Mamie")

        assertEquals(listOf("Mamie Jeanne"), speaker.spoken)
        assertEquals(listOf("Mamie Jeanne"), viewModel.messageTexts())
        assertEquals(listOf(listOf(Picture.Photo("m.jpg"))), viewModel.state().message.map { it.pictures })
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

    private fun BoardViewModel.cellNamed(label: String): Cell = checkNotNull(state().board).grid.flatten().filterNotNull().first { it.label == label }

    // Deux jeux réduits : « grandes cases » (accueil 13 et son dossier 14, à la taille de ses
    // dossiers) et « moyennes » (accueil 7 seul).
    private val sizedBoards = boards + listOf(
        BoardEntity(id = BoardSize.LARGE_CELLS.homeBoardId, name = "large", rowCount = 1, columnCount = 2),
        BoardEntity(id = 14, name = "large food", rowCount = 2, columnCount = 3),
        BoardEntity(id = BoardSize.MEDIUM.homeBoardId, name = "medium", rowCount = 1, columnCount = 1),
    )
    private val sizedCells = cells + listOf(
        CellEntity(id = 20, boardId = BoardSize.LARGE_CELLS.homeBoardId, row = 0, column = 0, label = "food", linkedBoardId = 14, pictogramId = null, category = null),
    )

    @Test
    fun `choosing a size saves it and opens its home, keeping the sentence`() = runTest(dispatcher) {
        val viewModel = startViewModel(FakeBoardDao(sizedBoards, sizedCells))
        viewModel.tap("je")
        assertEquals(BoardSize.STANDARD, viewModel.state().boardSize)

        viewModel.onBoardSizeChosen(BoardSize.LARGE_CELLS)

        assertEquals(BoardSize.LARGE_CELLS.homeBoardId, viewModel.state().board?.id)
        assertEquals(BoardSize.LARGE_CELLS, viewModel.state().boardSize)
        assertEquals(listOf("je"), viewModel.messageTexts())
        assertNull(viewModel.state().settingFailure)
        // Retenue : un nouveau lancement ouvre la même taille.
        assertEquals(BoardSize.LARGE_CELLS.homeBoardId, startViewModel(FakeBoardDao(sizedBoards, sizedCells), voice = FakeSpeaker()).state().board?.id)
    }

    @Test
    fun `a size that cannot be saved changes nothing and is reported`() = runTest(dispatcher) {
        val viewModel = startViewModel(FakeBoardDao(sizedBoards, sizedCells))
        preferences.writeFailure = IOException("disk full")

        viewModel.onBoardSizeChosen(BoardSize.LARGE_CELLS)

        assertEquals(SettingFailure.NotSaved, viewModel.state().settingFailure)
        assertEquals(HOME_BOARD_ID, viewModel.state().board?.id)
        assertEquals(BoardSize.STANDARD, viewModel.state().boardSize)

        // Le message ne reste pas : un nouvel essai réussi l'efface.
        preferences.writeFailure = null
        viewModel.onBoardSizeChosen(BoardSize.LARGE_CELLS)
        assertNull(viewModel.state().settingFailure)
        assertEquals(BoardSize.LARGE_CELLS.homeBoardId, viewModel.state().board?.id)
    }

    @Test
    fun `a size that cannot be checked is reported instead of crashing`() = runTest(dispatcher) {
        val dao = FakeBoardDao(sizedBoards, sizedCells)
        val viewModel = startViewModel(dao)
        dao.existsFailure = SQLiteException("disk I/O error")

        viewModel.onBoardSizeChosen(BoardSize.LARGE_CELLS)

        assertEquals(SettingFailure.NotSaved, viewModel.state().settingFailure)
        assertEquals(HOME_BOARD_ID, viewModel.state().board?.id)
    }

    /** Grilles restaurées d'avant les tailles : l'accueil des moyennes n'y est pas. */
    @Test
    fun `a size missing from the boards is reported and nothing is saved`() = runTest(dispatcher) {
        val viewModel = startViewModel(FakeBoardDao(boards, cells))

        viewModel.onBoardSizeChosen(BoardSize.MEDIUM)

        assertEquals(SettingFailure.Unavailable, viewModel.state().settingFailure)
        assertEquals(HOME_BOARD_ID, viewModel.state().board?.id)
        assertTrue(preferences.current.asMap().values.none { it == BoardSize.MEDIUM.homeBoardId })
    }

    /** Choisie pendant le premier lancement : la taille attend l'insertion du vocabulaire, qui contient son accueil. */
    @Test
    fun `a size chosen while the home is still opening waits for it`() = runTest(dispatcher) {
        val dao = FakeBoardDao().apply { insertGate = CompletableDeferred() }
        val viewModel = startViewModel(dao)

        viewModel.onBoardSizeChosen(BoardSize.LARGE_CELLS)
        checkNotNull(dao.insertGate).complete(Unit)

        assertNull(viewModel.state().settingFailure)
        assertEquals(BoardSize.LARGE_CELLS.homeBoardId, viewModel.state().board?.id)
    }

    /** Deux appuis rapides : le second gagne, même si le premier écrit plus lentement. */
    @Test
    fun `the last size chosen wins over a slower earlier choice`() = runTest(dispatcher) {
        val viewModel = startViewModel(FakeBoardDao(sizedBoards, sizedCells))
        val slowWrite = CompletableDeferred<Unit>()
        preferences.nextWriteGate = slowWrite

        viewModel.onBoardSizeChosen(BoardSize.LARGE_CELLS)
        viewModel.onBoardSizeChosen(BoardSize.MEDIUM)
        slowWrite.complete(Unit)

        assertEquals(BoardSize.MEDIUM.homeBoardId, viewModel.state().board?.id)
        assertEquals(BoardSize.MEDIUM.homeBoardId, startViewModel(FakeBoardDao(sizedBoards, sizedCells), voice = FakeSpeaker()).state().board?.id)
    }

    @Test
    fun `a board opened inside a size survives the process death`() = runTest(dispatcher) {
        startViewModel(FakeBoardDao(sizedBoards, sizedCells)).onBoardSizeChosen(BoardSize.LARGE_CELLS)
        val savedState = SavedStateHandle(mapOf(NAVIGATION_KEY to longArrayOf(BoardSize.LARGE_CELLS.homeBoardId, 14)))

        val restored = startViewModel(FakeBoardDao(sizedBoards, sizedCells), savedState = savedState, voice = FakeSpeaker())

        assertEquals(14L, restored.state().board?.id)
        assertEquals(BoardSize.LARGE_CELLS, restored.state().boardSize)
    }

    /** Dans un dossier du jeu (pile [13, 14]) : la taille vient de la racine, pas de la grille ouverte. */
    @Test
    fun `a folder created in a size takes the folder size of that size`() = runTest(dispatcher) {
        val dao = FakeBoardDao(sizedBoards, sizedCells)
        val viewModel = startViewModel(dao)
        viewModel.onBoardSizeChosen(BoardSize.LARGE_CELLS)
        viewModel.tap("food")
        assertEquals(14L, viewModel.state().board?.id)
        viewModel.onStartEditing()

        viewModel.onEmptySlotClick(1, 2)
        viewModel.onEditorSave(CellContent("Animaux", null, null, null, null), asFolder = true)

        val folder = dao.boards.value.single { it.name == "Animaux" }
        assertEquals(BoardSize.LARGE_CELLS.folderRowCount to BoardSize.LARGE_CELLS.folderColumnCount, folder.rowCount to folder.columnCount)
    }

    @Test
    fun `the settings close when the edit mode starts, and keep the app busy while open`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        assertFalse(viewModel.state().isBusy)

        viewModel.onOpenSettings()
        assertTrue(viewModel.state().isSettingsOpen)
        assertTrue(viewModel.state().isBusy)
        viewModel.onCloseSettings()
        assertFalse(viewModel.state().isBusy)

        viewModel.onOpenSettings()
        // Sinon la grille à modifier resterait cachée sous les Réglages.
        viewModel.onStartEditing()
        assertFalse(viewModel.state().isSettingsOpen)
        assertTrue(viewModel.state().isEditing)
    }

    @Test
    fun `the gear opens the settings at once only while editing`() = runTest(dispatcher) {
        val viewModel = startViewModel()

        assertFalse(viewModel.onSettingsRequested())
        assertFalse(viewModel.state().isSettingsOpen)

        viewModel.onStartEditing()
        assertTrue(viewModel.onSettingsRequested())
        assertTrue(viewModel.state().isSettingsOpen)
    }

    @Test
    fun `the settings never reopen after the process died`() = runTest(dispatcher) {
        val savedState = SavedStateHandle()
        val first = startViewModel(savedState = savedState)
        first.onOpenSettings()

        val second = startViewModel(savedState = first.dieAndKeep(savedState), voice = FakeSpeaker())

        assertFalse(second.state().isSettingsOpen)
        assertFalse(second.state().isBusy)
    }

    @Test
    fun `while editing a tap opens the editor, never speaks nor opens a folder`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        viewModel.onStartEditing()

        viewModel.tap("je")
        assertEquals(EditTarget.ExistingCell(HOME_BOARD_ID, viewModel.cellNamed("je")), viewModel.state().editor?.target)
        viewModel.onEditorClose()
        viewModel.tap("A")

        assertEquals(emptyList<String>(), speaker.spoken)
        assertEquals(emptyList<String>(), viewModel.messageTexts())
        assertEquals(HOME_BOARD_ID, viewModel.state().board?.id)
        assertEquals("A", (viewModel.state().editor?.target as? EditTarget.ExistingCell)?.cell?.label)
    }

    @Test
    fun `an empty slot opens a new cell editor only while editing`() = runTest(dispatcher) {
        val dao = FakeBoardDao(listOf(BoardEntity(id = HOME_BOARD_ID, name = "home", rowCount = 2, columnCount = 3)), emptyList())
        val viewModel = startViewModel(dao)

        viewModel.onEmptySlotClick(1, 2)
        assertNull(viewModel.state().editor)

        viewModel.onStartEditing()
        viewModel.onEmptySlotClick(1, 2)
        viewModel.onEditorSave(CellContent("eau", null, WordCategory.NOUN, null, null), asFolder = false)

        assertNull(viewModel.state().editor)
        val word = viewModel.state().board?.grid?.get(1)?.get(2)
        assertEquals("eau", word?.label)
        assertNull(word?.linkedBoardId)

        viewModel.onEmptySlotClick(0, 0)
        viewModel.onEditorSave(CellContent("Animaux", null, null, null, null), asFolder = true)
        val folder = checkNotNull(viewModel.state().board?.grid?.get(0)?.get(0))
        assertEquals("Animaux", dao.boards.value.single { it.id == folder.linkedBoardId }.name)
    }

    @Test
    fun `a double tap on save writes once`() = runTest(dispatcher) {
        val dao = FakeBoardDao(listOf(BoardEntity(id = HOME_BOARD_ID, name = "home", rowCount = 2, columnCount = 3)), emptyList())
        val viewModel = startViewModel(dao)
        viewModel.onStartEditing()
        viewModel.onEmptySlotClick(0, 0)
        dao.insertGate = CompletableDeferred()

        viewModel.onEditorSave(CellContent("eau", null, null, null, null), asFolder = false)
        viewModel.onEditorSave(CellContent("eau", null, null, null, null), asFolder = false)
        // Fermer pendant l'écriture ne la perd pas de vue.
        viewModel.onEditorClose()
        assertTrue(viewModel.state().editor?.isWriting == true)
        checkNotNull(dao.insertGate).complete(Unit)

        assertEquals(1, dao.cells.value.size)
        assertNull(viewModel.state().editor)
    }

    @Test
    fun `editing an existing cell changes it in place, deleting a folder removes its board`() = runTest(dispatcher) {
        val dao = FakeBoardDao(boards, cells)
        val viewModel = startViewModel(dao)
        viewModel.onStartEditing()
        viewModel.tap("veux")
        // Une case existante garde son type, quoi que dise asFolder.
        viewModel.onEditorSave(CellContent("voudrais", null, WordCategory.VERB, null, null), asFolder = true)
        assertEquals("voudrais", viewModel.state().board?.grid?.get(0)?.get(1)?.label)
        assertNull(viewModel.state().board?.grid?.get(0)?.get(1)?.linkedBoardId)

        viewModel.tap("A")
        viewModel.onEditorOpenFolder()
        viewModel.tap("B")
        viewModel.onEditorDelete()

        // B (grille 3) part ; A (grille 2, ouverte et porteuse de la case) reste.
        assertFalse(dao.boards.value.any { it.id == 3L })
        assertEquals(2L, viewModel.state().board?.id)
    }

    @Test
    fun `a cell deleted meanwhile makes the save fail visibly`() = runTest(dispatcher) {
        val dao = FakeBoardDao(boards, cells)
        val viewModel = startViewModel(dao)
        viewModel.onStartEditing()
        viewModel.tap("veux")
        dao.cells.value = dao.cells.value.filterNot { it.label == "veux" }

        viewModel.onEditorSave(CellContent("voudrais", null, null, null, null), asFolder = false)

        assertEquals(EditFailure.SlotTakenOrCellGone, viewModel.state().editor?.failure)
    }

    @Test
    fun `a write that fails keeps the editor open with the failure`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        viewModel.onStartEditing()
        // (1, 1) est déjà pris (« lost ») : un « + » affiché avant que la grille ne soit relue.
        viewModel.onEmptySlotClick(1, 1)
        viewModel.onEditorSave(CellContent("y", null, null, null, null), asFolder = false)

        assertEquals(EditFailure.SlotTakenOrCellGone, viewModel.state().editor?.failure)
        assertEquals("lost", viewModel.state().board?.grid?.get(1)?.get(1)?.label)
        assertFalse(viewModel.state().editor?.isWriting == true)
    }

    @Test
    fun `a write finishing after the editor was left touches no other editor`() = runTest(dispatcher) {
        val dao = FakeBoardDao(listOf(BoardEntity(id = HOME_BOARD_ID, name = "home", rowCount = 2, columnCount = 3)), emptyList())
        val viewModel = startViewModel(dao)
        viewModel.onStartEditing()
        dao.insertGate = CompletableDeferred()
        viewModel.onEmptySlotClick(0, 0)
        viewModel.onEditorSave(CellContent("lent", null, null, null, null), asFolder = false)

        viewModel.onStopEditing()
        viewModel.onStartEditing()
        viewModel.onEmptySlotClick(1, 1)
        checkNotNull(dao.insertGate).complete(Unit)

        assertEquals(EditTarget.NewCell(HOME_BOARD_ID, 1, 1), viewModel.state().editor?.target)
    }

    @Test
    fun `stopping the edit closes everything, and starting it silences the voice`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        viewModel.onStartEditing()
        assertEquals(1, speaker.stopCount)
        assertTrue(viewModel.state().isBusy)
        viewModel.tap("je")

        viewModel.onStopEditing()

        assertFalse(viewModel.state().isEditing)
        assertNull(viewModel.state().editor)
    }

    @Test
    fun `neither the edit mode nor the editor come back after the process death`() = runTest(dispatcher) {
        val savedState = SavedStateHandle()
        val first = startViewModel(savedState = savedState)
        first.onStartEditing()
        first.tap("je")

        val second = startViewModel(savedState = first.dieAndKeep(savedState), voice = FakeSpeaker())

        assertFalse(second.state().isEditing)
        assertNull(second.state().editor)
    }

    @Test
    fun `a folder opened from the editor stays in edit mode`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        viewModel.onStartEditing()
        viewModel.tap("A")

        viewModel.onEditorOpenFolder()

        assertEquals(2L, viewModel.state().board?.id)
        assertTrue(viewModel.state().isEditing)
        assertNull(viewModel.state().editor)
    }

    @Test
    fun `a storage failure keeps the editor open with its own message`() = runTest(dispatcher) {
        filesDirectory = kotlin.io.path.createTempDirectory("papotons").toFile()
        File(filesDirectory, "corrupted-databases/000001-x").mkdirs()
        File(filesDirectory, "corrupted-databases.edited/occupied").mkdirs()
        val dao = FakeBoardDao(listOf(BoardEntity(id = HOME_BOARD_ID, name = "home", rowCount = 2, columnCount = 3)), emptyList())
        val viewModel = startViewModel(dao)
        viewModel.onStartEditing()
        viewModel.onEmptySlotClick(0, 0)

        viewModel.onEditorSave(CellContent("eau", null, null, null, null), asFolder = false)

        assertEquals(EditFailure.Storage, viewModel.state().editor?.failure)
        assertFalse(viewModel.state().editor?.isWriting == true)
        assertEquals(emptyList<CellEntity>(), dao.cells.value)
        filesDirectory.deleteRecursively()
    }

    @Test
    fun `a pictogram picked in the search is kept on the device only when the cell is saved`() = runTest(dispatcher) {
        val dao = FakeBoardDao(listOf(BoardEntity(id = HOME_BOARD_ID, name = "home", rowCount = 2, columnCount = 3)), emptyList())
        val viewModel = startViewModel(dao)
        viewModel.onStartEditing()
        viewModel.onEmptySlotClick(0, 0)

        viewModel.onSearchOpen("pomme")
        // Ouvrir ne fait que préremplir : rien ne part avant « Chercher ».
        assertEquals(emptyList<String>(), arasaac.searches)
        assertEquals("pomme", viewModel.state().pictogramSearch?.query)
        viewModel.onSearch("pomme")
        assertEquals(listOf("pomme"), arasaac.searches)
        assertEquals(listOf(10L, 20L), viewModel.state().pictogramSearch?.results?.map { it.id })
        viewModel.onSearchPick(20)

        assertNull(viewModel.state().pictogramSearch)
        assertEquals(20L, viewModel.state().editor?.chosenPictogram?.id)
        assertTrue(pictogramStore.stored.isEmpty())

        viewModel.onEditorSave(CellContent("poire", 20, null, null, null), asFolder = false)

        assertEquals(setOf(20L), pictogramStore.stored.keys)
        assertEquals(20L, dao.cells.value.single().pictogramId)
    }

    @Test
    fun `a pictogram that cannot be kept stops the save before the database`() = runTest(dispatcher) {
        val dao = FakeBoardDao(listOf(BoardEntity(id = HOME_BOARD_ID, name = "home", rowCount = 2, columnCount = 3)), emptyList())
        val viewModel = startViewModel(dao)
        viewModel.onStartEditing()
        viewModel.onEmptySlotClick(0, 0)
        viewModel.onSearchOpen("pomme")
        viewModel.onSearch("pomme")
        viewModel.onSearchPick(10)
        pictogramStore.canStore = false

        viewModel.onEditorSave(CellContent("pomme", 10, null, null, null), asFolder = false)

        assertEquals(EditFailure.Storage, viewModel.state().editor?.failure)
        assertEquals(emptyList<CellEntity>(), dao.cells.value)
        assertEquals(10L, viewModel.state().editor?.chosenPictogram?.id)

        // L'adulte réessaie une fois la place libérée : le choix n'a pas été perdu.
        pictogramStore.canStore = true
        viewModel.onEditorSave(CellContent("pomme", 10, null, null, null), asFolder = false)
        assertEquals(setOf(10L), pictogramStore.stored.keys)
        assertEquals(10L, dao.cells.value.single().pictogramId)
    }

    @Test
    fun `an offline search says so and can be retried`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        viewModel.onStartEditing()
        viewModel.tap("je")
        arasaac.isOffline = true

        viewModel.onSearchOpen("je")
        viewModel.onSearch("je")
        assertEquals(SearchStatus.Failed, viewModel.state().pictogramSearch?.status)

        arasaac.isOffline = false
        viewModel.onSearch("je")
        assertEquals(SearchStatus.Done, viewModel.state().pictogramSearch?.status)
    }

    @Test
    fun `an empty keyword is not sent and leaving the edit closes the search`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        viewModel.onStartEditing()
        viewModel.tap("je")

        viewModel.onSearchOpen(" ")
        viewModel.onSearch(" ")
        assertEquals(emptyList<String>(), arasaac.searches)
        assertEquals(SearchStatus.Idle, viewModel.state().pictogramSearch?.status)

        viewModel.onStopEditing()
        assertNull(viewModel.state().pictogramSearch)
    }

    private fun TestScope.editingEmptyHome(): Pair<BoardViewModel, FakeBoardDao> {
        val dao = FakeBoardDao(listOf(BoardEntity(id = HOME_BOARD_ID, name = "home", rowCount = 2, columnCount = 3)), emptyList())
        val viewModel = startViewModel(dao)
        viewModel.onStartEditing()
        viewModel.onEmptySlotClick(0, 0)
        return viewModel to dao
    }

    @Test
    fun `a picked pictogram given up in the editor is never kept`() = runTest(dispatcher) {
        val (viewModel, dao) = editingEmptyHome()
        viewModel.onSearchOpen("pomme")
        viewModel.onSearch("pomme")
        viewModel.onSearchPick(20)

        // L'adulte retire le picto et enregistre sans : rien n'est gardé.
        viewModel.onEditorSave(CellContent("poire", null, null, null, null), asFolder = false)
        assertTrue(pictogramStore.stored.isEmpty())
        assertNull(dao.cells.value.single().pictogramId)

        // Un choix fait dans un éditeur fermé ne suit pas dans le suivant.
        viewModel.onEmptySlotClick(0, 1)
        viewModel.onSearchOpen("pomme")
        viewModel.onSearch("pomme")
        viewModel.onSearchPick(10)
        viewModel.onEditorClose()
        viewModel.onEmptySlotClick(0, 2)
        viewModel.onEditorSave(CellContent("autre", 10, null, null, null), asFolder = false)
        assertTrue(pictogramStore.stored.isEmpty())
    }

    @Test
    fun `a thumbnail that fails stays grey and cannot be picked`() = runTest(dispatcher) {
        val (viewModel, _) = editingEmptyHome()
        arasaac.failingDownloads += 10L

        viewModel.onSearchOpen("pomme")
        viewModel.onSearch("pomme")

        val results = checkNotNull(viewModel.state().pictogramSearch).results
        assertEquals(SearchStatus.Done, viewModel.state().pictogramSearch?.status)
        assertNull(results.single { it.id == 10L }.image)
        assertNotNull(results.single { it.id == 20L }.image)
        viewModel.onSearchPick(10)
        assertNull(viewModel.state().editor?.chosenPictogram)
        assertNotNull(viewModel.state().pictogramSearch)
    }

    @Test
    fun `a slow search replaced by a newer one never shows up`() = runTest(dispatcher) {
        val (viewModel, _) = editingEmptyHome()
        arasaac.resultsFor = { keyword -> if (keyword == "lent") listOf(PictogramResult(1, "lent")) else listOf(PictogramResult(2, "vite")) }
        arasaac.searchGates["lent"] = CompletableDeferred()

        viewModel.onSearchOpen("lent")
        viewModel.onSearch("lent")
        assertEquals(SearchStatus.Loading, viewModel.state().pictogramSearch?.status)
        viewModel.onSearch("vite")
        checkNotNull(arasaac.searchGates["lent"]).complete(Unit)

        assertEquals(listOf(2L), viewModel.state().pictogramSearch?.results?.map { it.id })
    }

    @Test
    fun `a search closed while loading leaves nothing behind`() = runTest(dispatcher) {
        val (viewModel, _) = editingEmptyHome()
        arasaac.searchGates["pomme"] = CompletableDeferred()
        viewModel.onSearchOpen("pomme")
        viewModel.onSearch("pomme")

        viewModel.onSearchClose()
        checkNotNull(arasaac.searchGates["pomme"]).complete(Unit)

        assertNull(viewModel.state().pictogramSearch)
        // Annulée pour de bon (la connexion se ferme) : aucune vignette n'est partie après la fermeture.
        assertEquals(listOf("pomme"), arasaac.cancelledSearches)
        assertEquals(emptyList<Long>(), arasaac.downloads)
    }

    @Test
    fun `a replaced search failing late never hides the newer results`() = runTest(dispatcher) {
        val (viewModel, _) = editingEmptyHome()
        arasaac.resultsFor = { listOf(PictogramResult(2, "chien")) }
        arasaac.searchGates["chat"] = CompletableDeferred()
        arasaac.failingSearches += "chat"

        viewModel.onSearchOpen("chat")
        viewModel.onSearch("chat")
        viewModel.onSearch("chien")
        checkNotNull(arasaac.searchGates["chat"]).complete(Unit)

        assertEquals(SearchStatus.Done, viewModel.state().pictogramSearch?.status)
        assertEquals(listOf(2L), viewModel.state().pictogramSearch?.results?.map { it.id })
    }

    @Test
    fun `removing a picked pictogram forgets it for good`() = runTest(dispatcher) {
        val (viewModel, _) = editingEmptyHome()
        viewModel.onSearchOpen("pomme")
        viewModel.onSearch("pomme")
        viewModel.onSearchPick(20)

        viewModel.onChosenPictogramRemoved()

        assertNull(viewModel.state().editor?.chosenPictogram)
        viewModel.onEditorSave(CellContent("poire", 20, null, null, null), asFolder = false)
        assertTrue(pictogramStore.stored.isEmpty())
    }

    @Test
    fun `removing the picked pictogram while saving neither blocks the editor nor changes the save`() = runTest(dispatcher) {
        val (viewModel, dao) = editingEmptyHome()
        viewModel.onSearchOpen("pomme")
        viewModel.onSearch("pomme")
        viewModel.onSearchPick(20)
        pictogramStore.storeGate = CompletableDeferred()

        viewModel.onEditorSave(CellContent("poire", 20, null, null, null), asFolder = false)
        viewModel.onChosenPictogramRemoved()
        checkNotNull(pictogramStore.storeGate).complete(Unit)

        assertNull(viewModel.state().editor)
        assertEquals(20L, dao.cells.value.single().pictogramId)
    }

    @Test
    fun `a too vague word asks for a more precise one`() = runTest(dispatcher) {
        val (viewModel, _) = editingEmptyHome()
        arasaac.tooVagueSearches += "le"

        viewModel.onSearchOpen("le")
        viewModel.onSearch("le")

        assertEquals(SearchStatus.TooManyResults, viewModel.state().pictogramSearch?.status)
    }

    private val gallery: Uri = mockk(relaxed = true)

    @Test
    fun `a chosen photo is committed with the cell and replaces nothing else`() = runTest(dispatcher) {
        val (viewModel, dao) = editingEmptyHome()

        viewModel.onGalleryRequested()
        assertTrue(viewModel.isAwaitingPhoto)
        viewModel.onPhotoResult(gallery)
        assertFalse(viewModel.isAwaitingPhoto)
        assertEquals("photo1.jpg", viewModel.state().editor?.chosenPhoto)

        viewModel.onEditorSave(CellContent("Mamie", null, null, "photo1.jpg", "Mamie Jeanne"), asFolder = false)

        assertEquals(setOf("photo1.jpg"), photos.committed)
        assertEquals(emptyList<String>(), photos.deleted)
        val cell = dao.cells.value.single()
        assertEquals("photo1.jpg" to "Mamie Jeanne", cell.photoFile to cell.spokenText)
    }

    @Test
    fun `a photo given up is deleted, whatever the way out`() = runTest(dispatcher) {
        val (viewModel, _) = editingEmptyHome()
        viewModel.onGalleryRequested()
        viewModel.onPhotoResult(gallery)

        // Un autre choix remplace la première.
        viewModel.onGalleryRequested()
        viewModel.onPhotoResult(gallery)
        assertEquals(listOf("photo1.jpg"), photos.deleted)

        viewModel.onEditorClose()
        assertEquals(listOf("photo1.jpg", "photo2.jpg"), photos.deleted)
        assertTrue(photos.committed.isEmpty())
    }

    @Test
    fun `a save failing after the photo is committed reuses it on retry`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        viewModel.onStartEditing()
        // (1, 1) est pris : l'écriture en base échoue après la validation de la photo.
        viewModel.onEmptySlotClick(1, 1)
        viewModel.onGalleryRequested()
        viewModel.onPhotoResult(gallery)

        viewModel.onEditorSave(CellContent("x", null, null, "photo1.jpg", null), asFolder = false)
        assertEquals(EditFailure.SlotTakenOrCellGone, viewModel.state().editor?.failure)
        viewModel.onEditorSave(CellContent("x", null, null, "photo1.jpg", null), asFolder = false)

        assertEquals(setOf("photo1.jpg"), photos.committed)
        viewModel.onEditorClose()
        // Écrite dans aucune case : effacée.
        assertEquals(listOf("photo1.jpg"), photos.deleted)
    }

    @Test
    fun `replacing or removing the photo of a cell deletes the old one once saved`() = runTest(dispatcher) {
        val dao = FakeBoardDao(
            listOf(BoardEntity(id = HOME_BOARD_ID, name = "home", rowCount = 1, columnCount = 2)),
            listOf(
                CellEntity(id = 1, boardId = HOME_BOARD_ID, row = 0, column = 0, label = "Mamie", linkedBoardId = null, pictogramId = null, category = null, photoFile = "old.jpg", spokenText = null),
                CellEntity(id = 2, boardId = HOME_BOARD_ID, row = 0, column = 1, label = "Papi", linkedBoardId = null, pictogramId = null, category = null, photoFile = "papi.jpg", spokenText = null),
            ),
        )
        val viewModel = startViewModel(dao)
        viewModel.onStartEditing()
        viewModel.tap("Mamie")
        viewModel.onEditorSave(CellContent("Mamie", 7, null, null, null), asFolder = false)
        assertEquals(listOf("old.jpg"), photos.deleted)

        viewModel.tap("Papi")
        viewModel.onEditorDelete()
        assertEquals(listOf("old.jpg", "papi.jpg"), photos.deleted)
    }

    @Test
    fun `a photo coming back too late ends the edit`() = runTest(dispatcher) {
        val (viewModel, _) = editingEmptyHome()
        viewModel.onCameraRequested()

        nowMs += PHOTO_REQUEST_TIMEOUT_MS + 1
        viewModel.onCameraResult(isSaved = true)

        assertFalse(viewModel.state().isEditing)
        assertFalse(viewModel.isAwaitingPhoto)
        assertEquals(0, photos.prepareCount)
        assertEquals(1, photos.cameraCaptureDiscarded)
    }

    @Test
    fun `an import finishing after the editor closed is thrown away`() = runTest(dispatcher) {
        val (viewModel, dao) = editingEmptyHome()
        photos.prepareGate = CompletableDeferred()
        viewModel.onGalleryRequested()
        viewModel.onPhotoResult(gallery)
        assertTrue(viewModel.state().editor?.isImportingPhoto == true)
        // « Enregistrer » attend la fin de l'import.
        viewModel.onEditorSave(CellContent("x", null, null, null, null), asFolder = false)
        assertFalse(viewModel.state().editor?.isWriting == true)

        assertTrue(dao.cells.value.isEmpty())

        // Fermé puis rouvert sur une autre case : l'import en retard ne doit pas y atterrir.
        viewModel.onEditorClose()
        viewModel.onEmptySlotClick(0, 1)
        checkNotNull(photos.prepareGate).complete(Unit)

        assertEquals(listOf("photo1.jpg"), photos.deleted)
        assertNull(viewModel.state().editor?.chosenPhoto)
    }

    @Test
    fun `an unusable image says so`() = runTest(dispatcher) {
        val (viewModel, _) = editingEmptyHome()
        photos.canPrepare = false

        viewModel.onGalleryRequested()
        viewModel.onPhotoResult(gallery)

        assertEquals(PhotoFailure.UnusableImage, viewModel.state().editor?.photoFailure)
        assertNull(viewModel.state().editor?.chosenPhoto)
    }

    private fun TestScope.editingCellWithPhoto(): Pair<BoardViewModel, FakeBoardDao> {
        val dao = FakeBoardDao(
            listOf(BoardEntity(id = HOME_BOARD_ID, name = "home", rowCount = 1, columnCount = 2)),
            listOf(CellEntity(id = 1, boardId = HOME_BOARD_ID, row = 0, column = 0, label = "Mamie", linkedBoardId = null, pictogramId = null, category = null, photoFile = "old.jpg", spokenText = null)),
        )
        val viewModel = startViewModel(dao)
        viewModel.onStartEditing()
        viewModel.tap("Mamie")
        return viewModel to dao
    }

    @Test
    fun `a cell keeping its photo never loses it, saved or not`() = runTest(dispatcher) {
        val (viewModel, dao) = editingCellWithPhoto()

        viewModel.onEditorSave(CellContent("Mamou", null, null, "old.jpg", "Mamie Jeanne"), asFolder = false)

        assertTrue(photos.deleted.isEmpty())
        assertEquals("old.jpg", dao.cells.value.single().photoFile)

        // Une écriture qui échoue n'efface pas non plus l'ancienne photo.
        viewModel.tap("Mamou")
        dao.cells.value = emptyList()
        viewModel.onEditorSave(CellContent("Mamie", null, null, null, null), asFolder = false)
        assertEquals(EditFailure.SlotTakenOrCellGone, viewModel.state().editor?.failure)
        assertTrue(photos.deleted.isEmpty())
    }

    @Test
    fun `leaving the edit while a photo is being saved never deletes it`() = runTest(dispatcher) {
        val (viewModel, dao) = editingEmptyHome()
        viewModel.onGalleryRequested()
        viewModel.onPhotoResult(gallery)
        photos.commitGate = CompletableDeferred()
        viewModel.onEditorSave(CellContent("Mamie", null, null, "photo1.jpg", null), asFolder = false)

        viewModel.onStopEditing()
        checkNotNull(photos.commitGate).complete(Unit)

        assertEquals("photo1.jpg", dao.cells.value.single().photoFile)
        assertTrue(photos.deleted.isEmpty())
    }

    @Test
    fun `a photo that cannot be committed is not written and stays pending`() = runTest(dispatcher) {
        val (viewModel, dao) = editingEmptyHome()
        viewModel.onGalleryRequested()
        viewModel.onPhotoResult(gallery)
        photos.canCommit = false

        viewModel.onEditorSave(CellContent("Mamie", null, null, "photo1.jpg", null), asFolder = false)

        assertEquals(EditFailure.Storage, viewModel.state().editor?.failure)
        assertTrue(dao.cells.value.isEmpty())
        assertTrue(photos.deleted.isEmpty())
        assertEquals("photo1.jpg", viewModel.state().editor?.chosenPhoto)
    }

    @Test
    fun `a photo and a pictogram replace each other`() = runTest(dispatcher) {
        val (viewModel, _) = editingEmptyHome()
        viewModel.onSearchOpen("pomme")
        viewModel.onSearch("pomme")
        viewModel.onSearchPick(20)
        viewModel.onGalleryRequested()
        viewModel.onPhotoResult(gallery)
        assertNull(viewModel.state().editor?.chosenPictogram)
        assertEquals("photo1.jpg", viewModel.state().editor?.chosenPhoto)

        viewModel.onSearchOpen("pomme")
        viewModel.onSearch("pomme")
        viewModel.onSearchPick(10)
        assertNull(viewModel.state().editor?.chosenPhoto)
        assertEquals(listOf("photo1.jpg"), photos.deleted)

        viewModel.onEditorSave(CellContent("x", 10, null, null, null), asFolder = false)
        assertEquals(setOf(10L), pictogramStore.stored.keys)
    }

    @Test
    fun `a photo taken in time lands in the editor, a cancelled one changes nothing`() = runTest(dispatcher) {
        val (viewModel, _) = editingEmptyHome()

        viewModel.onCameraRequested()
        viewModel.onCameraResult(isSaved = false)
        assertFalse(viewModel.isAwaitingPhoto)
        assertEquals(0, photos.prepareCount)

        viewModel.onCameraRequested()
        nowMs += PHOTO_REQUEST_TIMEOUT_MS
        viewModel.onCameraResult(isSaved = true)
        assertTrue(viewModel.state().isEditing)
        assertEquals("photo1.jpg", viewModel.state().editor?.chosenPhoto)
        assertEquals(2, photos.cameraCaptureDiscarded)
    }

    @Test
    fun `a missing camera app says so and stops waiting`() = runTest(dispatcher) {
        val (viewModel, _) = editingEmptyHome()
        viewModel.onCameraRequested()

        viewModel.onPhotoRequestFailed()

        assertFalse(viewModel.isAwaitingPhoto)
        assertEquals(PhotoFailure.NoPhotoApp, viewModel.state().editor?.photoFailure)
    }

    @Test
    fun `stopping the edit or opening a folder deletes the photo not yet saved`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        viewModel.onStartEditing()
        viewModel.tap("A")
        viewModel.onGalleryRequested()
        viewModel.onPhotoResult(gallery)

        viewModel.onEditorOpenFolder()
        assertEquals(listOf("photo1.jpg"), photos.deleted)

        viewModel.tap("B")
        viewModel.onGalleryRequested()
        viewModel.onPhotoResult(gallery)
        viewModel.onStopEditing()
        assertEquals(listOf("photo1.jpg", "photo2.jpg"), photos.deleted)
    }

    @Test
    fun `removing the image during an import never leaves the editor stuck`() = runTest(dispatcher) {
        val (viewModel, dao) = editingEmptyHome()
        photos.prepareGate = CompletableDeferred()
        viewModel.onGalleryRequested()
        viewModel.onPhotoResult(gallery)

        viewModel.onChosenPictogramRemoved()
        checkNotNull(photos.prepareGate).complete(Unit)

        assertFalse(viewModel.state().editor?.isImportingPhoto == true)
        assertEquals(listOf("photo1.jpg"), photos.deleted)
        viewModel.onEditorSave(CellContent("eau", null, null, null, null), asFolder = false)
        assertEquals("eau", dao.cells.value.single().label)
    }

    @Test
    fun `a write failing after the editor closed deletes its photo`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        viewModel.onStartEditing()
        // (1, 1) est pris : l'écriture échoue après la validation.
        viewModel.onEmptySlotClick(1, 1)
        viewModel.onGalleryRequested()
        viewModel.onPhotoResult(gallery)
        photos.commitGate = CompletableDeferred()
        viewModel.onEditorSave(CellContent("x", null, null, "photo1.jpg", null), asFolder = false)

        viewModel.onStopEditing()
        checkNotNull(photos.commitGate).complete(Unit)

        assertEquals(listOf("photo1.jpg"), photos.deleted)
    }

    @Test
    fun `replacing a photo by another deletes the old one and commits the new one`() = runTest(dispatcher) {
        val (viewModel, dao) = editingCellWithPhoto()
        viewModel.onGalleryRequested()
        viewModel.onPhotoResult(gallery)

        viewModel.onEditorSave(CellContent("Mamie", null, null, "photo1.jpg", null), asFolder = false)

        assertEquals(setOf("photo1.jpg"), photos.committed)
        assertEquals(listOf("old.jpg"), photos.deleted)
        assertEquals("photo1.jpg", dao.cells.value.single().photoFile)
    }

    @Test
    fun `a write failing late never deletes the photo of an editor opened since`() = runTest(dispatcher) {
        val viewModel = startViewModel()
        viewModel.onStartEditing()
        // A : (1, 1) est pris, l'écriture échouera ; retenue pendant qu'un autre éditeur s'ouvre.
        viewModel.onEmptySlotClick(1, 1)
        viewModel.onGalleryRequested()
        viewModel.onPhotoResult(gallery)
        val gateOfA = CompletableDeferred<Unit>()
        photos.commitGate = gateOfA
        viewModel.onEditorSave(CellContent("x", null, null, "photo1.jpg", null), asFolder = false)
        viewModel.onStopEditing()
        viewModel.onStartEditing()
        // B : son propre choix.
        viewModel.tap("je")
        photos.commitGate = null
        viewModel.onGalleryRequested()
        viewModel.onPhotoResult(gallery)
        assertEquals("photo2.jpg", viewModel.state().editor?.chosenPhoto)

        gateOfA.complete(Unit)

        assertEquals(listOf("photo1.jpg"), photos.deleted)
        assertEquals("photo2.jpg", viewModel.state().editor?.chosenPhoto)
    }

    @Test
    fun `a photo given up earlier is refused, the current one is kept`() = runTest(dispatcher) {
        val (viewModel, dao) = editingEmptyHome()
        viewModel.onGalleryRequested()
        viewModel.onPhotoResult(gallery)
        viewModel.onGalleryRequested()
        viewModel.onPhotoResult(gallery)

        // Brouillon en retard : il vise encore la première photo, déjà effacée.
        viewModel.onEditorSave(CellContent("Mamie", null, null, "photo1.jpg", null), asFolder = false)

        assertEquals(EditFailure.Storage, viewModel.state().editor?.failure)
        assertTrue(dao.cells.value.none { it.label == "Mamie" })
        assertEquals(listOf("photo1.jpg"), photos.deleted)
        assertTrue(photos.committed.isEmpty())
        viewModel.onEditorSave(CellContent("Mamie", null, null, "photo2.jpg", null), asFolder = false)
        assertEquals("photo2.jpg", dao.cells.value.single { it.label == "Mamie" }.photoFile)
        assertEquals(listOf("photo1.jpg"), photos.deleted)
    }

    @Test
    fun `an editor opened on a cell not yet reread never brings back its old photo`() = runTest(dispatcher) {
        val (viewModel, dao) = editingCellWithPhoto()
        // La case a déjà reçu une nouvelle photo, que l'éditeur n'a pas encore vue.
        dao.cells.value = dao.cells.value.map { it.copy(photoFile = "new.jpg") }

        viewModel.onEditorSave(CellContent("Mamou", null, null, "old.jpg", null), asFolder = false)

        assertEquals(EditFailure.SlotTakenOrCellGone, viewModel.state().editor?.failure)
        assertEquals("Mamie" to "new.jpg", dao.cells.value.single().let { it.label to it.photoFile })
        assertTrue(photos.deleted.isEmpty())
    }

    @Test
    fun `opening the home board sweeps the images no cell points to`() = runTest(dispatcher) {
        filesDirectory = kotlin.io.path.createTempDirectory("papotons").toFile()
        val (orphan, ofTheFirstCell) = listOf("999.png", "10.png").map { name ->
            File(filesDirectory, "pictograms/$name").apply {
                parentFile?.mkdirs()
                writeText("image")
                setLastModified(System.currentTimeMillis() - 3_600_000L)
            }
        }

        // Le ménage passe sur le dispatcher de test, sans file d'attente : fini au retour.
        startViewModel()

        assertFalse(orphan.exists())
        assertTrue(ofTheFirstCell.exists())
        filesDirectory.deleteRecursively()
    }

    @Test
    fun `a restore brings the stack back home and closes the editor`() = runTest(dispatcher) {
        val repository = BoardRepository(FakeBoardDao(boards, cells), preferences, context).also { it.fileDispatcher = dispatcher }
        val viewModel = BoardViewModel(repository, speaker, volume, arasaac, pictogramStore, photos, SavedStateHandle()).also { it.elapsedRealtimeMs = { nowMs } }
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.tap("A")
        viewModel.tap("B")
        viewModel.onStartEditing()
        viewModel.tap("rouge")
        assertNotNull(viewModel.state().editor)

        repository.replaceAll(BackupContent(listOf(boards[0].copy(name = "restored")), listOf(cells[0])))

        assertEquals("restored", viewModel.state().board?.name)
        assertFalse(viewModel.state().board?.canGoBack == true)
        assertNull(viewModel.state().editor)
        assertTrue(viewModel.state().isEditing)
    }
}
