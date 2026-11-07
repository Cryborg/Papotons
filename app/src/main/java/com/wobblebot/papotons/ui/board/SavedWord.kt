package com.wobblebot.papotons.ui.board

import com.wobblebot.papotons.domain.Picture
import com.wobblebot.papotons.domain.SentenceWord
import com.wobblebot.papotons.domain.WordCategory
import java.io.Serializable

/**
 * Un mot de la phrase en cours, tel qu'il passe la mort du processus (`SavedStateHandle`). La
 * catégorie voyage par son nom, comme en base : un nom inconnu se relit sans couleur. Nom et
 * champs gardés tels quels par R8 (`proguard-rules.pro`) : sérialisés, ils doivent se relire.
 */
internal data class SavedWord(
    val label: String,
    val categoryName: String?,
    val pictogramId: Long?,
    // Ajouté après coup : un état sauvé sans ce champ se relit avec `null` (sérialisation Java).
    val photoFile: String? = null,
    // Un état sauvé sans ce champ se relit avec `false` (sérialisation Java).
    val isConjugated: Boolean = false,
) : Serializable {
    fun toSentenceWord() = SentenceWord(
        label,
        WordCategory.fromStoredName(categoryName),
        photoFile?.let(Picture::Photo) ?: pictogramId?.let(Picture::Pictogram),
        isConjugated,
    )

    companion object {
        private const val serialVersionUID = 1L

        fun from(word: SentenceWord): SavedWord {
            val picture = word.picture
            return when (picture) {
                is Picture.Pictogram -> SavedWord(word.label, word.category?.name, picture.id, null, word.isConjugated)
                is Picture.Photo -> SavedWord(word.label, word.category?.name, null, picture.fileName, word.isConjugated)
                null -> SavedWord(word.label, word.category?.name, null, null, word.isConjugated)
            }
        }
    }
}
