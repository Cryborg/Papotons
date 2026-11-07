package com.wobblebot.papotons.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.io.IOException

/** DataStore en mémoire et synchrone : un vrai DataStore écrit hors du dispatcher de test. */
class FakePreferences(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)

    /** Levée à l'écriture, après la transformation (disque plein). */
    var writeFailure: IOException? = null

    /** Levée à la lecture (fichier inaccessible). */
    var readFailure: IOException? = null

    val current: Preferences get() = state.value

    override val data: Flow<Preferences> = flow {
        readFailure?.let { throw it }
        emitAll(state)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        readFailure?.let { throw it }
        val updated = transform(state.value)
        writeFailure?.let { throw it }
        state.value = updated
        return updated
    }
}
