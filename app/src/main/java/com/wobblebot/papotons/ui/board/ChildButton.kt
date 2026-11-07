package com.wobblebot.papotons.ui.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Entre deux cibles voisines : un appui qui tremble ne doit pas toucher la voisine. */
internal val TARGET_SPACING = 8.dp

/** La cible minimale d'Android, en largeur : en hauteur, un bouton de l'enfant prend toute la barre. */
internal val CHILD_BUTTON_MIN_WIDTH = 48.dp

/** Les cibles de la barre, carte de la phrase comprise, ont toutes la même hauteur. */
internal val CHILD_BAR_TOP_PADDING = 6.dp

/**
 * Plus grand qu'en haut : un appui qui dépasse une case du haut de la grille tombe dans le vide, pas
 * sur « Tout effacer », qui vide la phrase sans retour possible.
 */
internal val CHILD_BAR_BOTTOM_PADDING = 20.dp

private val CHILD_BUTTON_HORIZONTAL_PADDING = 8.dp
private const val DISABLED_CONTENT_ALPHA = 0.38f

/**
 * Un bouton de la barre de l'enfant : une carte au contour visible, et toute la carte est la cible,
 * ni plus ni moins. L'adulte et l'enfant voient où l'appui compte (planification motrice).
 */
@Composable
internal fun ChildButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = BoardCardShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        // Désactivé, le contour pâlit avec l'icône : l'état se voit d'un coup d'œil.
        border = BorderStroke(BOARD_CARD_BORDER_WIDTH, if (enabled) outline else outline.copy(alpha = DISABLED_CONTENT_ALPHA)),
        // Le retrait est hors de la carte : la marge n'est pas touchable. `Surface(onClick)` ne pose
        // aucun rôle, contrairement à `IconButton` : TalkBack doit dire « bouton ».
        modifier = modifier
            .fillMaxHeight()
            .padding(top = CHILD_BAR_TOP_PADDING, bottom = CHILD_BAR_BOTTOM_PADDING)
            .widthIn(min = CHILD_BUTTON_MIN_WIDTH)
            .semantics { role = Role.Button },
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = CHILD_BUTTON_HORIZONTAL_PADDING),
            content = content,
        )
    }
}

/** [ChildButton] à une icône, qui porte le nom du bouton pour TalkBack. */
@Composable
internal fun ChildIconButton(
    painter: Painter,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    ChildButton(onClick = onClick, enabled = enabled) {
        Icon(painter, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = DISABLED_CONTENT_ALPHA))
    }
}
