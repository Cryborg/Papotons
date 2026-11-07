package com.wobblebot.papotons.data.caregiver

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wobblebot.papotons.data.profile.isValidProfileName
import com.wobblebot.papotons.domain.Caregiver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CaregiverRepository"
private const val READ_RETRY_DELAY_MS = 10_000L
private val CAREGIVERS = stringPreferencesKey("caregivers")

/** L'accompagnant visé a disparu entre la lecture et l'écriture. */
class CaregiverNotFoundException(caregiverId: Long) : IOException("Caregiver $caregiverId not found")

/**
 * Les accompagnants et leurs codes. Un fichier illisible (lecture ratée, texte abîmé) vaut « aucun
 * accompagnant » : le contrôle retombe sur le calcul, qui arrête encore un enfant, et un adulte garde
 * l'accès aux Réglages. Le premier ajout réécrit alors le fichier. Les noms suivent la règle des
 * noms d'utilisateur ; ni nom ni code ne vont au journal.
 */
@Singleton
class CaregiverRepository @Inject constructor(@CaregiversStore private val store: DataStore<Preferences>) {
    /** Où tourne PBKDF2, hors du thread principal ; remplacé dans les tests. */
    internal var hashDispatcher: CoroutineDispatcher = Dispatchers.Default

    fun observeCaregivers(): Flow<List<Caregiver>> = store.data
        .map { preferences -> read(preferences).map { it.summary() } }
        // Une lecture ratée ne termine pas le flux : terminé, il figerait « aucun accompagnant » et un
        // ajout, écrit pourtant, ne s'afficherait jamais (doublons au nouvel essai).
        .retryWhen { cause, attempt ->
            if (cause !is IOException) return@retryWhen false
            if (attempt == 0L) Log.e(TAG, "Caregivers could not be read, falling back to the parental question, retrying", cause)
            emit(emptyList())
            delay(READ_RETRY_DELAY_MS)
            true
        }

    suspend fun add(name: String, code: String) {
        require(isValidProfileName(name)) { "Invalid caregiver name" }
        val salt = CaregiverCode.newSalt()
        val hash = hash(code, salt)
        var addedId = 0L
        store.edit { preferences ->
            val caregivers = read(preferences)
            addedId = (caregivers.maxOfOrNull { it.id } ?: 0) + 1
            preferences[CAREGIVERS] = (caregivers + StoredCaregiver(addedId, name, hash, salt, codeResetAtMs = null)).toJson()
        }
        Log.i(TAG, "Caregiver $addedId added")
    }

    /** Nom et, si [code] n'est pas `null`, code : ensemble ou pas du tout. */
    suspend fun edit(caregiverId: Long, name: String, code: String?) {
        require(isValidProfileName(name)) { "Invalid caregiver name" }
        val salt = code?.let { CaregiverCode.newSalt() }
        val hash = code?.let { hash(it, checkNotNull(salt)) }
        update(caregiverId) {
            if (hash == null) {
                StoredCaregiver(it.id, name, it.codeHash, it.codeSalt, it.codeResetAtMs)
            } else {
                StoredCaregiver(it.id, name, hash, checkNotNull(salt), codeResetAtMs = null)
            }
        }
        if (code != null) Log.i(TAG, "Caregiver $caregiverId code changed (recovery: false)")
    }

    /** Par « Code oublié ? » : les Réglages le signalent jusqu'au prochain changement ordinaire ([edit]). */
    suspend fun recoverCode(caregiverId: Long, code: String, nowMs: Long = System.currentTimeMillis()) {
        val salt = CaregiverCode.newSalt()
        val hash = hash(code, salt)
        update(caregiverId) { StoredCaregiver(it.id, it.name, hash, salt, codeResetAtMs = nowMs) }
        Log.i(TAG, "Caregiver $caregiverId code changed (recovery: true)")
    }

    suspend fun delete(caregiverId: Long) {
        store.edit { preferences ->
            val caregivers = read(preferences)
            if (caregivers.none { it.id == caregiverId }) throw CaregiverNotFoundException(caregiverId)
            preferences[CAREGIVERS] = caregivers.filter { it.id != caregiverId }.toJson()
        }
        Log.i(TAG, "Caregiver $caregiverId deleted")
    }

    /** Faux pour un mauvais code, un accompagnant disparu ou un fichier illisible : le contrôle se referme. */
    suspend fun verify(caregiverId: Long, code: String): Boolean {
        val caregiver = try {
            read(store.data.first()).firstOrNull { it.id == caregiverId }
        } catch (exception: IOException) {
            Log.e(TAG, "Caregivers could not be read to check a code", exception)
            null
        } ?: return false
        val isRight = try {
            withContext(hashDispatcher) { CaregiverCode.matches(code, caregiver.codeSalt, caregiver.codeHash) }
        } catch (exception: IllegalArgumentException) {
            // Sel vide d'un fichier abîmé : un code faux, pas un plantage.
            Log.e(TAG, "Code of caregiver $caregiverId could not be checked (${exception.javaClass.simpleName})")
            false
        }
        if (!isRight) Log.i(TAG, "Wrong code for caregiver $caregiverId")
        return isRight
    }

    private suspend fun update(caregiverId: Long, change: (StoredCaregiver) -> StoredCaregiver) {
        store.edit { preferences ->
            val caregivers = read(preferences)
            if (caregivers.none { it.id == caregiverId }) throw CaregiverNotFoundException(caregiverId)
            preferences[CAREGIVERS] = caregivers.map { if (it.id == caregiverId) change(it) else it }.toJson()
        }
    }

    private suspend fun hash(code: String, salt: ByteArray) = withContext(hashDispatcher) { CaregiverCode.hash(code, salt) }

    private fun read(preferences: Preferences): List<StoredCaregiver> {
        val json = preferences[CAREGIVERS] ?: return emptyList()
        // Le nom de l'exception seulement : le message d'org.json recopie le texte lu (noms, hachés, sels).
        return try {
            parseCaregivers(json)
        } catch (exception: JSONException) {
            Log.e(TAG, "Caregivers unreadable (${exception.javaClass.simpleName}), treated as none")
            emptyList()
        } catch (exception: IllegalArgumentException) {
            Log.e(TAG, "Caregivers unreadable (${exception.javaClass.simpleName}), treated as none")
            emptyList()
        }
    }
}
