package com.wobblebot.papotons.ui.backup

import android.content.Context
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.wobblebot.papotons.data.FakePreferences
import com.wobblebot.papotons.data.backup.BackupContent
import com.wobblebot.papotons.data.backup.BackupRefusedException
import com.wobblebot.papotons.data.backup.BackupStore
import com.wobblebot.papotons.data.backup.PendingRestore
import com.wobblebot.papotons.data.backup.RecoveryReport
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

private val HAS_UNSAVED_CHANGES = booleanPreferencesKey("has_unsaved_changes")

class FakeBackupStore : BackupStore {
    val exported = mutableListOf<Uri>()
    val restored = mutableListOf<PendingRestore>()
    val documentsOfferedForDeletion = mutableListOf<Uri>()
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

    override suspend fun deleteEmptyDocument(document: Uri) {
        documentsOfferedForDeletion += document
    }

    var hasCopies = false
    var recoverable: List<PendingRestore> = emptyList()
    var recoveryFailure: Exception? = null
    var recoveryGate: CompletableDeferred<Unit>? = null

    override suspend fun hasCorruptedCopies(): Boolean = hasCopies

    var onRecoveryRead: () -> Unit = {}

    override suspend fun analyzeCorruptedCopies(): List<PendingRestore> {
        onRecoveryRead()
        recoveryGate?.await()
        recoveryFailure?.let { throw it }
        return recoverable
    }

    /** Une copie relue, d'utilisateur [name] : deux se distinguent à leur nom. */
    fun recovered(name: String) = PendingRestore(
        fileName = null,
        content = pending.content.copy(profiles = listOf(ProfileEntity(1, name, null))),
        recovery = RecoveryReport(copyName = "000001-$name", copiedAtMs = 1_000L, announcedCellCount = 2, leftOutRowCount = 1),
    )
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
    fun `a document coming back too late is offered for deletion if empty and ends the edit`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onSaveRequested("%1\$s")
        nowMs += PHOTO_REQUEST_TIMEOUT_MS + 1

        assertFalse(viewModel.onSaveDocument(document))

        assertTrue(store.exported.isEmpty())
        assertEquals(listOf(document), store.documentsOfferedForDeletion)
    }

    @Test
    fun `a backup document coming back after the process died is still written`() = runTest(dispatcher) {
        val viewModel = viewModel()

        assertTrue(viewModel.onSaveDocument(document))

        assertEquals(listOf(document), store.exported)
        assertTrue(store.documentsOfferedForDeletion.isEmpty())
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
        assertTrue(store.exported.isEmpty() && store.documentsOfferedForDeletion.isEmpty())
        assertNull(viewModel.uiState.value.message)
    }

    /** Une exception que la sauvegarde ne prévoit pas : un bug. Classe à part, pour ne pas la confondre. */
    private class ProgrammingBug : RuntimeException()

    @Test
    fun `an unexpected failure of a backup is not shown as a failed save`() {
        val bug = ProgrammingBug()
        store.exportFailure = bug

        // Un bug plante l'appli (Android vitals le voit) : `runTest` relance l'exception qu'aucun `catch` n'a prise.
        val thrown = assertThrows(ProgrammingBug::class.java) {
            runTest(dispatcher) {
                val viewModel = viewModel()
                viewModel.onSaveRequested("%1\$s")
                viewModel.onSaveDocument(document)
            }
        }

        assertSame(bug, thrown)
    }

    @Test
    fun `a revoked document or an unreadable database is a failed save, not a crash`() = runTest(dispatcher) {
        for (failure in listOf(SecurityException("revoked"), SQLiteException("unreadable"))) {
            store.exportFailure = failure
            val viewModel = viewModel()
            viewModel.onSaveRequested("%1\$s")
            viewModel.onSaveDocument(document)

            assertEquals(failure.javaClass.simpleName, BackupMessage.SaveFailed, viewModel.uiState.value.message)
        }
    }

    @Test
    fun `an unreadable database during a restore is a failed restore, not a crash`() = runTest(dispatcher) {
        store.restoreFailure = SQLiteException("unreadable")
        val viewModel = viewModel()
        viewModel.onRestoreRequested()
        viewModel.onRestoreDocument(document)
        viewModel.onConfirmRestore()

        assertEquals(BackupMessage.RestoreFailed, viewModel.uiState.value.message)
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

    @Test
    fun `the recovery button shows only while a damaged copy exists`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onOpen()
        assertFalse(viewModel.uiState.value.hasCorruptedCopies)

        store.hasCopies = true
        viewModel.onClose()
        viewModel.onOpen()

        assertTrue(viewModel.uiState.value.hasCorruptedCopies)

        // Supprimées entre deux ouvertures (données effacées, par exemple) : le bouton s'en va.
        store.hasCopies = false
        viewModel.onClose()
        viewModel.onOpen()
        assertFalse(viewModel.uiState.value.hasCorruptedCopies)
    }

    @Test
    fun `the recovery button is known before the first opening`() = runTest(dispatcher) {
        store.hasCopies = true

        val viewModel = viewModel()

        assertTrue(viewModel.uiState.value.hasCorruptedCopies)
    }

    @Test
    fun `copies with nothing readable say so`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onOpen()

        viewModel.onRecoverRequested()

        assertEquals(BackupMessage.NothingRecovered, viewModel.uiState.value.message)
        assertNull(viewModel.uiState.value.pendingRestore)
        assertTrue(viewModel.uiState.value.recoveryChoices.isEmpty())
        assertFalse(viewModel.uiState.value.isWorking)
    }

    @Test
    fun `a single readable copy goes straight to the confirmation, then says to read the boards again`() = runTest(dispatcher) {
        val copy = store.recovered("Léo")
        store.recoverable = listOf(copy)
        val viewModel = viewModel()
        viewModel.onOpen()

        viewModel.onRecoverRequested()

        assertSame(copy, viewModel.uiState.value.pendingRestore)
        assertTrue(viewModel.uiState.value.recoveryChoices.isEmpty())
        viewModel.onConfirmRestore()
        assertEquals(listOf(copy), store.restored)
        assertEquals(BackupMessage.Recovered, viewModel.uiState.value.message)
    }

    @Test
    fun `several readable copies are offered, and the chosen one is confirmed`() = runTest(dispatcher) {
        val newer = store.recovered("Léo")
        val older = store.recovered("Marie")
        store.recoverable = listOf(newer, older)
        val viewModel = viewModel()
        viewModel.onOpen()

        viewModel.onRecoverRequested()
        assertEquals(listOf(newer, older), viewModel.uiState.value.recoveryChoices)
        assertNull(viewModel.uiState.value.pendingRestore)

        viewModel.onRecoveryChosen(older)

        assertSame(older, viewModel.uiState.value.pendingRestore)
        viewModel.onConfirmRestore()
        assertEquals(listOf(older), store.restored)
        assertTrue(viewModel.uiState.value.recoveryChoices.isEmpty())
    }

    @Test
    fun `cancelling the confirmation of a chosen copy brings the choice back`() = runTest(dispatcher) {
        val copies = listOf(store.recovered("Léo"), store.recovered("Marie"))
        store.recoverable = copies
        val viewModel = viewModel()
        viewModel.onOpen()
        viewModel.onRecoverRequested()
        viewModel.onRecoveryChosen(copies[1])

        viewModel.onCancelRestore()

        assertNull(viewModel.uiState.value.pendingRestore)
        assertEquals(copies, viewModel.uiState.value.recoveryChoices)
        assertTrue(store.restored.isEmpty())
    }

    @Test
    fun `a second tap while the copies are read starts no second reading`() = runTest(dispatcher) {
        var readings = 0
        store.recoveryGate = CompletableDeferred()
        store.onRecoveryRead = { readings++ }
        val viewModel = viewModel()
        viewModel.onOpen()

        viewModel.onRecoverRequested()
        viewModel.onRecoverRequested()
        store.recoveryGate?.complete(Unit)

        assertEquals(1, readings)
    }

    @Test
    fun `a choice no longer offered confirms nothing`() = runTest(dispatcher) {
        store.recoverable = listOf(store.recovered("Léo"), store.recovered("Marie"))
        val viewModel = viewModel()
        viewModel.onOpen()
        viewModel.onRecoverRequested()
        viewModel.onCancelRecoveryChoice()

        // Un second appui sur une ligne de la modale qui se ferme.
        viewModel.onRecoveryChosen(store.recoverable.first())

        assertNull(viewModel.uiState.value.pendingRestore)
        assertTrue(viewModel.uiState.value.recoveryChoices.isEmpty())
    }

    @Test
    fun `the end of the edit forgets the copies offered`() = runTest(dispatcher) {
        store.recoverable = listOf(store.recovered("Léo"), store.recovered("Marie"))
        val viewModel = viewModel()
        viewModel.onOpen()
        viewModel.onRecoverRequested()

        viewModel.onEditingEnded()

        assertTrue(viewModel.uiState.value.recoveryChoices.isEmpty())
    }

    @Test
    fun `a reading of the copies still running when the edit ends never brings its choice back`() = runTest(dispatcher) {
        store.recoverable = listOf(store.recovered("Léo"), store.recovered("Marie"))
        store.recoveryGate = CompletableDeferred()
        val viewModel = viewModel()
        viewModel.onOpen()
        viewModel.onRecoverRequested()

        viewModel.onEditingEnded()
        store.recoveryGate?.complete(Unit)

        assertTrue(viewModel.uiState.value.recoveryChoices.isEmpty())
        assertFalse(viewModel.uiState.value.isWorking)
    }

    @Test
    fun `copies that could not be read say the boards did not change`() = runTest(dispatcher) {
        store.recoveryFailure = IOException("No copy could be read")
        val viewModel = viewModel()
        viewModel.onOpen()

        viewModel.onRecoverRequested()

        assertEquals(BackupMessage.RestoreFailed, viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.isWorking)
    }
}
