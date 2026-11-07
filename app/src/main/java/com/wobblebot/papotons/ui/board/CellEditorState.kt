package com.wobblebot.papotons.ui.board

import androidx.compose.runtime.Immutable
import com.wobblebot.papotons.domain.Cell

/** La case qu'ouvre l'éditeur : un emplacement libre à remplir, ou une case existante. */
@Immutable
sealed interface EditTarget {
    val boardId: Long

    data class NewCell(override val boardId: Long, val row: Int, val column: Int) : EditTarget

    data class ExistingCell(override val boardId: Long, val cell: Cell) : EditTarget
}

/** Pourquoi une écriture n'a pas abouti : l'adulte n'a pas la même chose à faire. */
enum class EditFailure { SlotTakenOrCellGone, Storage }

@Immutable
data class CellEditorState(
    val target: EditTarget,
    /** Une écriture en cours : « Enregistrer » et « Supprimer » attendent, un double appui n'écrit pas deux fois. */
    val isWriting: Boolean = false,
    val failure: EditFailure? = null,
    /** Unique par écriture : son résultat ne touche que l'état qu'elle a posé, jamais un éditeur égal rouvert depuis. */
    val writeToken: Long = 0,
)
