package com.wobblebot.papotons.ui.settings

import android.content.Context
import com.wobblebot.papotons.R
import com.wobblebot.papotons.data.FakePreferences
import com.wobblebot.papotons.data.board.BoardRepository
import com.wobblebot.papotons.data.board.FakeBoardDao
import com.wobblebot.papotons.data.profile.FIRST_PROFILE_ID
import com.wobblebot.papotons.domain.CellContent
import com.wobblebot.papotons.domain.HOME_BOARD_ID
import com.wobblebot.papotons.ui.board.FakePhotoStore
import io.mockk.every
import io.mockk.mockk
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
import java.io.IOException

private const val ANNA_PHOTO = "0a1b2c3d-0000-4000-8000-00000000000a.jpg"
private const val LEO_PHOTO = "0a1b2c3d-0000-4000-8000-00000000000b.jpg"

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilesViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val preferences = FakePreferences()
    private val context = mockk<Context> {
        every { getString(any()) } answers { "label${firstArg<Int>()}" }
        every { filesDir } answers { File("build/tmp/no-files") }
    }
    private val dao = FakeBoardDao()
    private val repository = BoardRepository(dao, preferences, context)
    private val photos = FakePhotoStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(): ProfilesViewModel = ProfilesViewModel(repository, photos).also { viewModel ->
        backgroundScope.launch { viewModel.uiState.collect {} }
    }

    /** Léo (ouvert, avec une photo) et Anna (avec une photo). */
    private suspend fun leoAndAnna(): Long {
        repository.ensureHomeBoard()
        val anna = repository.createProfile("Anna")
        val annaHome = dao.boards.value.single { it.profileId == anna && it.coreBoardId == HOME_BOARD_ID }.id
        repository.addWord(annaHome, 6, 7, CellContent("Mamie", null, null, ANNA_PHOTO, null))
        repository.addWord(HOME_BOARD_ID, 6, 7, CellContent("Papi", null, null, LEO_PHOTO, null))
        return anna
    }

    @Test
    fun `deleting a profile deletes its photos, and only after the database`() = runTest(dispatcher) {
        val anna = leoAndAnna()
        val viewModel = viewModel()

        viewModel.onDelete(anna)

        assertEquals(listOf(ANNA_PHOTO), photos.deleted)
        assertEquals(listOf(FIRST_PROFILE_ID), viewModel.uiState.value.profiles.map { it.id })
        assertNull(viewModel.uiState.value.failure)
    }

    @Test
    fun `a refused deletion deletes no photo and says so`() = runTest(dispatcher) {
        leoAndAnna()
        val viewModel = viewModel()
        dao.boards.value = dao.boards.value.filter { it.profileId == FIRST_PROFILE_ID }

        // Anna n'a plus de grille : Léo est le dernier qui en a.
        viewModel.onDelete(FIRST_PROFILE_ID)

        assertTrue(photos.deleted.isEmpty())
        assertEquals(ProfileFailure.NotDeleted, viewModel.uiState.value.failure)
        assertFalse(viewModel.uiState.value.isWorking)
    }

    @Test
    fun `touching the open profile changes nothing`() = runTest(dispatcher) {
        leoAndAnna()
        val viewModel = viewModel()
        val switches = repository.profileSwitches.value

        viewModel.onSwitch(FIRST_PROFILE_ID)

        assertEquals(switches, repository.profileSwitches.value)
    }

    @Test
    fun `touching another profile opens it`() = runTest(dispatcher) {
        val anna = leoAndAnna()
        val viewModel = viewModel()

        viewModel.onSwitch(anna)

        assertEquals(anna, viewModel.uiState.value.openProfileId)
    }

    @Test
    fun `a failed switch is shown, then forgotten when the settings open again`() = runTest(dispatcher) {
        val anna = leoAndAnna()
        val viewModel = viewModel()
        preferences.writeFailure = IOException("disk full")

        viewModel.onSwitch(anna)

        assertEquals(ProfileFailure.NotSwitched, viewModel.uiState.value.failure)
        assertEquals(FIRST_PROFILE_ID, viewModel.uiState.value.openProfileId)
        viewModel.onShown()
        assertNull(viewModel.uiState.value.failure)
    }

    @Test
    fun `a name the repository refuses is reported, not a crash`() = runTest(dispatcher) {
        repository.ensureHomeBoard()
        val viewModel = viewModel()

        viewModel.onCreate(" ")

        assertEquals(ProfileFailure.NotCreated, viewModel.uiState.value.failure)
        assertEquals(1, viewModel.uiState.value.profiles.size)
    }

    @Test
    fun `a created profile is listed`() = runTest(dispatcher) {
        repository.ensureHomeBoard()
        val viewModel = viewModel()

        viewModel.onCreate("Anna")

        assertEquals(listOf("label${R.string.profile_default_name}", "Anna"), viewModel.uiState.value.profiles.map { it.name })
    }
}
