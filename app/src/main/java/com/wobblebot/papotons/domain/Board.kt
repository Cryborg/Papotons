package com.wobblebot.papotons.domain

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** L'accueil standard dans `CoreVocabulary` : chaque utilisateur en a une copie (`core_board_id`) ; celle de la graine ou de la migration garde cet id. */
const val HOME_BOARD_ID = 1L

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
    /** Photo prise ou choisie par l'adulte (nom dans `files/photos/`) : elle l'emporte sur le picto. */
    val photoFile: String? = null,
    /** Ce que la case dit, s'il diffère de son libellé (« Mamie » affiché, « Mamie Jeanne » dit). */
    val spokenText: String? = null,
) {
    val picture: Picture? get() = photoFile?.let(Picture::Photo) ?: pictogramId?.let(Picture::Pictogram)

    /** Ce qui se dit et entre dans la phrase. */
    val spoken: String get() = spokenText ?: label
}

/** L'image d'une case ou d'un mot de la phrase. */
@Immutable
sealed interface Picture {
    data class Pictogram(val id: Long) : Picture

    data class Photo(val fileName: String) : Picture
}

@Immutable
data class Board(
    val id: Long,
    val name: String,
    val rowCount: Int,
    val columnCount: Int,
    val cells: ImmutableList<Cell>,
)

fun Cell.isInside(rowCount: Int, columnCount: Int): Boolean = row in 0 until rowCount && column in 0 until columnCount

/**
 * Range les cases dans une matrice [rowCount] × [columnCount] d'après leur position stockée,
 * jamais d'après leur rang dans [cells] : c'est ce qui garantit qu'une case ne change pas de
 * place (invariant 3). Un emplacement libre vaut `null`.
 */
fun layoutGrid(rowCount: Int, columnCount: Int, cells: List<Cell>): ImmutableList<ImmutableList<Cell?>> {
    val slots = Array(rowCount) { arrayOfNulls<Cell>(columnCount) }
    // Une case hors de la grille reste en base : on ne la montre pas, on ne la supprime pas.
    for (cell in cells) {
        if (cell.isInside(rowCount, columnCount)) slots[cell.row][cell.column] = cell
    }
    return slots.map { it.toList().toImmutableList() }.toImmutableList()
}

/** Ce qu'un adulte règle dans une case : jamais sa position (invariant 3). */
@Immutable
data class CellContent(
    val label: String,
    val pictogramId: Long?,
    val category: WordCategory?,
    // Sans valeur par défaut : un chemin d'écriture (édition, import) qui les oublierait effacerait la
    // photo ou le texte dit d'une case, et doit donc ne pas compiler.
    val photoFile: String?,
    /** `null` : la case dit son libellé. */
    val spokenText: String?,
)
