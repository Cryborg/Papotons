package com.wobblebot.papotons.ui.board

import androidx.compose.runtime.Immutable
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.SentenceToken
import com.wobblebot.papotons.speech.SpeechStatus
import com.wobblebot.papotons.ui.settings.SettingsPage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** La grille affichée, ses cases déjà rangées par position (`null` = emplacement libre). */
@Immutable
data class DisplayedBoard(
    val id: Long,
    val name: String,
    val grid: ImmutableList<ImmutableList<Cell?>>,
    val canGoBack: Boolean,
    /** L'id d'origine (`CoreVocabulary`) du bas de la pile, dont on lit la taille ; `null` pour une grille de l'adulte. */
    val rootCoreBoardId: Long? = null,
)

/** Un choix de taille qui n'a rien changé. */
enum class SettingFailure {
    /** Écriture ou lecture ratée : réessayer peut suffire. */
    NotSaved,

    /** L'utilisateur ouvert n'a pas l'accueil de cette taille (grilles d'avant les tailles). */
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
    /** La page des Réglages ouverte (par un adulte après le contrôle, ou depuis l'édition), `null` s'ils sont fermés. */
    val settingsPage: SettingsPage? = null,
    /** Pourquoi le dernier choix de taille n'a rien changé, `null` s'il a réussi. */
    val settingFailure: SettingFailure? = null,
    /** L'éditeur de case ouvert, en édition seulement. */
    val editor: CellEditorState? = null,
    /** La recherche de pictos ouverte depuis l'éditeur. */
    val pictogramSearch: PictogramSearchState? = null,
    /** La page des formes d'un verbe, à la place de la grille. */
    val verbForms: VerbFormsPage? = null,
) {
    val isSettingsOpen: Boolean get() = settingsPage != null

    /** Une phrase, une modification, un réglage ou un choix de forme est en cours : rien ne doit interrompre (redémarrage de mise à jour). */
    val isBusy: Boolean get() = message.isNotEmpty() || isEditing || isSettingsOpen || verbForms != null

    /** La taille de la grille ouverte, lue sur l'écran réellement ouvert (pas sur le réglage) ; `null` pour un autre écran. */
    val boardSize: BoardSize? get() = BoardSize.withHome(board?.rootCoreBoardId)
}
