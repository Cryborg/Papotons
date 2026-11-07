package com.wobblebot.papotons.ui.settings

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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

private const val TAG = "ProfilesViewModel"

/** Une action sur les utilisateurs qui n'a rien changé. */
enum class ProfileFailure { NotCreated, NotRenamed, NotDeleted, NotSwitched, ExitLockNotSaved }

@Immutable
data class ProfilesUiState(
    val profiles: ImmutableList<Profile> = persistentListOf(),
    /** `null` tant que le premier écran n'est pas ouvert. */
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
    private val work = SettingsWork<ProfileFailure>(viewModelScope, TAG)

    val uiState: StateFlow<ProfilesUiState> = combine(
        repository.observeProfiles(),
        repository.openProfileId,
        work.isWorking,
        work.failure,
    ) { profiles, openProfileId, working, lastFailure ->
        ProfilesUiState(profiles.toImmutableList(), openProfileId, working, lastFailure)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProfilesUiState())

    /** À l'ouverture des Réglages : un ancien échec ne s'affiche plus. */
    fun onShown() {
        work.clearFailure()
    }

    fun onCreate(name: String) = work.run(ProfileFailure.NotCreated, targetId = null) { repository.createProfile(name) }

    fun onRename(profileId: Long, name: String) = work.run(ProfileFailure.NotRenamed, profileId) { repository.renameProfile(profileId, name) }

    /** Les photos de ses cases sont effacées après la suppression en base : jamais avant. */
    fun onDelete(profileId: Long) = work.run(ProfileFailure.NotDeleted, profileId) {
        repository.deleteProfile(profileId).forEach { photos.delete(it) }
    }

    fun onExitLockChanged(profileId: Long, isLocked: Boolean) =
        work.run(ProfileFailure.ExitLockNotSaved, profileId) { repository.setExitLocked(profileId, isLocked) }

    /** Toucher l'utilisateur déjà ouvert ne fait rien : la phrase de l'enfant ne se vide pas pour rien. */
    fun onSwitch(profileId: Long) {
        if (profileId == uiState.value.openProfileId) return
        work.run(ProfileFailure.NotSwitched, profileId) { repository.switchProfile(profileId) }
    }

}
