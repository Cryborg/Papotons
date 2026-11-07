package com.wobblebot.papotons.ui.board

import com.wobblebot.papotons.domain.SentenceWord
import com.wobblebot.papotons.domain.WordCategory
import java.io.Serializable

/**
 * Un mot de la phrase en cours, tel qu'il passe la mort du processus (`SavedStateHandle`). La
 * catégorie voyage par son nom, comme en base : un nom inconnu se relit sans couleur. Nom et
 * champs gardés tels quels par R8 (`proguard-rules.pro`) : sérialisés, ils doivent se relire.
 */
internal data class SavedWord(val label: String, val categoryName: String?, val pictogramId: Long?) : Serializable {
    fun toSentenceWord() = SentenceWord(label, WordCategory.fromStoredName(categoryName), pictogramId)

    companion object {
        private const val serialVersionUID = 1L

        fun from(word: SentenceWord) = SavedWord(word.label, word.category?.name, word.pictogramId)
    }
}
