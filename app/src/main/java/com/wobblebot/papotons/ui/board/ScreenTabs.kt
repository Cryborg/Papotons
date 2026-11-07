package com.wobblebot.papotons.ui.board

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * Hauteur minimale d'un onglet : une cible large pour un doigt qui vise mal. Elle ne grandit qu'avec
 * la police du système (réglée une fois), jamais avec ce qui s'affiche : la grille ne bouge pas.
 */
private val TAB_MIN_HEIGHT = 56.dp

/** Au-delà, un nom long finit par des points de suspension : les autres onglets restent en vue. */
private val TAB_MAX_WIDTH = 240.dp
private val TAB_HORIZONTAL_PADDING = 20.dp
private val TAB_SPACING = 8.dp
private val TAB_ROW_HORIZONTAL_PADDING = 8.dp
private val TAB_SHAPE = RoundedCornerShape(12.dp)

/**
 * Les onglets de l'enfant, un par écran, entre la barre du haut et la grille : en toucher un ouvre cet
 * écran. Celui de l'écran ouvert ([selectedId], même depuis un de ses dossiers) est plein. La rangée
 * défile quand ils ne tiennent pas, et ramène toujours l'onglet ouvert en vue (zoom, police agrandie) ;
 * un nom long finit par des points de suspension.
 */
@Composable
fun ScreenTabs(screens: ImmutableList<Screen>, selectedId: Long, onChosen: (Long) -> Unit, modifier: Modifier = Modifier) {
    val rowDescription = stringResource(R.string.screen_tabs_description)
    Row(
        horizontalArrangement = Arrangement.spacedBy(TAB_SPACING),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = TAB_ROW_HORIZONTAL_PADDING)
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
    Surface(
        shape = TAB_SHAPE,
        color = if (isSelected) colors.primary else colors.surfaceVariant,
        contentColor = if (isSelected) colors.onPrimary else colors.onSurfaceVariant,
        // Découpé avant la sélection : l'ondulation de l'appui suit les coins arrondis.
        modifier = Modifier
            .bringIntoViewRequester(viewRequester)
            .heightIn(min = TAB_MIN_HEIGHT)
            .widthIn(max = TAB_MAX_WIDTH)
            .clip(TAB_SHAPE)
            .selectable(selected = isSelected, role = Role.Tab, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = TAB_MIN_HEIGHT).padding(horizontal = TAB_HORIZONTAL_PADDING)) {
            Text(text = name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
