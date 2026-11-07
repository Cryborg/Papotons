package com.wobblebot.papotons.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wobblebot.papotons.data.board.BoardRepository
import com.wobblebot.papotons.data.photo.PhotoStore
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.ui.board.STOP_TIMEOUT_MS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    private val work = SettingsWork<ScreenFailure>(viewModelScope, TAG)

    val uiState: StateFlow<ScreensUiState> = combine(work.isWorking, work.failure, ::ScreensUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ScreensUiState())

    /** À l'ouverture des Réglages : un ancien échec ne s'affiche plus. */
    fun onShown() {
        work.clearFailure()
    }

    fun onCreate(name: String, size: BoardSize) = work.run(ScreenFailure.NotCreated, targetId = null) { repository.createScreen(name, size) }

    fun onRename(screenId: Long, name: String) = work.run(ScreenFailure.NotRenamed, screenId) { repository.renameScreen(screenId, name) }

    /** Les photos de ses cases sont effacées après la suppression en base : jamais avant. */
    fun onDelete(screenId: Long) = work.run(ScreenFailure.NotDeleted, screenId) {
        repository.deleteScreen(screenId).forEach { photos.delete(it) }
    }

    /** [offset] : 1 vers la fin des onglets, -1 vers le début. */
    fun onMove(screenId: Long, offset: Int) = work.run(ScreenFailure.NotMoved, screenId) { repository.moveScreen(screenId, offset) }

}
