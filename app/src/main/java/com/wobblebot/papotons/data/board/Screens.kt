package com.wobblebot.papotons.data.board

import android.database.sqlite.SQLiteException
import com.wobblebot.papotons.data.backup.MAX_BOARDS
import com.wobblebot.papotons.data.profile.ProfileEntity
import com.wobblebot.papotons.domain.HOME_BOARD_ID

/**
 * Écrans d'un utilisateur, au plus : autant d'onglets que l'enfant peut encore parcourir. Une limite
 * d'interface : la sauvegarde ne compte pas les écrans, seulement les grilles (`MAX_BOARDS`).
 */
const val MAX_SCREENS = 20

/**
 * Le plus grand rang d'écran que la sauvegarde accepte : un écran est une grille, il ne peut pas y en
 * avoir davantage. Un écran créé après le plus grand rang en base doit rester en dessous, sinon l'export
 * serait refusé.
 */
const val MAX_SCREEN_POSITION = MAX_BOARDS

/** Le rang du premier écran que reçoivent la graine, un nouvel utilisateur et la migration 4 → 5. */
const val FIRST_SCREEN_POSITION = 0

/** La grille visée n'est pas (ou plus) un écran de cet utilisateur : un id périmé ne touche à rien. */
class ScreenNotFoundException(boardId: Long) : SQLiteException("Board $boardId is not a screen of this profile")

/** Supprimer le dernier écran d'un utilisateur ne lui laisserait rien à ouvrir : refusé. */
class LastScreenException(boardId: Long) : SQLiteException("Screen $boardId is the last one of its profile")

/** Une case ouvre encore cet écran (données abîmées) : le supprimer échouerait à moitié, rien n'est fait. */
class ScreenStillLinkedException(boardId: Long) : SQLiteException("Screen $boardId is still opened by a folder")

/** Déjà [MAX_SCREENS] écrans, trop de grilles pour la sauvegarde, ou plus de rang libre sous [MAX_SCREEN_POSITION]. */
class TooManyScreensException : SQLiteException("Too many screens")

/** Ce que la règle du premier écran lit d'une grille. */
data class BoardOrigin(val id: Long, val coreBoardId: Long?)

/**
 * Le premier écran d'un utilisateur d'avant les écrans, parmi ses [boards] : son écran de départ
 * choisi ([startBoardId]) s'il est à lui et qu'aucune case ne l'ouvre, sinon sa copie de l'accueil
 * standard qu'aucune case n'ouvre, sinon sa plus petite grille qu'aucune case n'ouvre, sinon sa plus
 * petite grille ; `null` sans grille. Même règle pour la migration 4 → 5 et pour une sauvegarde aux
 * formats 1 et 2.
 */
fun legacyFirstScreenOf(startBoardId: Long?, boards: List<BoardOrigin>, linkedBoardIds: Set<Long>): Long? {
    val unlinked = boards.filter { it.id !in linkedBoardIds }
    return unlinked.firstOrNull { it.id == startBoardId }?.id
        ?: unlinked.filter { it.coreBoardId == HOME_BOARD_ID }.minOfOrNull { it.id }
        ?: unlinked.minOfOrNull { it.id }
        ?: boards.minOfOrNull { it.id }
}

/** [boards] avec le premier écran de chaque utilisateur au rang [FIRST_SCREEN_POSITION], selon [legacyFirstScreenOf]. */
fun withFirstScreens(profiles: List<ProfileEntity>, boards: List<BoardEntity>, cells: List<CellEntity>): List<BoardEntity> {
    val linkedBoardIds = cells.mapNotNullTo(HashSet()) { it.linkedBoardId }
    val boardsByProfile = boards.groupBy { it.profileId }
    val firstScreens = profiles.mapNotNullTo(HashSet()) { profile ->
        legacyFirstScreenOf(profile.startBoardId, boardsByProfile[profile.id].orEmpty().map { BoardOrigin(it.id, it.coreBoardId) }, linkedBoardIds)
    }
    return boards.map { board -> board.copy(screenPosition = if (board.id in firstScreens) FIRST_SCREEN_POSITION else null) }
}
