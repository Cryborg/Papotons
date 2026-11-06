package com.wobblebot.papotons.ui.board

import androidx.compose.runtime.Immutable
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.speech.SpeechStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** La grille affichée, ses cases déjà rangées par position (`null` = emplacement libre). */
@Immutable
data class DisplayedBoard(
    val id: Long,
    val name: String,
    val grid: ImmutableList<ImmutableList<Cell?>>,
    val canGoBack: Boolean,
)

@Immutable
data class BoardUiState(
    /** `null` tant que l'accueil n'est pas lu (au premier lancement, le temps d'insérer la démo). */
    val board: DisplayedBoard? = null,
    val message: ImmutableList<String> = persistentListOf(),
    val speechStatus: SpeechStatus = SpeechStatus.Initializing,
) {
    /** Une phrase est en cours : rien ne doit interrompre (redémarrage de mise à jour). */
    val isComposingMessage: Boolean get() = message.isNotEmpty()
}
