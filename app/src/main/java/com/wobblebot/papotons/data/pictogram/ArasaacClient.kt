package com.wobblebot.papotons.data.pictogram

import android.util.Log
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

private const val TAG = "ArasaacClient"
private const val SEARCH_URL = "https://api.arasaac.org/v1/pictograms/fr/search/"
private const val IMAGE_URL = "https://static.arasaac.org/pictograms/"
private const val IMAGE_SIZE_SUFFIX = "_300.png"

/** Le nom de l'appli, sans le modèle de l'appareil que le User-Agent par défaut donne. */
private const val USER_AGENT = "Papotons"
private const val TIMEOUT_MS = 10_000
private const val MAX_SEARCH_BYTES = 2 * 1024 * 1024
private const val MAX_RESULTS = 30
private const val READ_BUFFER_BYTES = 8 * 1024
private const val HEX_DIGITS = "0123456789ABCDEF"

/** Un picto trouvé : son identifiant ARASAAC et son premier mot-clé (description accessible). */
data class PictogramResult(val id: Long, val keyword: String)

/** Ce qui n'a pas abouti chez ARASAAC (réseau, réponse inattendue). */
open class ArasaacException(message: String, cause: Throwable? = null) : IOException(message, cause)

class ResponseTooLargeException(maxBytes: Int) : ArasaacException("Response larger than $maxBytes bytes")

/** La recherche rend plus que ce qu'on accepte de lire : le mot est trop vague. */
class TooManyResultsException(cause: Throwable) : ArasaacException("Too many results", cause)

/**
 * L'API publique d'ARASAAC, seul réseau de l'appli (invariant 1). Couture de test : l'écran de
 * recherche se teste sur la JVM avec un faux.
 */
interface ArasaacClient {
    /** Au plus [MAX_RESULTS] pictos, sans ceux marqués `sex` ou `violence` (appli pour enfants). */
    suspend fun search(keyword: String): List<PictogramResult>

    /** L'image PNG 300 px d'un picto. */
    suspend fun download(pictogramId: Long): ByteArray
}

/**
 * Seuls partent le mot cherché, l'adresse IP et le nom du logiciel (User-Agent). Un seul essai (l'adulte a « Réessayer »), 10 s par
 * opération (connexion, puis chaque lecture), aucune redirection suivie (on ne parle qu'aux hôtes
 * d'ARASAAC), corps plafonné. Une recherche annulée ferme sa connexion : la lecture bloquante
 * s'interrompt au lieu d'attendre son délai.
 */
class HttpArasaacClient @Inject constructor() : ArasaacClient {
    override suspend fun search(keyword: String): List<PictogramResult> {
        val body = try {
            get(searchUrl(keyword), MAX_SEARCH_BYTES, emptyOn404 = true) ?: return emptyList()
        } catch (exception: ResponseTooLargeException) {
            // Un mot trop court (« le ») : des milliers de résultats. L'adulte doit préciser.
            throw TooManyResultsException(exception)
        }
        return parseSearchResults(body.toString(Charsets.UTF_8))
    }

    override suspend fun download(pictogramId: Long): ByteArray =
        checkNotNull(get(imageUrl(pictogramId), MAX_PICTOGRAM_BYTES, emptyOn404 = false))

    private suspend fun get(url: String, maxBytes: Int, emptyOn404: Boolean): ByteArray? = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        // Un gardien sur un autre fil : annulé avec la recherche, il ferme la connexion et débloque
        // la lecture (`invokeOnCompletion` n'agirait qu'à la fin, donc après la lecture).
        // UNDISPATCHED : en place avant la connexion, même si l'annulation arrive tout de suite.
        val guard = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                connection.disconnect()
            }
        }
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.use { readCapped(it, maxBytes) }
                // Ni l'adresse ni le mot cherché dans les journaux ou les messages : un adulte peut y
                // avoir tapé un prénom.
                HttpURLConnection.HTTP_NOT_FOUND -> if (emptyOn404) null else throw ArasaacException("HTTP 404")
                else -> {
                    Log.w(TAG, "HTTP $status")
                    throw ArasaacException("HTTP $status")
                }
            }
        } finally {
            guard.cancel()
            connection.disconnect()
        }
    }

}

/** Tout le flux, ou un échec au-delà de [maxBytes] (une réponse démesurée n'entre jamais en mémoire). */
internal fun readCapped(input: InputStream, maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_BYTES)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > maxBytes) throw ResponseTooLargeException(maxBytes)
        output.write(buffer, 0, count)
    }
}

/** Un mot-clé cherchable : au moins une lettre ou un chiffre (« . » ou « .. » viseraient une autre route). */
fun isSearchable(keyword: String): Boolean = keyword.any(Char::isLetterOrDigit)

fun searchUrl(keyword: String): String = SEARCH_URL + encodePathSegment(keyword.trim())

fun imageUrl(pictogramId: Long): String = "$IMAGE_URL$pictogramId/$pictogramId$IMAGE_SIZE_SUFFIX"

/**
 * Encodage en pourcents d'un segment de chemin : tout sauf les caractères non réservés. Jamais
 * `URLEncoder` : son `+` reste littéral dans un chemin (« pomme+de+terre » rend 2063 résultats au
 * lieu de 39).
 */
internal fun encodePathSegment(text: String): String = buildString {
    for (byte in text.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~") {
            append(char)
        } else {
            append('%').append(HEX_DIGITS[code shr 4]).append(HEX_DIGITS[code and 0x0F])
        }
    }
}

/** La réponse de recherche d'ARASAAC ; une réponse illisible est un échec, jamais une liste vide. */
fun parseSearchResults(json: String): List<PictogramResult> = try {
    val results = JSONArray(json)
    val ids = HashSet<Long>()
    var unflaggedCount = 0
    buildList {
        for (index in 0 until results.length()) {
            if (size >= MAX_RESULTS) break
            val pictogram = results.getJSONObject(index)
            // Le filtre enfant repose sur ces deux drapeaux : absents, nuls ou d'un autre type (API
            // changée), on écarte ce résultat plutôt que de tout laisser passer ou tout refuser.
            val isSexual = pictogram.opt("sex") as? Boolean
            val isViolent = pictogram.opt("violence") as? Boolean
            if (isSexual == null || isViolent == null) {
                unflaggedCount++
                continue
            }
            if (isSexual || isViolent) continue
            val id = pictogram.getLong("_id")
            // Une clé en double ferait planter la grille des résultats.
            if (!ids.add(id)) continue
            // Pas `optString` : sur Android, un mot-clé nul y devient le texte « null », lu par TalkBack.
            val keyword = pictogram.optJSONArray("keywords")?.optJSONObject(0)?.opt("keyword") as? String ?: ""
            add(PictogramResult(id, keyword))
        }
        if (unflaggedCount > 0) Log.w(TAG, "$unflaggedCount results without sex or violence flag dropped")
    }
} catch (exception: JSONException) {
    throw ArasaacException("Unreadable search response", exception)
}
