package com.wobblebot.papotons.ui.backup

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.wobblebot.papotons.data.FakePreferences
import com.wobblebot.papotons.data.backup.BackupContent
import com.wobblebot.papotons.data.backup.BackupRefusedException
import com.wobblebot.papotons.data.backup.BackupStore
import com.wobblebot.papotons.data.backup.PendingRestore
import com.wobblebot.papotons.data.backup.RefusalReason
import com.wobblebot.papotons.data.board.BoardEntity
import com.wobblebot.papotons.data.profile.ProfileEntity
import com.wobblebot.papotons.data.board.BoardRepository
import com.wobblebot.papotons.data.board.CellEntity
import com.wobblebot.papotons.data.board.FakeBoardDao
import com.wobblebot.papotons.ui.board.PHOTO_REQUEST_TIMEOUT_MS
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
import java.io.IOException

private val HAS_UNSAVED_CHANGES = booleanPreferencesKey("has_unsaved_changes")

class FakeBackupStore : BackupStore {
    val exported = mutableListOf<Uri>()
    val restored = mutableListOf<PendingRestore>()
    val deletedDocuments = mutableListOf<Uri>()
    var discardCount = 0
    var exportFailure: Exception? = null
    var restoreFailure: Exception? = null
    var leftOutImages = 0
    var analyzeFailure: Exception? = null
    var analyzeGate: CompletableDeferred<Unit>? = null
    var restoreGate: CompletableDeferred<Unit>? = null
    val pending = PendingRestore(
        "papotons.obz",
        BackupContent(listOf(ProfileEntity(1, "Léo", null)), listOf(BoardEntity(1, "home", 1, 1, profileId = 1)), listOf(CellEntity(boardId = 1, row = 0, column = 0, label = "je", linkedBoardId = null, pictogramId = null, category = null))),
    )

    override suspend fun export(destination: Uri): Int {
        exportFailure?.let { throw it }
        exported += destination
        return leftOutImages
    }

    override suspend fun analyze(source: Uri): PendingRestore {
        analyzeGate?.await()
        analyzeFailure?.let { throw it }
        return pending
    }

    override suspend fun restore(pending: PendingRestore) {
        restoreGate?.await()
        restoreFailure?.let { throw it }
        restored += pending
    }

    override suspend fun discard() {
        discardCount++
    }

    override suspend fun deleteDocument(document: Uri) {
        deletedDocuments += document
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = FakeBackupStore()
    private val preferences = FakePreferences()
    private val document: Uri = mockk(relaxed = true)
    private var nowMs = 0L
    private val context = mockk<Context> {
        every { filesDir } answers { File("build/tmp/no-files") }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(): BackupViewModel =
        BackupViewModel(store, BoardRepository(FakeBoardDao(), preferences, context)).also { viewModel ->
            viewModel.elapsedRealtimeMs = { nowMs }
            backgroundScope.launch { viewModel.uiState.collect {} }
        }

    /** La confirmation de restauration nomme ceux qu'elle remplacerait, à jour. */
    @Test
    fun `the current profile names are known and follow changes`() = runTest(dispatcher) {
        val labels = mockk<Context> {
            every { getString(any()) } answers { "label${firstArg<Int>()}" }
            every { filesDir } answers { context.filesDir }
        }
        val repository = BoardRepository(FakeBoardDao(), preferences, labels)
        repository.ensureHomeBoard()
        repository.renameProfile(1, "Léo")
        val viewModel = BackupViewModel(store, repository).also { backgroundScope.launch { it.uiState.collect {} } }

        assertEquals(listOf("Léo"), viewModel.uiState.value.currentProfileNames)
        repository.createProfile("Anna")
        assertEquals(listOf("Léo", "Anna"), viewModel.uiState.value.currentProfileNames)
    }

    @Test
    fun `a backup saved in time is written and said`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onOpen()

        assertEquals("papotons-%1\$s.obz".format(java.time.LocalDate.now()), viewModel.onSaveRequested("papotons-%1\$s.obz"))
        assertTrue(viewModel.isAwaitingDocument)
        assertTrue(viewModel.onSaveDocument(document))

        assertFalse(viewModel.isAwaitingDocument)
        assertEquals(listOf(document), store.exported)
        assertEquals(BackupMessage.Saved, viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.isWorking)
    }

    @Test
    fun `a document coming back too late is deleted and ends the edit`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onSaveRequested("%1\$s")
        nowMs += PHOTO_REQUEST_TIMEOUT_MS + 1

        assertFalse(viewModel.onSaveDocument(document))

        assertTrue(store.exported.isEmpty())
        assertEquals(listOf(document), store.deletedDocuments)
    }

    @Test
    fun `a backup document coming back after the process died is still written`() = runTest(dispatcher) {
        val viewModel = viewModel()

        assertTrue(viewModel.onSaveDocument(document))

        assertEquals(listOf(document), store.exported)
        assertTrue(store.deletedDocuments.isEmpty())
    }

    @Test
    fun `a restore document nobody asked for in this process is never read`() = runTest(dispatcher) {
        val viewModel = viewModel()

        assertTrue(viewModel.onRestoreDocument(document))

        assertNull(viewModel.uiState.value.pendingRestore)
        assertFalse(viewModel.uiState.value.isWorking)
    }

    @Test
    fun `a cancelled request changes nothing`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onSaveRequested("%1\$s")

        assertTrue(viewModel.onSaveDocument(null))

        assertFalse(viewModel.isAwaitingDocument)
        assertTrue(store.exported.isEmpty() && store.deletedDocuments.isEmpty())
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `a failed backup says so`() = runTest(dispatcher) {
        val viewModel = viewModel()
        store.exportFailure = IOException("disk full")
        viewModel.onSaveRequested("%1\$s")

        viewModel.onSaveDocument(document)

        assertEquals(BackupMessage.SaveFailed, viewModel.uiState.value.message)
    }

    @Test
    fun `a restore waits for the confirmation, then replaces`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onRestoreRequested()

        assertTrue(viewModel.onRestoreDocument(document))
        assertEquals(store.pending, viewModel.uiState.value.pendingRestore)
        assertTrue(store.restored.isEmpty())

        viewModel.onConfirmRestore()

        assertEquals(listOf(store.pending), store.restored)
        assertNull(viewModel.uiState.value.pendingRestore)
        assertEquals(BackupMessage.Restored, viewModel.uiState.value.message)
    }

    @Test
    fun `a cancelled restore writes nothing and forgets the file`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onRestoreRequested()
        viewModel.onRestoreDocument(document)

        viewModel.onCancelRestore()

        assertTrue(store.restored.isEmpty())
        assertEquals(1, store.discardCount)
        assertNull(viewModel.uiState.value.pendingRestore)
    }

    @Test
    fun `a refused backup says why`() = runTest(dispatcher) {
        val viewModel = viewModel()
        store.analyzeFailure = BackupRefusedException(RefusalReason.NotPapotons, "No backup mark")
        viewModel.onRestoreRequested()

        viewModel.onRestoreDocument(document)

        assertEquals(BackupMessage.NotPapotons, viewModel.uiState.value.message)
        assertNull(viewModel.uiState.value.pendingRestore)
    }

    @Test
    fun `a restore coming back too late ends the edit without reading`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onRestoreRequested()
        nowMs += PHOTO_REQUEST_TIMEOUT_MS + 1

        assertFalse(viewModel.onRestoreDocument(document))

        assertNull(viewModel.uiState.value.pendingRestore)
    }

    @Test
    fun `the end of the edit closes everything and forgets a pending restore`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onOpen()
        viewModel.onRestoreRequested()
        viewModel.onRestoreDocument(document)

        viewModel.onEditingEnded()

        assertFalse(viewModel.uiState.value.isDialogOpen)
        assertNull(viewModel.uiState.value.pendingRestore)
        assertEquals(1, store.discardCount)
        // Rouverte, la sauvegarde ne propose plus l'ancienne restauration.
        viewModel.onOpen()
        assertNull(viewModel.uiState.value.pendingRestore)
    }

    @Test
    fun `the dialog does not close while working`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onOpen()
        viewModel.onRestoreRequested()
        viewModel.onRestoreDocument(document)
        store.restoreGate = CompletableDeferred()
        viewModel.onConfirmRestore()

        viewModel.onClose()

        assertTrue(viewModel.uiState.value.isDialogOpen)
        checkNotNull(store.restoreGate).complete(Unit)
        viewModel.onClose()
        assertFalse(viewModel.uiState.value.isDialogOpen)
    }

    @Test
    fun `changes count as unsaved until a backup says otherwise`() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.hasUnsavedChanges)

        preferences.edit { it[HAS_UNSAVED_CHANGES] = false }

        assertFalse(viewModel.uiState.value.hasUnsavedChanges)
    }

    @Test
    fun `a reading still running when the edit ends never brings its confirmation back`() = runTest(dispatcher) {
        val viewModel = viewModel()
        store.analyzeGate = CompletableDeferred()
        viewModel.onRestoreRequested()
        viewModel.onRestoreDocument(document)

        viewModel.onEditingEnded()
        checkNotNull(store.analyzeGate).complete(Unit)

        assertNull(viewModel.uiState.value.pendingRestore)
        assertFalse(viewModel.uiState.value.isWorking)
        assertEquals(1, store.discardCount)
        viewModel.onOpen()
        assertNull(viewModel.uiState.value.pendingRestore)
    }

    @Test
    fun `each refusal has its own message`() = runTest(dispatcher) {
        val expected = mapOf(
            RefusalReason.NotPapotons to BackupMessage.NotPapotons,
            RefusalReason.Unreadable to BackupMessage.Unreadable,
            RefusalReason.TooLarge to BackupMessage.TooLarge,
            RefusalReason.Invalid to BackupMessage.Invalid,
        )
        val viewModel = viewModel()
        for ((reason, message) in expected) {
            store.analyzeFailure = BackupRefusedException(reason, "test")
            viewModel.onRestoreRequested()
            viewModel.onRestoreDocument(document)
            assertEquals(reason.name, message, viewModel.uiState.value.message)
        }
    }

    @Test
    fun `a backup the restore would refuse is a failed backup, not a refused file`() = runTest(dispatcher) {
        val viewModel = viewModel()
        store.exportFailure = BackupRefusedException(RefusalReason.Invalid, "test")
        viewModel.onSaveRequested("%1\$s")

        viewModel.onSaveDocument(document)

        assertEquals(BackupMessage.SaveFailed, viewModel.uiState.value.message)
    }

    @Test
    fun `a result reached while the dialog was closed is shown when it opens again`() = runTest(dispatcher) {
        val viewModel = viewModel()
        store.restoreGate = CompletableDeferred()
        viewModel.onRestoreRequested()
        viewModel.onRestoreDocument(document)
        viewModel.onConfirmRestore()

        viewModel.onEditingEnded()
        checkNotNull(store.restoreGate).complete(Unit)
        viewModel.onOpen()

        assertEquals(BackupMessage.Restored, viewModel.uiState.value.message)
    }

    @Test
    fun `a request cancelled too late still ends the edit`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onRestoreRequested()
        nowMs += PHOTO_REQUEST_TIMEOUT_MS + 1
        assertFalse(viewModel.onRestoreDocument(null))

        viewModel.onSaveRequested("%1\$s")
        nowMs += PHOTO_REQUEST_TIMEOUT_MS + 1
        assertFalse(viewModel.onSaveDocument(null))
    }

    @Test
    fun `a backup that left damaged images out says so`() = runTest(dispatcher) {
        val viewModel = viewModel()
        store.leftOutImages = 2
        viewModel.onSaveRequested("%1\$s")

        viewModel.onSaveDocument(document)

        assertEquals(BackupMessage.SavedWithoutSomeImages, viewModel.uiState.value.message)
    }

    @Test
    fun `a backup too large to restore says what to do`() = runTest(dispatcher) {
        val viewModel = viewModel()
        store.exportFailure = BackupRefusedException(RefusalReason.TooLarge, "test")
        viewModel.onSaveRequested("%1\$s")

        viewModel.onSaveDocument(document)

        assertEquals(BackupMessage.SaveTooLarge, viewModel.uiState.value.message)
    }

    @Test
    fun `a reading or a restore that fails says the boards did not change`() = runTest(dispatcher) {
        val viewModel = viewModel()
        store.analyzeFailure = IOException("gone")
        viewModel.onRestoreRequested()
        viewModel.onRestoreDocument(document)
        assertEquals(BackupMessage.RestoreFailed, viewModel.uiState.value.message)

        store.analyzeFailure = null
        store.restoreFailure = IOException("disk full")
        viewModel.onRestoreRequested()
        viewModel.onRestoreDocument(document)
        viewModel.onConfirmRestore()

        assertEquals(BackupMessage.RestoreFailed, viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.isWorking)
        assertNull(viewModel.uiState.value.pendingRestore)
    }

    @Test
    fun `a document request that cannot open stops waiting`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onRestoreRequested()

        viewModel.onDocumentRequestFailed()

        // Sinon, un départ (`ON_STOP`) ne terminerait plus jamais l'édition.
        assertFalse(viewModel.isAwaitingDocument)
        assertEquals(BackupMessage.NoDocumentApp, viewModel.uiState.value.message)
    }

    @Test
    fun `the two minutes count from the request`() = runTest(dispatcher) {
        val viewModel = viewModel()
        nowMs = 600_000
        viewModel.onSaveRequested("%1\$s")
        nowMs += PHOTO_REQUEST_TIMEOUT_MS
        assertTrue(viewModel.onSaveDocument(document))

        viewModel.onSaveRequested("%1\$s")
        nowMs += PHOTO_REQUEST_TIMEOUT_MS + 1
        assertFalse(viewModel.onSaveDocument(document))
    }
}
