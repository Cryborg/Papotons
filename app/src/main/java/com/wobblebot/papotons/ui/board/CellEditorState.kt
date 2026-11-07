package com.wobblebot.papotons.ui.board

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
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
    /** Un picto choisi dans la recherche, pas encore gardé sur l'appareil (il l'est à l'« Enregistrer »). */
    val chosenPictogram: ChosenPictogram? = null,
    /** Une photo prise ou choisie, prête (nom de fichier), pas encore écrite dans la case. */
    val chosenPhoto: String? = null,
    /** Une photo en préparation : « Enregistrer » attend. */
    val isImportingPhoto: Boolean = false,
    /** Pourquoi la dernière photo n'a pas pu servir. */
    val photoFailure: PhotoFailure? = null,
)

enum class PhotoFailure { UnusableImage, NoPhotoApp }

@Immutable
data class ChosenPictogram(val id: Long, val image: ImageBitmap)

enum class SearchStatus { Idle, Loading, Done, Failed, TooManyResults }

/** Un picto trouvé ; sa vignette arrive après la liste (`null` en attendant, ou si elle a échoué). */
@Immutable
data class FoundPictogram(val id: Long, val keyword: String, val image: ImageBitmap? = null)

@Immutable
data class PictogramSearchState(
    val query: String,
    val status: SearchStatus = SearchStatus.Idle,
    val results: ImmutableList<FoundPictogram> = persistentListOf(),
)
