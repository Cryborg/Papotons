package com.wobblebot.papotons.data.caregiver

import com.wobblebot.papotons.domain.Caregiver
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

private const val KEY_ID = "id"
private const val KEY_NAME = "name"
private const val KEY_CODE_HASH = "codeHash"
private const val KEY_CODE_SALT = "codeSalt"
private const val KEY_CODE_RESET_AT_MS = "codeResetAtMs"

/** Un accompagnant tel qu'il est gardé : son code, seulement haché. */
class StoredCaregiver(
    val id: Long,
    val name: String,
    val codeHash: ByteArray,
    val codeSalt: ByteArray,
    /** Quand (ms) « Code oublié ? » a changé son code ; `null` après un changement ordinaire. */
    val codeResetAtMs: Long?,
) {
    fun summary() = Caregiver(id, name, codeResetAtMs)
}

// java.util.Base64 et non android.util.Base64 : le second rend `null` dans les tests JVM.
private val encoder = Base64.getEncoder()
private val decoder = Base64.getDecoder()

fun List<StoredCaregiver>.toJson(): String = JSONArray(
    map { caregiver ->
        JSONObject()
            .put(KEY_ID, caregiver.id)
            .put(KEY_NAME, caregiver.name)
            .put(KEY_CODE_HASH, encoder.encodeToString(caregiver.codeHash))
            .put(KEY_CODE_SALT, encoder.encodeToString(caregiver.codeSalt))
            .also { json -> caregiver.codeResetAtMs?.let { json.put(KEY_CODE_RESET_AT_MS, it) } }
    }
).toString()

/** Lève [JSONException] (ou [IllegalArgumentException] pour un Base64 abîmé) sur un texte illisible. */
fun parseCaregivers(json: String): List<StoredCaregiver> {
    val array = JSONArray(json)
    return (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        StoredCaregiver(
            id = item.getLong(KEY_ID),
            name = item.getString(KEY_NAME),
            codeHash = decoder.decode(item.getString(KEY_CODE_HASH)),
            codeSalt = decoder.decode(item.getString(KEY_CODE_SALT)),
            codeResetAtMs = if (item.has(KEY_CODE_RESET_AT_MS)) item.getLong(KEY_CODE_RESET_AT_MS) else null,
        )
    }
}
