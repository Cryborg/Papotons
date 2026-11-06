package com.wobblebot.papotons.data.board

import android.content.Context
import android.util.Log
import com.wobblebot.papotons.domain.Board
import com.wobblebot.papotons.domain.HOME_BOARD_ID
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BoardRepository"

@Singleton
class BoardRepository @Inject constructor(
    private val boardDao: BoardDao,
    @ApplicationContext private val context: Context,
) {
    /**
     * Insère le vocabulaire de base au premier lancement, puis rend l'id de la grille à
     * ouvrir. Une base qui a perdu son accueil ouvre sa plus petite grille : ni plantage en boucle
     * ni chargement sans fin, et rien n'est effacé (invariant 4).
     */
    suspend fun ensureHomeBoard(): Long {
        val seed = CoreVocabulary.build(context::getString)
        if (boardDao.seedIfEmpty(seed.boards, seed.cells)) {
            Log.i(TAG, "Core vocabulary inserted: ${seed.boards.size} boards, ${seed.cells.size} cells")
        }
        if (boardDao.boardExists(HOME_BOARD_ID)) return HOME_BOARD_ID
        // Jamais nul : la base n'est pas vide après seedIfEmpty.
        val fallbackBoardId = checkNotNull(boardDao.smallestBoardId()) { "No board after seeding" }
        Log.e(TAG, "Home board $HOME_BOARD_ID missing, opening board $fallbackBoardId instead")
        return fallbackBoardId
    }

    /** `null` quand la grille n'existe pas. */
    fun observeBoard(boardId: Long): Flow<Board?> = boardDao.observeBoard(boardId).map { it?.toDomain() }
}
