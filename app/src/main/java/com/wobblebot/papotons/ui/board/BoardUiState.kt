package com.wobblebot.papotons.ui.board

import androidx.compose.runtime.Immutable
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.SentenceToken
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
    /** `null` tant que l'accueil n'est pas lu (au premier lancement, le temps d'insérer le vocabulaire de base). */
    val board: DisplayedBoard? = null,
    /** La phrase telle qu'elle s'affiche et se dit (« j'ai », pas « je ai »). */
    val message: ImmutableList<SentenceToken> = persistentListOf(),
    val speechStatus: SpeechStatus = SpeechStatus.Initializing,
    /** Les grilles ont été trouvées perdues : alerte pour l'adulte, jusqu'à acquittement. */
    val hasLostBoards: Boolean = false,
    /** La voix ne peut pas s'entendre (volume média à zéro ou coupé). */
    val isMuted: Boolean = false,
    /** Un appui sur « Son coupé » n'a pas pu monter le son : modale pour l'adulte. */
    val isVolumeLockedDialogOpen: Boolean = false,
) {
    /** Une phrase est en cours : rien ne doit interrompre (redémarrage de mise à jour). */
    val isComposingMessage: Boolean get() = message.isNotEmpty()
}
