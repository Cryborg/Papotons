package com.wobblebot.papotons.ui.settings

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wobblebot.papotons.data.board.BoardRepository
import com.wobblebot.papotons.data.photo.PhotoStore
import com.wobblebot.papotons.domain.Profile
import com.wobblebot.papotons.ui.board.STOP_TIMEOUT_MS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

private const val TAG = "ProfilesViewModel"

/** Une action sur les utilisateurs qui n'a rien changé. */
enum class ProfileFailure { NotCreated, NotRenamed, NotDeleted, NotSwitched, ExitLockNotSaved }

@Immutable
data class ProfilesUiState(
    val profiles: ImmutableList<Profile> = persistentListOf(),
    /** `null` tant que l'écran de départ n'est pas ouvert. */
    val openProfileId: Long? = null,
    /** Une écriture tourne : les boutons attendent. */
    val isWorking: Boolean = false,
    val failure: ProfileFailure? = null,
)

/** Les utilisateurs, dans les Réglages (derrière le contrôle des adultes). */
@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val repository: BoardRepository,
    private val photos: PhotoStore,
) : ViewModel() {
    private val isWorking = MutableStateFlow(false)
    private val failure = MutableStateFlow<ProfileFailure?>(null)

    val uiState: StateFlow<ProfilesUiState> = combine(
        repository.observeProfiles(),
        repository.openProfileId,
        isWorking,
        failure,
    ) { profiles, openProfileId, working, lastFailure ->
        ProfilesUiState(profiles.toImmutableList(), openProfileId, working, lastFailure)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProfilesUiState())

    /** À l'ouverture des Réglages : un ancien échec ne s'affiche plus. */
    fun onShown() {
        failure.value = null
    }

    fun onCreate(name: String) = work(ProfileFailure.NotCreated, profileId = null) { repository.createProfile(name) }

    fun onRename(profileId: Long, name: String) = work(ProfileFailure.NotRenamed, profileId) { repository.renameProfile(profileId, name) }

    /** Les photos de ses cases sont effacées après la suppression en base : jamais avant. */
    fun onDelete(profileId: Long) = work(ProfileFailure.NotDeleted, profileId) {
        repository.deleteProfile(profileId).forEach { photos.delete(it) }
    }

    fun onExitLockChanged(profileId: Long, isLocked: Boolean) =
        work(ProfileFailure.ExitLockNotSaved, profileId) { repository.setExitLocked(profileId, isLocked) }

    /** Toucher l'utilisateur déjà ouvert ne fait rien : la phrase de l'enfant ne se vide pas pour rien. */
    fun onSwitch(profileId: Long) {
        if (profileId == uiState.value.openProfileId) return
        work(ProfileFailure.NotSwitched, profileId) { repository.switchProfile(profileId) }
    }

    /**
     * Une action à la fois (une autre demandée pendant ce temps est ignorée : les boutons sont alors
     * grisés) ; un échec est journalisé avec l'id visé, jamais le nom, et affiché.
     */
    private fun work(onFailure: ProfileFailure, profileId: Long?, action: suspend () -> Unit) {
        if (isWorking.value) return
        isWorking.value = true
        failure.value = null
        viewModelScope.launch {
            try {
                action()
            } catch (exception: IOException) {
                report(onFailure, profileId, exception)
            } catch (exception: SQLiteException) {
                report(onFailure, profileId, exception)
            } catch (exception: CancellationException) {
                // Une IllegalStateException elle aussi : l'écran qui part n'est pas un échec.
                throw exception
            } catch (exception: IllegalStateException) {
                // Base fermée, utilisateur ouvert pas encore connu : un message, pas un plantage.
                report(onFailure, profileId, exception)
            } catch (exception: IllegalArgumentException) {
                // Nom refusé par le dépôt (le dialogue l'aurait dû empêcher).
                report(onFailure, profileId, exception)
            } finally {
                isWorking.value = false
            }
        }
    }

    private fun report(onFailure: ProfileFailure, profileId: Long?, exception: Exception) {
        Log.e(TAG, "Profile action failed ($onFailure, profile $profileId)", exception)
        failure.value = onFailure
    }
}
