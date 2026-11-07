package com.wobblebot.papotons.ui.board

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.wobblebot.papotons.data.FakePreferences
import com.wobblebot.papotons.data.board.BoardRepository
import com.wobblebot.papotons.data.board.FakeBoardDao
import com.wobblebot.papotons.data.profile.FIRST_PROFILE_ID
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.domain.CellContent
import com.wobblebot.papotons.domain.HOME_BOARD_ID
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/** Changer d'utilisateur côté écran : ses grilles, sa taille, et rien de l'autre. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSwitchTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val speaker = FakeSpeaker()
    private val preferences = FakePreferences()
    private val context = mockk<Context> {
        // Les libellés du vocabulaire de base : « label<id de ressource> ».
        every { getString(any()) } answers { "label${firstArg<Int>()}" }
        every { filesDir } answers { File("build/tmp/no-files") }
    }
    private val dao = FakeBoardDao()
    private val repository = BoardRepository(dao, preferences, context).also { it.fileDispatcher = dispatcher }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.startViewModel(): BoardViewModel {
        val viewModel = BoardViewModel(repository, speaker, FakeMediaVolume(), FakeArasaacClient(), FakePictogramStore(), FakePhotoStore(), FakeConjugations(), SavedStateHandle())
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    private fun BoardViewModel.state() = uiState.value

    /** Le vocabulaire de base inséré, puis un second utilisateur. */
    private suspend fun createAnna(): Long {
        repository.ensureHomeBoard()
        return repository.createProfile("Anna")
    }

    private fun homeOf(profileId: Long) = dao.boards.value.single { it.profileId == profileId && it.coreBoardId == HOME_BOARD_ID }.id

    private fun BoardViewModel.tapFirstWord() {
        onCellClick(checkNotNull(state().board).grid.flatten().filterNotNull().first { it.linkedBoardId == null })
    }

    @Test
    fun `switching profiles opens the new home, clears the sentence and closes the editor`() = runTest(dispatcher) {
        val anna = createAnna()
        val viewModel = startViewModel()
        viewModel.tapFirstWord()
        viewModel.onStartEditing()
        viewModel.onEmptySlotClick(6, 7)
        assertTrue(viewModel.state().message.isNotEmpty())
        assertNotNull(viewModel.state().editor)
        val stopsBefore = speaker.stopCount

        repository.switchProfile(anna)

        assertEquals(homeOf(anna), viewModel.state().board?.id)
        assertTrue(viewModel.state().message.isEmpty())
        assertNull(viewModel.state().editor)
        // La phrase de l'autre ne finit pas de se dire.
        assertEquals(stopsBefore + 1, speaker.stopCount)
    }

    /** Une ouverture déjà partie a lu l'utilisateur d'avant : elle ne doit pas avoir le dernier mot. */
    @Test
    fun `a switch during a slow opening still ends on the new profile`() = runTest(dispatcher) {
        val anna = createAnna()
        val slowRead = CompletableDeferred<Unit>()
        dao.nextProfileReadGate = slowRead
        val viewModel = startViewModel()

        repository.switchProfile(anna)
        slowRead.complete(Unit)

        assertEquals(homeOf(anna), viewModel.state().board?.id)
    }

    /** Un écran en grandes cases d'Anna (ids ≥ 1000) : sa taille se lit par l'id d'origine de la racine. */
    @Test
    fun `the size of a second profile's screen is read from the origin of its root, even in a folder`() = runTest(dispatcher) {
        val anna = createAnna()
        repository.switchProfile(anna)
        val viewModel = startViewModel()
        val annaLarge = repository.createScreen("Maison", BoardSize.LARGE_CELLS)

        viewModel.onScreenChosen(annaLarge)

        assertEquals(annaLarge, viewModel.state().board?.id)
        assertEquals(BoardSize.LARGE_CELLS, viewModel.state().boardSize)
        val folder = checkNotNull(viewModel.state().board).grid.flatten().filterNotNull().first { it.linkedBoardId != null }
        viewModel.onCellClick(folder)
        assertEquals(BoardSize.LARGE_CELLS, viewModel.state().boardSize)
    }

    @Test
    fun `a folder created in a second profile's large cells screen takes their folder size`() = runTest(dispatcher) {
        val anna = createAnna()
        repository.switchProfile(anna)
        val annaLarge = repository.createScreen("Maison", BoardSize.LARGE_CELLS)
        // Les grandes cases sont pleines : une place libérée pour le dossier.
        val freed = dao.cells.value.first { it.boardId == annaLarge && it.linkedBoardId == null }
        dao.cells.value -= freed
        val viewModel = startViewModel()
        viewModel.onScreenChosen(annaLarge)
        viewModel.onStartEditing()

        viewModel.onEmptySlotClick(freed.row, freed.column)
        viewModel.onEditorSave(CellContent("Animaux", null, null, null, null), asFolder = true)

        val created = dao.boards.value.single { it.name == "Animaux" }
        assertEquals(BoardSize.LARGE_CELLS.folderRowCount to BoardSize.LARGE_CELLS.folderColumnCount, created.rowCount to created.columnCount)
        assertEquals(anna, created.profileId)
    }

    /** Les onglets sont ceux de l'utilisateur affiché : ceux de Léo ne restent pas chez Anna. */
    @Test
    fun `switching profiles shows the tabs of the new profile`() = runTest(dispatcher) {
        val anna = createAnna()
        val leoSchool = repository.createScreen("École", BoardSize.LARGE_CELLS)
        val viewModel = startViewModel()
        assertEquals(listOf(homeOf(FIRST_PROFILE_ID), leoSchool), viewModel.state().board?.screens?.map { it.id })

        repository.switchProfile(anna)

        assertEquals(listOf(homeOf(anna)), viewModel.state().board?.screens?.map { it.id })
    }

    /** Invariant 2 : une préférence illisible ne doit jamais empêcher de parler. */
    @Test
    fun `an unreadable open profile still opens boards`() = runTest(dispatcher) {
        createAnna()
        preferences.readFailure = IOException("unreadable")

        val viewModel = startViewModel()

        assertEquals(homeOf(FIRST_PROFILE_ID), viewModel.state().board?.id)
    }
}
