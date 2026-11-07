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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val isWorking = MutableStateFlow(false)
    private val failure = MutableStateFlow<CaregiverFailure?>(null)

    val uiState: StateFlow<CaregiversUiState> = combine(repository.observeCaregivers(), isWorking, failure) { caregivers, working, lastFailure ->
        CaregiversUiState(caregivers.toImmutableList(), working, lastFailure)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CaregiversUiState())

    /** À l'ouverture des Réglages : un ancien échec ne s'affiche plus. */
    fun onShown() {
        failure.value = null
    }

    /** [code] `null` : inchangé. */
    fun onSave(caregiverId: Long?, name: String, code: String?) = work(CaregiverFailure.NotSaved, caregiverId) {
        if (caregiverId == null) {
            repository.add(name, checkNotNull(code) { "A new caregiver needs a code" })
        } else {
            repository.edit(caregiverId, name, code)
        }
    }

    fun onDelete(caregiverId: Long) = work(CaregiverFailure.NotDeleted, caregiverId) { repository.delete(caregiverId) }

    suspend fun verify(caregiverId: Long, code: String): Boolean = repository.verify(caregiverId, code)

    /** Après la multiplication de « Code oublié ? ». Faux si le code n'a pas pu s'écrire. */
    suspend fun recover(caregiverId: Long, code: String): Boolean = try {
        repository.recoverCode(caregiverId, code)
        true
    } catch (exception: IOException) {
        Log.e(TAG, "Recovered code of caregiver $caregiverId could not be saved", exception)
        false
    }

    /** Une action à la fois ; un échec est journalisé avec l'id visé, jamais le nom ni le code, et affiché. */
    private fun work(onFailure: CaregiverFailure, caregiverId: Long?, action: suspend () -> Unit) {
        if (isWorking.value) return
        isWorking.value = true
        failure.value = null
        viewModelScope.launch {
            try {
                action()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IOException) {
                report(onFailure, caregiverId, exception)
            } catch (exception: IllegalArgumentException) {
                report(onFailure, caregiverId, exception)
            } catch (exception: IllegalStateException) {
                report(onFailure, caregiverId, exception)
            } finally {
                isWorking.value = false
            }
        }
    }

    private fun report(onFailure: CaregiverFailure, caregiverId: Long?, exception: Exception) {
        Log.e(TAG, "Caregiver action failed ($onFailure, caregiver $caregiverId)", exception)
        failure.value = onFailure
    }
}
