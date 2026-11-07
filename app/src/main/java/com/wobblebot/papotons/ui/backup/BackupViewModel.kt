package com.wobblebot.papotons.ui.backup

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wobblebot.papotons.data.backup.BackupRefusedException
import com.wobblebot.papotons.data.backup.BackupStore
import com.wobblebot.papotons.data.backup.PendingRestore
import com.wobblebot.papotons.data.backup.RefusalReason
import com.wobblebot.papotons.data.board.BoardRepository
import com.wobblebot.papotons.ui.board.PHOTO_REQUEST_TIMEOUT_MS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

private const val TAG = "BackupViewModel"
private const val STOP_TIMEOUT_MS = 5_000L

/** Ce que la modale de sauvegarde dit après un travail. */
enum class BackupMessage { Saved, SavedWithoutSomeImages, SaveFailed, SaveTooLarge, Restored, RestoreFailed, NotPapotons, Unreadable, TooLarge, Invalid, NoDocumentApp }

@Immutable
data class BackupUiState(
    val isDialogOpen: Boolean = false,
    /** Un export, une lecture ou une restauration tourne : boutons grisés. */
    val isWorking: Boolean = false,
    val message: BackupMessage? = null,
    /** Une sauvegarde lue et valide, en attente de confirmation. */
    val pendingRestore: PendingRestore? = null,
    /** Vrai aussi quand on ne sait pas : la pastille se trompe du bon côté. */
    val hasUnsavedChanges: Boolean = true,
)

/**
 * La sauvegarde et la restauration, en mode édition seulement (l'écran ferme tout à la fin de
 * l'édition). Le document est choisi par le système : l'appli n'envoie rien (invariant 1).
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val store: BackupStore,
    repository: BoardRepository,
) : ViewModel() {
    private val dialog = MutableStateFlow(BackupUiState())

    val uiState: StateFlow<BackupUiState> = combine(dialog, repository.observeUnsavedChanges()) { state, hasUnsavedChanges ->
        state.copy(hasUnsavedChanges = hasUnsavedChanges)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BackupUiState())

    /** Remplaçable dans les tests. */
    internal var elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime

    /** Quand le créateur ou le sélecteur de document a été ouvert ; `null` sans demande en cours. */
    private var documentRequestedAtMs: Long? = null

    /** Le système ouvre une autre appli pour le document : ce départ ne termine pas l'édition. */
    val isAwaitingDocument: Boolean get() = documentRequestedAtMs != null

    /** La lecture d'une sauvegarde en cours : une fin d'édition l'abandonne. */
    private var analysis: Job? = null

    /** Un message arrivé modale fermée (fin d'édition pendant un travail) reste jusqu'à la prochaine ouverture. */
    fun onOpen() {
        dialog.update { it.copy(isDialogOpen = true) }
    }

    fun onClose() {
        if (dialog.value.isWorking) return
        dialog.update { it.copy(isDialogOpen = false, message = null) }
    }

    /** Rend le nom proposé pour le fichier ([fileNamePattern] : un `%1$s` pour la date). */
    fun onSaveRequested(fileNamePattern: String): String {
        documentRequestedAtMs = elapsedRealtimeMs()
        return fileNamePattern.format(LocalDate.now().toString())
    }

    fun onRestoreRequested() {
        documentRequestedAtMs = elapsedRealtimeMs()
    }

    fun onDocumentRequestFailed() {
        documentRequestedAtMs = null
        dialog.update { it.copy(message = BackupMessage.NoDocumentApp) }
    }

    /**
     * Le document créé pour la sauvegarde. Faux s'il revient plus de [PHOTO_REQUEST_TIMEOUT_MS] après
     * la demande : l'adulte est parti, l'édition doit se terminer, et le document vide est supprimé.
     */
    fun onSaveDocument(document: Uri?): Boolean {
        // Aucune demande dans ce processus : il est mort pendant le créateur de document, que l'adulte
        // a ouvert derrière le contrôle parental. Écrire une sauvegarde ne détruit rien : on l'écrit.
        val isInTime = takeRequestInTime() ?: true
        // Trop tard, même annulé : l'adulte est parti, l'édition se termine.
        if (!isInTime) {
            if (document != null) viewModelScope.launch { store.deleteDocument(document) }
            return false
        }
        if (document == null) return true
        work(success = null, failure = BackupMessage.SaveFailed, isRestoring = false) {
            val leftOut = store.export(document)
            dialog.update { it.copy(message = if (leftOut == 0) BackupMessage.Saved else BackupMessage.SavedWithoutSomeImages) }
        }
        return true
    }

    /** La sauvegarde choisie. Faux si elle revient trop tard (voir [onSaveDocument]). */
    fun onRestoreDocument(document: Uri?): Boolean {
        val isInTime = takeRequestInTime() ?: return true
        if (!isInTime) return false
        if (document == null) return true
        analysis = work(success = null, failure = BackupMessage.RestoreFailed, isRestoring = true) {
            val pending = store.analyze(document)
            dialog.update { it.copy(pendingRestore = pending) }
        }
        return true
    }

    fun onConfirmRestore() {
        val pending = dialog.value.pendingRestore ?: return
        dialog.update { it.copy(pendingRestore = null) }
        work(success = BackupMessage.Restored, failure = BackupMessage.RestoreFailed, isRestoring = true) { store.restore(pending) }
    }

    fun onCancelRestore() {
        if (dialog.value.pendingRestore == null) return
        dialog.update { it.copy(pendingRestore = null) }
        viewModelScope.launch { store.discard() }
    }

    /** Fin de l'édition : tout se ferme, une sauvegarde lue et pas confirmée est oubliée. */
    fun onEditingEnded() {
        // Une lecture encore en cours ne doit pas reposer sa confirmation à l'entrée suivante en édition.
        val wasAnalyzing = analysis?.isActive == true
        analysis?.cancel()
        analysis = null
        val hadPending = dialog.value.pendingRestore != null
        dialog.update { it.copy(isDialogOpen = false, pendingRestore = null) }
        if (hadPending || wasAnalyzing) viewModelScope.launch { store.discard() }
    }

    /** `null` sans demande en cours ; sinon si le résultat arrive à temps. La demande est consommée. */
    private fun takeRequestInTime(): Boolean? {
        val requestedAt = documentRequestedAtMs ?: return null
        documentRequestedAtMs = null
        val isInTime = elapsedRealtimeMs() - requestedAt <= PHOTO_REQUEST_TIMEOUT_MS
        if (!isInTime) Log.i(TAG, "Document came back too late, ignored")
        return isInTime
    }

    /** [isRestoring] : un refus dit pourquoi la sauvegarde ne se restaure pas ; à l'export, c'est un échec. */
    private fun work(success: BackupMessage?, failure: BackupMessage, isRestoring: Boolean, block: suspend () -> Unit): Job {
        dialog.update { it.copy(isWorking = true, message = null) }
        return viewModelScope.launch {
            val message = try {
                block()
                success ?: dialog.value.message
            } catch (exception: CancellationException) {
                dialog.update { it.copy(isWorking = false) }
                throw exception
            } catch (exception: BackupRefusedException) {
                Log.e(TAG, "Backup refused (${exception.reason}): ${exception.message}")
                when {
                    isRestoring -> exception.reason.toMessage()
                    // Permanent : réessayer ne servirait à rien, il faut alléger les grilles.
                    exception.reason == RefusalReason.TooLarge -> BackupMessage.SaveTooLarge
                    else -> failure
                }
            } catch (exception: Exception) {
                // La classe seulement : le message d'un fournisseur de documents cite souvent l'adresse.
                Log.e(TAG, "Backup work failed: ${exception.javaClass.simpleName}")
                failure
            }
            dialog.update { it.copy(isWorking = false, message = message) }
        }
    }
}

private fun RefusalReason.toMessage(): BackupMessage = when (this) {
    RefusalReason.NotPapotons -> BackupMessage.NotPapotons
    RefusalReason.Unreadable -> BackupMessage.Unreadable
    RefusalReason.TooLarge -> BackupMessage.TooLarge
    RefusalReason.Invalid -> BackupMessage.Invalid
}
