package com.wobblebot.papotons.ui.board

import androidx.compose.runtime.Immutable
import com.wobblebot.papotons.domain.BoardSize
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
    /** Le bas de la pile : l'écran de départ réellement ouvert, dont on lit la taille. */
    val rootBoardId: Long = id,
)

/** Un choix de taille qui n'a rien changé. */
enum class SettingFailure {
    /** Écriture ou lecture ratée : réessayer peut suffire. */
    NotSaved,

    /** Ces grilles n'ont pas l'accueil de cette taille (sauvegarde d'avant les tailles). */
    Unavailable,
}

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
    /** Mode édition, ouvert par un adulte après le contrôle parental. */
    val isEditing: Boolean = false,
    /** L'écran Réglages, ouvert par un adulte après le contrôle parental, ou depuis l'édition. */
    val isSettingsOpen: Boolean = false,
    /** Pourquoi le dernier choix de taille n'a rien changé, `null` s'il a réussi. */
    val settingFailure: SettingFailure? = null,
    /** L'éditeur de case ouvert, en édition seulement. */
    val editor: CellEditorState? = null,
    /** La recherche de pictos ouverte depuis l'éditeur. */
    val pictogramSearch: PictogramSearchState? = null,
) {
    /** Une phrase, une modification ou un réglage est en cours : rien ne doit interrompre (redémarrage de mise à jour). */
    val isBusy: Boolean get() = message.isNotEmpty() || isEditing || isSettingsOpen

    /** La taille de la grille ouverte, lue sur l'écran réellement ouvert (pas sur le réglage) ; `null` pour un autre écran. */
    val boardSize: BoardSize? get() = BoardSize.withHome(board?.rootBoardId)
}
