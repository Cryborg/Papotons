package com.wobblebot.papotons.ui.settings

import com.wobblebot.papotons.data.FakePreferences
import com.wobblebot.papotons.data.caregiver.CaregiverRepository
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CaregiversViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = FakePreferences()
    private val repository = CaregiverRepository(store).also { it.hashDispatcher = dispatcher }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel() = CaregiversViewModel(repository).also { viewModel ->
        backgroundScope.launch { viewModel.uiState.collect {} }
    }

    private fun CaregiversViewModel.marie() = checkNotNull(uiState.value.caregivers).single()

    @Test
    fun `an edit without a new code keeps the code`() = runTest(dispatcher) {
        repository.add("Marie", "4721")
        val viewModel = viewModel()

        viewModel.onSave(viewModel.marie().id, "Marie-Anne", code = null)

        assertEquals("Marie-Anne", viewModel.marie().name)
        assertTrue(viewModel.verify(viewModel.marie().id, "4721"))
    }

    @Test
    fun `a recovered code that cannot be written says so, so the gate still opens`() = runTest(dispatcher) {
        repository.add("Marie", "4721")
        val viewModel = viewModel()
        store.writeFailure = IOException("disk full")

        assertFalse(viewModel.recover(viewModel.marie().id, "0000"))
        store.writeFailure = null
        assertTrue(viewModel.verify(viewModel.marie().id, "4721"))
    }

    @Test
    fun `a failed save is shown, then forgotten when the settings open again`() = runTest(dispatcher) {
        val viewModel = viewModel()
        store.writeFailure = IOException("disk full")

        viewModel.onSave(null, "Marie", "4721")

        assertEquals(CaregiverFailure.NotSaved, viewModel.uiState.value.failure)
        assertFalse(viewModel.uiState.value.isWorking)
        viewModel.onShown()
        assertNull(viewModel.uiState.value.failure)
    }

    /** Deux appuis rapides sur « Enregistrer » : un seul accompagnant. */
    @Test
    fun `one save at a time`() = runTest(dispatcher) {
        val viewModel = viewModel()
        val slowWrite = CompletableDeferred<Unit>()
        store.nextWriteGate = slowWrite

        viewModel.onSave(null, "Marie", "4721")
        viewModel.onSave(null, "Marie", "4721")
        slowWrite.complete(Unit)

        assertEquals(1, checkNotNull(viewModel.uiState.value.caregivers).size)
    }

    @Test
    fun `a failed deletion says it was a deletion`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onDelete(99)

        assertEquals(CaregiverFailure.NotDeleted, viewModel.uiState.value.failure)
    }

    @Test
    fun `the list is unknown until read, then known even when empty`() = runTest(dispatcher) {
        val viewModel = CaregiversViewModel(repository)
        assertNull(viewModel.uiState.value.caregivers)

        backgroundScope.launch { viewModel.uiState.collect {} }

        assertEquals(emptyList<Any>(), viewModel.uiState.value.caregivers)
    }
}
