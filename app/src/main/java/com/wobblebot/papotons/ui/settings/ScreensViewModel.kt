package com.wobblebot.papotons.ui.settings

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wobblebot.papotons.data.board.BoardRepository
import com.wobblebot.papotons.data.photo.PhotoStore
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.ui.board.STOP_TIMEOUT_MS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

private const val TAG = "ScreensViewModel"

/** Une action sur les écrans qui n'a rien changé. */
enum class ScreenFailure { NotCreated, NotRenamed, NotDeleted, NotMoved }

/** Les écrans eux-mêmes viennent de la grille affichée ([com.wobblebot.papotons.ui.board.DisplayedBoard.screens]). */
@Immutable
data class ScreensUiState(
    /** Une écriture tourne : les boutons attendent (un double appui sur « Monter » ne défait pas le premier). */
    val isWorking: Boolean = false,
    val failure: ScreenFailure? = null,
)

/** Les écrans de l'utilisateur ouvert, dans les Réglages (derrière le contrôle des adultes). */
@HiltViewModel
class ScreensViewModel @Inject constructor(
    private val repository: BoardRepository,
    private val photos: PhotoStore,
) : ViewModel() {
    private val isWorking = MutableStateFlow(false)
    private val failure = MutableStateFlow<ScreenFailure?>(null)

    val uiState: StateFlow<ScreensUiState> = combine(isWorking, failure, ::ScreensUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ScreensUiState())

    /** À l'ouverture des Réglages : un ancien échec ne s'affiche plus. */
    fun onShown() {
        failure.value = null
    }

    fun onCreate(name: String, size: BoardSize) = work(ScreenFailure.NotCreated, screenId = null) { repository.createScreen(name, size) }

    fun onRename(screenId: Long, name: String) = work(ScreenFailure.NotRenamed, screenId) { repository.renameScreen(screenId, name) }

    /** Les photos de ses cases sont effacées après la suppression en base : jamais avant. */
    fun onDelete(screenId: Long) = work(ScreenFailure.NotDeleted, screenId) {
        repository.deleteScreen(screenId).forEach { photos.delete(it) }
    }

    /** [offset] : 1 vers la fin des onglets, -1 vers le début. */
    fun onMove(screenId: Long, offset: Int) = work(ScreenFailure.NotMoved, screenId) { repository.moveScreen(screenId, offset) }

    /** Une action à la fois ; un échec est journalisé avec l'id visé, jamais le nom, et affiché. */
    private fun work(onFailure: ScreenFailure, screenId: Long?, action: suspend () -> Unit) {
        if (isWorking.value) return
        isWorking.value = true
        failure.value = null
        viewModelScope.launch {
            try {
                action()
            } catch (exception: IOException) {
                report(onFailure, screenId, exception)
            } catch (exception: SQLiteException) {
                report(onFailure, screenId, exception)
            } catch (exception: CancellationException) {
                // Une IllegalStateException elle aussi : l'écran qui part n'est pas un échec.
                throw exception
            } catch (exception: IllegalStateException) {
                // Base fermée, utilisateur ouvert pas encore connu : un message, pas un plantage.
                report(onFailure, screenId, exception)
            } catch (exception: IllegalArgumentException) {
                // Nom refusé par le dépôt (la modale aurait dû l'empêcher).
                report(onFailure, screenId, exception)
            } finally {
                isWorking.value = false
            }
        }
    }

    private fun report(onFailure: ScreenFailure, screenId: Long?, exception: Exception) {
        Log.e(TAG, "Screen action failed ($onFailure, screen $screenId)", exception)
        failure.value = onFailure
    }
}
