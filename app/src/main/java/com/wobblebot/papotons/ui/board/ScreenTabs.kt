package com.wobblebot.papotons.ui.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.Screen
import kotlinx.collections.immutable.ImmutableList

/** Au-delà, un nom long finit par des points de suspension : les autres écrans restent en vue. */
private val TAB_MAX_WIDTH = 200.dp
private val TAB_HORIZONTAL_PADDING = 16.dp

/**
 * Les écrans de l'enfant, au centre de la barre du bas : en toucher un ouvre cet écran. Celui de
 * l'écran ouvert ([selectedId], même depuis un de ses dossiers) est plein, les autres ont un contour.
 * Chaque pastille est sa cible entière, sur toute l'épaisseur de la barre, à [TARGET_SPACING] de la
 * voisine ; la rangée défile quand ils ne tiennent pas, et ramène toujours l'écran ouvert en vue
 * (zoom, police agrandie) ; un nom long finit par des points de suspension.
 */
@Composable
fun ScreenTabs(screens: ImmutableList<Screen>, selectedId: Long, onChosen: (Long) -> Unit, modifier: Modifier = Modifier) {
    val rowDescription = stringResource(R.string.screen_tabs_description)
    Row(
        horizontalArrangement = Arrangement.spacedBy(TARGET_SPACING),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxHeight()
            .horizontalScroll(rememberScrollState())
            .selectableGroup()
            .semantics { contentDescription = rowDescription },
    ) {
        for (screen in screens) {
            ScreenTab(screen.name, isSelected = screen.id == selectedId, onClick = { onChosen(screen.id) })
        }
    }
}

@Composable
private fun ScreenTab(name: String, isSelected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val viewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(isSelected) { if (isSelected) viewRequester.bringIntoView() }
    // La pastille est la cible, ni plus ni moins : on voit où l'appui compte.
    Surface(
        shape = BoardCardShape,
        color = if (isSelected) colors.primary else colors.surface,
        contentColor = if (isSelected) colors.onPrimary else colors.onSurface,
        border = if (isSelected) null else BorderStroke(BOARD_CARD_BORDER_WIDTH, colors.outline),
        modifier = Modifier
            .bringIntoViewRequester(viewRequester)
            .fillMaxHeight()
            .widthIn(min = CHILD_BUTTON_MIN_WIDTH, max = TAB_MAX_WIDTH)
            .clip(BoardCardShape)
            .selectable(selected = isSelected, role = Role.Tab, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = TAB_HORIZONTAL_PADDING)) {
            // Une ligne : la barre garde son épaisseur même à police agrandie.
            Text(text = name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
