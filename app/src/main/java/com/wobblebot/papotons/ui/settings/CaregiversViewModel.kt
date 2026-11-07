package com.wobblebot.papotons.ui.settings

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wobblebot.papotons.domain.Caregiver
import com.wobblebot.papotons.data.caregiver.CaregiverRepository
import com.wobblebot.papotons.ui.board.STOP_TIMEOUT_MS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import javax.inject.Inject

private const val TAG = "CaregiversViewModel"

/** Une action sur les accompagnants qui n'a rien changé. */
enum class CaregiverFailure { NotSaved, NotDeleted }

@Immutable
data class CaregiversUiState(
    /** `null` tant que le fichier n'est pas lu : le contrôle attend, plutôt que de montrer le calcul à tort. */
    val caregivers: ImmutableList<Caregiver>? = null,
    val isWorking: Boolean = false,
    val failure: CaregiverFailure? = null,
)

/** Les accompagnants : le contrôle qui ouvre les Réglages, et leur section dans les Réglages. */
@HiltViewModel
class CaregiversViewModel @Inject constructor(private val repository: CaregiverRepository) : ViewModel() {
    private val work = SettingsWork<CaregiverFailure>(viewModelScope, TAG)

    val uiState: StateFlow<CaregiversUiState> = combine(repository.observeCaregivers(), work.isWorking, work.failure) { caregivers, working, lastFailure ->
        CaregiversUiState(caregivers.toImmutableList(), working, lastFailure)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CaregiversUiState())

    /** À l'ouverture des Réglages : un ancien échec ne s'affiche plus. */
    fun onShown() {
        work.clearFailure()
    }

    /** [code] `null` : inchangé. */
    fun onSave(caregiverId: Long?, name: String, code: String?) = work.run(CaregiverFailure.NotSaved, caregiverId) {
        if (caregiverId == null) {
            repository.add(name, checkNotNull(code) { "A new caregiver needs a code" })
        } else {
            repository.edit(caregiverId, name, code)
        }
    }

    fun onDelete(caregiverId: Long) = work.run(CaregiverFailure.NotDeleted, caregiverId) { repository.delete(caregiverId) }

    suspend fun verify(caregiverId: Long, code: String): Boolean = repository.verify(caregiverId, code)

    /** Après la multiplication de « Code oublié ? ». Faux si le code n'a pas pu s'écrire. */
    suspend fun recover(caregiverId: Long, code: String): Boolean = try {
        repository.recoverCode(caregiverId, code)
        true
    } catch (exception: IOException) {
        Log.e(TAG, "Recovered code of caregiver $caregiverId could not be saved", exception)
        false
    }

}
