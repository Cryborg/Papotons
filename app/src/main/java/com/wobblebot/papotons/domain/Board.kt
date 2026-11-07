package com.wobblebot.papotons.domain

import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** La grille ouverte au lancement et par le bouton « Accueil ». */
const val HOME_BOARD_ID = 1L

private const val TAG = "Board"

/**
 * Une case, posée à une position fixe de sa grille. [linkedBoardId] non nul en fait un dossier :
 * elle ouvre cette grille au lieu de parler.
 */
@Immutable
data class Cell(
    val id: Long,
    val row: Int,
    val column: Int,
    val label: String,
    val linkedBoardId: Long?,
    /** Identifiant ARASAAC du picto, `null` pour une case en texte seul. */
    val pictogramId: Long? = null,
    val category: WordCategory? = null,
)

@Immutable
data class Board(
    val id: Long,
    val name: String,
    val rowCount: Int,
    val columnCount: Int,
    val cells: ImmutableList<Cell>,
)

/**
 * Range les cases dans une matrice [rowCount] × [columnCount] d'après leur position stockée,
 * jamais d'après leur rang dans [cells] : c'est ce qui garantit qu'une case ne change pas de
 * place (invariant 3). Un emplacement libre vaut `null`.
 */
fun layoutGrid(rowCount: Int, columnCount: Int, cells: List<Cell>): ImmutableList<ImmutableList<Cell?>> {
    val slots = Array(rowCount) { arrayOfNulls<Cell>(columnCount) }
    for (cell in cells) {
        if (cell.row in 0 until rowCount && cell.column in 0 until columnCount) {
            slots[cell.row][cell.column] = cell
        } else {
            // Gardée en base : on ne la montre pas, on ne la supprime pas.
            Log.w(TAG, "Cell ${cell.id} at (${cell.row}, ${cell.column}) is outside a $rowCount x $columnCount grid")
        }
    }
    return slots.map { it.toList().toImmutableList() }.toImmutableList()
}

/** Ce qu'un adulte règle dans une case : jamais sa position (invariant 3). */
@Immutable
data class CellContent(
    val label: String,
    val pictogramId: Long?,
    val category: WordCategory?,
)
