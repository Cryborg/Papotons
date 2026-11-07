package com.wobblebot.papotons.data.board

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.wobblebot.papotons.data.CORRUPTED_DATABASES_DIRECTORY
import com.wobblebot.papotons.domain.Board
import com.wobblebot.papotons.domain.HOME_BOARD_ID
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BoardRepository"
private const val PREFERENCES_RETRY_DELAY_MS = 10_000L

/**
 * Posé une fois le vocabulaire de base inséré. Pas un réglage : ne jamais l'effacer, sinon une base
 * perdue repasserait pour un premier lancement, sans alerte.
 */
private val CORE_VOCABULARY_SEEDED = booleanPreferencesKey("core_vocabulary_seeded")

/** Quand (ms) la base a été trouvée vide alors qu'elle avait déjà été remplie. Retirée par l'adulte. */
private val BOARDS_LOST_AT = longPreferencesKey("boards_lost_at")

@Singleton
class BoardRepository @Inject constructor(
    private val boardDao: BoardDao,
    private val preferences: DataStore<Preferences>,
    @ApplicationContext private val context: Context,
) {
    /** Une perte constatée dont l'écriture a échoué : connue de ce processus seulement. */
    private val unsavedLostBoards = MutableStateFlow(false)

    /**
     * Insère le vocabulaire de base dans une base vide, puis rend l'id de la grille à ouvrir. Une
     * base vide qui avait déjà été remplie (corrompue, effacée) reçoit aussi le vocabulaire de base,
     * pour qu'on puisse parler, et lève l'alerte de [observeLostBoards]. Une base qui a perdu son
     * accueil ouvre sa plus petite grille : ni plantage en boucle ni chargement sans fin, et rien
     * n'est effacé (invariant 4).
     */
    suspend fun ensureHomeBoard(): Long {
        // L'alerte AVANT la réinsertion : un arrêt entre les deux ne doit pas la faire oublier.
        // `countBoards` d'abord : première requête, c'est elle qui ouvre la base, et une base
        // corrompue est copiée à ce moment-là, avant qu'on ne cherche les copies.
        raiseLostBoardsIfNeeded(isDatabaseEmpty = boardDao.countBoards() == 0)
        val seed = CoreVocabulary.build(context::getString)
        if (boardDao.seedIfEmpty(seed.boards, seed.cells)) {
            Log.i(TAG, "Core vocabulary inserted: ${seed.boards.size} boards, ${seed.cells.size} cells")
        }
        // Le marqueur APRÈS : un arrêt entre les deux laisse une base remplie sans marqueur, que le
        // lancement suivant marque sans alerte.
        markSeeded()
        if (boardDao.boardExists(HOME_BOARD_ID)) return HOME_BOARD_ID
        // Jamais nul : la base n'est pas vide après seedIfEmpty.
        val fallbackBoardId = checkNotNull(boardDao.smallestBoardId()) { "No board after seeding" }
        Log.e(TAG, "Home board $HOME_BOARD_ID missing, opening board $fallbackBoardId instead")
        return fallbackBoardId
    }

    /**
     * Vrai tant qu'une perte des grilles constatée par [ensureHomeBoard] (au lancement, ou quand
     * l'accueil devient introuvable) n'a pas été acquittée, même si son écriture a échoué.
     */
    fun observeLostBoards(): Flow<Boolean> = preferences.data
        .map { it[BOARDS_LOST_AT] != null }
        .combine(unsavedLostBoards) { isSaved, isUnsaved -> isSaved || isUnsaved }
        // Une lecture ratée ne termine pas le flux : terminé, il figerait l'alerte dans le `combine`
        // de l'écran (cachée pour de bon, ou jamais montrée pour une perte constatée ensuite).
        .retryWhen { cause, attempt ->
            if (cause !is IOException) return@retryWhen false
            // Une fois : la panne peut durer, et l'essai repart toutes les [PREFERENCES_RETRY_DELAY_MS].
            if (attempt == 0L) Log.e(TAG, "Preferences could not be read, retrying", cause)
            delay(PREFERENCES_RETRY_DELAY_MS)
            true
        }
        .distinctUntilChanged()

    suspend fun acknowledgeLostBoards() {
        unsavedLostBoards.value = false
        try {
            preferences.edit { it.remove(BOARDS_LOST_AT) }
            Log.i(TAG, "Lost boards notice acknowledged")
        } catch (exception: IOException) {
            Log.e(TAG, "Lost boards notice could not be cleared", exception)
        }
    }

    /**
     * Perte constatée quand la base est vide alors que le vocabulaire avait déjà été inséré, ou
     * quand une base corrompue a été mise de côté sans que les préférences s'en souviennent
     * (préférences abîmées avec la base, ou remises à zéro avant que l'adulte n'ait vu l'alerte).
     * Le marqueur n'est qu'une alarme : une lecture ou une écriture ratée est journalisée, sans
     * alerte (une fausse alerte sur une installation neuve inquiéterait pour rien), et n'empêche
     * jamais d'ouvrir une grille.
     */
    private suspend fun raiseLostBoardsIfNeeded(isDatabaseEmpty: Boolean) {
        val hasCorruptedCopies = hasCorruptedCopies()
        var isLossFound = false
        try {
            preferences.edit { settings ->
                val wasSeeded = settings[CORE_VOCABULARY_SEEDED] == true
                isLossFound = (isDatabaseEmpty && wasSeeded) || (!wasSeeded && hasCorruptedCopies)
                if (isLossFound) {
                    Log.e(TAG, "Boards lost (database empty: $isDatabaseEmpty, corrupted copies: $hasCorruptedCopies)")
                    settings[BOARDS_LOST_AT] = System.currentTimeMillis()
                }
            }
        } catch (exception: IOException) {
            // Perte vue mais pas écrite (stockage qui flanche) : la base réinsérée la cacherait aux
            // lancements suivants, elle s'affiche au moins jusqu'à la fin du processus.
            // Lecture ratée aussi : une base vide à côté d'une copie prouve la perte sans les préférences.
            if (isLossFound || (isDatabaseEmpty && hasCorruptedCopies)) unsavedLostBoards.value = true
            Log.e(TAG, "Lost boards check could not be saved (loss found: $isLossFound)", exception)
        }
    }

    /** Sur le thread appelant : un dossier de quelques entrées, lu une fois par ouverture. */
    private fun hasCorruptedCopies(): Boolean {
        val names = File(context.filesDir, CORRUPTED_DATABASES_DIRECTORY).list()
        // `null` : dossier absent (le cas courant) ou illisible, impossible à distinguer ici.
        return names != null && names.isNotEmpty()
    }

    private suspend fun markSeeded() {
        try {
            preferences.edit { it[CORE_VOCABULARY_SEEDED] = true }
        } catch (exception: IOException) {
            Log.e(TAG, "Core vocabulary marker could not be written", exception)
        }
    }

    /** `null` quand la grille n'existe pas. */
    fun observeBoard(boardId: Long): Flow<Board?> = boardDao.observeBoard(boardId).map { it?.toDomain() }
}
