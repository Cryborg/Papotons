package com.wobblebot.papotons.ui.board

import androidx.compose.ui.graphics.ImageBitmap
import com.wobblebot.papotons.data.pictogram.ArasaacClient
import com.wobblebot.papotons.data.pictogram.ArasaacException
import com.wobblebot.papotons.data.pictogram.PictogramResult
import com.wobblebot.papotons.data.pictogram.PictogramStore
import io.mockk.mockk
import com.wobblebot.papotons.data.pictogram.TooManyResultsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class FakeArasaacClient : ArasaacClient {
    /** Les résultats selon le mot cherché : deux recherches se distinguent. */
    var resultsFor: (String) -> List<PictogramResult> = { listOf(PictogramResult(10, "pomme"), PictogramResult(20, "poire")) }
    var isOffline = false

    /** Les vignettes qui échouent. */
    val failingDownloads = mutableSetOf<Long>()

    /** Retient la recherche de ce mot tant qu'il n'est pas complété (réseau lent). */
    val searchGates = mutableMapOf<String, CompletableDeferred<Unit>>()
    val searches = mutableListOf<String>()
    val downloads = mutableListOf<Long>()

    /** Les mots dont la recherche échoue (réseau). */
    val failingSearches = mutableSetOf<String>()

    /** Les mots trop vagues (réponse au-delà du plafond). */
    val tooVagueSearches = mutableSetOf<String>()
    val cancelledSearches = mutableListOf<String>()

    override suspend fun search(keyword: String): List<PictogramResult> {
        searches += keyword
        if (keyword in failingSearches) {
            // Comme une lecture bloquante : l'annulation ne l'interrompt pas, le délai expire après coup.
            withContext(NonCancellable) { searchGates[keyword]?.await() }
            throw ArasaacException("timeout")
        }
        try {
            searchGates[keyword]?.await()
        } catch (exception: CancellationException) {
            cancelledSearches += keyword
            throw exception
        }
        if (keyword in tooVagueSearches) throw TooManyResultsException(ArasaacException("too large"))
        if (isOffline) throw ArasaacException("offline")
        return resultsFor(keyword)
    }

    override suspend fun download(pictogramId: Long): ByteArray {
        downloads += pictogramId
        if (isOffline || pictogramId in failingDownloads) throw ArasaacException("offline")
        return byteArrayOf(pictogramId.toByte())
    }
}

class FakePictogramStore : PictogramStore {
    val stored = mutableMapOf<Long, ByteArray>()
    var canStore = true

    /** Retient l'écriture tant qu'il n'est pas complété (fichier lent à synchroniser). */
    var storeGate: CompletableDeferred<Unit>? = null

    override suspend fun decode(bytes: ByteArray): ImageBitmap = mockk(relaxed = true)

    override suspend fun store(pictogramId: Long, bytes: ByteArray): Boolean {
        storeGate?.await()
        if (canStore) stored[pictogramId] = bytes
        return canStore
    }
}
