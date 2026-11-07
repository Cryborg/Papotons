package com.wobblebot.papotons.ui.settings

import android.database.sqlite.SQLiteException
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Les écritures d'une page des Réglages : une à la fois (une autre demandée pendant ce temps est
 * ignorée, les boutons sont alors grisés), et la dernière qui a échoué. Un échec est journalisé sous
 * [tag] avec l'id visé, jamais un nom ni un code, et affiché par la page.
 */
class SettingsWork<F : Any>(private val scope: CoroutineScope, private val tag: String) {
    private val working = MutableStateFlow(false)
    private val lastFailure = MutableStateFlow<F?>(null)

    val isWorking: StateFlow<Boolean> = working.asStateFlow()
    val failure: StateFlow<F?> = lastFailure.asStateFlow()

    /** À l'ouverture des Réglages : un ancien échec ne s'affiche plus. */
    fun clearFailure() {
        lastFailure.value = null
    }

    fun run(onFailure: F, targetId: Long?, action: suspend () -> Unit) {
        if (working.value) return
        working.value = true
        lastFailure.value = null
        scope.launch {
            try {
                action()
            } catch (exception: CancellationException) {
                // Une IllegalStateException elle aussi : la page qui part n'est pas un échec.
                throw exception
            } catch (exception: IOException) {
                report(onFailure, targetId, exception)
            } catch (exception: SQLiteException) {
                report(onFailure, targetId, exception)
            } catch (exception: IllegalStateException) {
                // Base fermée, utilisateur ouvert pas encore connu : un message, pas un plantage.
                report(onFailure, targetId, exception)
            } catch (exception: IllegalArgumentException) {
                // Nom refusé par le dépôt (la modale aurait dû l'empêcher).
                report(onFailure, targetId, exception)
            } finally {
                working.value = false
            }
        }
    }

    private fun report(onFailure: F, targetId: Long?, exception: Exception) {
        Log.e(tag, "Settings action failed ($onFailure, target $targetId)", exception)
        lastFailure.value = onFailure
    }
}
