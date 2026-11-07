package com.wobblebot.papotons.data.conjugation

import android.content.Context
import android.util.Log
import com.wobblebot.papotons.domain.ConjugationTable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Conjugations"
private const val ASSET = "conjugations.tsv"

/** La table de conjugaison embarquée. Une interface pour que les tests la remplacent. */
interface Conjugations {
    suspend fun table(): ConjugationTable
}

/**
 * Lue une fois dans les assets. Illisible, elle est vide : l'appui long parle alors comme un appui
 * simple, et l'erreur reste dans le journal (le test de la vraie table l'attrape avant toute livraison).
 */
@Singleton
class AssetConjugations @Inject constructor(@ApplicationContext private val context: Context) : Conjugations {
    private val mutex = Mutex()
    private var loaded: ConjugationTable? = null

    override suspend fun table(): ConjugationTable = mutex.withLock {
        loaded ?: withContext(Dispatchers.IO) { read() }.also { loaded = it }
    }

    private fun read(): ConjugationTable {
        val text = try {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        } catch (exception: IOException) {
            Log.e(TAG, "Cannot read $ASSET", exception)
            return ConjugationTable.EMPTY
        }
        return ConjugationTable.parse(text).also { table ->
            if (table.malformedLines.isNotEmpty()) Log.e(TAG, "Malformed lines in $ASSET: ${table.malformedLines}")
        }
    }
}
