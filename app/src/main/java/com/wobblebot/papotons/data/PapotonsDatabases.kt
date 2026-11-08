package com.wobblebot.papotons.data

import android.content.Context
import android.util.Log
import androidx.room.RoomDatabase
import com.wobblebot.papotons.data.board.BoardDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "PapotonsDatabases"

/** Une base perdue est copiée puis supprimée : quelques centaines de millisecondes, même grosse. */
const val DATABASE_REPLACEMENT_TIMEOUT_MS = 10_000L

/**
 * Une instance de Room ouverte par [PapotonsDatabases], et ce que sa fabrique sait d'une
 * corruption vue en cours d'usage ([CorruptionCopyingOpenHelperFactory]).
 *
 * @property isLost les requêtes de cette instance vont échouer (sa connexion se ferme).
 * @property isReplaced la copie de la base perdue est tentée, ses fichiers supprimés : une instance
 *   neuve peut s'ouvrir.
 */
class OpenedDatabase(
    val boardDao: BoardDao,
    val isLost: () -> Boolean,
    val isReplaced: () -> Boolean,
)

/**
 * La base de l'appli, rouverte neuve après une corruption vue en cours d'usage : Room garde à vie
 * l'unique connexion d'une instance, et `onCorruption` l'a fermée. L'instance perdue est abandonnée
 * sans être fermée : sa fermeture annulerait en `CancellationException` ce qui y est encore en vol
 * (un écran figé plutôt qu'une erreur), alors que sa connexion est déjà fermée et que son suivi des
 * invalidations s'arrête de lui-même sur une base fermée.
 *
 * @param open ouvre une instance : `onReplaced` est à appeler une fois sa base remplacée sur le
 *   disque, `isReplacement` dit qu'elle prend la relève d'une instance perdue.
 */
class PapotonsDatabases(private val open: (onReplaced: () -> Unit, isReplacement: Boolean) -> OpenedDatabase) {
    private val replacementCount = MutableStateFlow(0)

    /** Monte à chaque base perdue en cours d'usage puis remplacée par une base neuve, vide. */
    val replacements: StateFlow<Int> = replacementCount.asStateFlow()

    private var current = open(::onReplaced, false)

    val boardDao: BoardDao get() = current().boardDao

    private fun onReplaced() {
        replacementCount.update { it + 1 }
    }

    /** Synchronisé : deux fils (l'export tourne hors du fil principal) ouvriraient deux instances neuves. */
    private fun current(): OpenedDatabase = synchronized(this) {
        if (current.isReplaced()) {
            Log.w(TAG, "Opening a new database instance after a corruption")
            current = open(::onReplaced, true)
        }
        current
    }

    /**
     * L'instance tenue est perdue (même remplacée sur le disque, tant que la neuve ne s'est pas
     * ouverte), ou une base a été remplacée depuis que [replacements] valait [replacementsBefore] :
     * un échec vu entre-temps vient de la perte, pas de l'appelant.
     */
    fun isLostSince(replacementsBefore: Int): Boolean =
        replacements.value != replacementsBefore || synchronized(this) { current.isLost() }

    /** Rend une fois la base perdue remplacée (tout de suite si c'est déjà fait depuis [replacementsBefore]). */
    suspend fun awaitReplacement(replacementsBefore: Int) {
        replacements.first { it != replacementsBefore }
    }

    /**
     * [query] sur l'instance courante, puis sur chaque instance neuve. L'erreur d'une instance
     * perdue, quelle qu'elle soit (la corruption pour la requête qui l'a vue, une connexion fermée
     * pour ses voisines), attend le remplacement : le compte qui monte annule cette attente et
     * ouvre le flux de l'instance neuve. Jamais remplacée (fichier resté là) : l'erreur remonte,
     * plutôt qu'un écran figé sans un mot.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> observe(query: (BoardDao) -> Flow<T>): Flow<T> = replacements.flatMapLatest { replacementsBefore ->
        val database = current()
        query(database.boardDao).catch { cause ->
            if (!database.isLost()) throw cause
            Log.w(TAG, "Query of a lost database ended, waiting for the new database", cause)
            // Remplacée : ce flux se termine, `flatMapLatest` ouvre celui du nouveau compte.
            if (withTimeoutOrNull(DATABASE_REPLACEMENT_TIMEOUT_MS) { awaitReplacement(replacementsBefore) } != null) return@catch
            Log.e(TAG, "Lost database never replaced")
            throw cause
        }
    }
}

/**
 * Celles de l'appli : une fabrique par instance, qui copie la base corrompue avant de la laisser
 * partir. [onBuilt] voit chaque instance (les tests instrumentés les ferment à la fin).
 *
 * Une instance de relève tourne sans WAL jusqu'au relancement : la dernière connexion de l'instance
 * perdue, fermée plus tard, supprime en partant le `-wal` et le `-shm` de son chemin PAR LEUR NOM,
 * qui seraient alors ceux de la base neuve.
 */
fun PapotonsDatabases(context: Context, name: String, onBuilt: (PapotonsDatabase) -> Unit = {}): PapotonsDatabases =
    PapotonsDatabases { onReplaced, isReplacement ->
        val factory = CorruptionCopyingOpenHelperFactory(context, onReplaced)
        val journalMode = if (isReplacement) RoomDatabase.JournalMode.TRUNCATE else RoomDatabase.JournalMode.AUTOMATIC
        val database = buildDatabase(context, name, factory, journalMode).also(onBuilt)
        OpenedDatabase(database.boardDao(), factory::isLost, factory::isReplaced)
    }
