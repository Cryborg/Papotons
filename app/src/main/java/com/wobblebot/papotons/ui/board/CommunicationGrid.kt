package com.wobblebot.papotons.ui.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.Cell
import kotlinx.collections.immutable.ImmutableList

private val GRID_PADDING = 8.dp
// 8 dp au moins entre deux cibles : un appui qui tremble ne doit pas toucher la voisine.
private val CELL_SPACING = 8.dp
private val CELL_CONTENT_PADDING = 6.dp
private val FOLDER_ICON_SIZE = 20.dp
// Bas, parce qu'il grossit avec la police système : à 200 %, un plancher à 12 sp coupait déjà « finished ».
private val LABEL_MIN_FONT_SIZE = 8.sp
private val LABEL_MAX_FONT_SIZE = 40.sp
// Pas de 2 sp : deux fois moins de mesures de texte par case qu'au pas de 1 sp, à chaque changement de grille.
private val LABEL_FONT_STEP = 2.sp
private const val LABEL_MIN_LINES = 1
private const val LABEL_MAX_LINES = 2
private val LABEL_LINE_HEIGHT = 1.15.em

/**
 * Toutes les cases, à leur position, sans défilement. Forcée de gauche à droite : une langue
 * écrite de droite à gauche ne doit pas renverser les colonnes (invariant 3).
 */
@Composable
fun CommunicationGrid(
    grid: ImmutableList<ImmutableList<Cell?>>,
    onCellClick: (Cell) -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier = modifier.padding(GRID_PADDING),
            verticalArrangement = Arrangement.spacedBy(CELL_SPACING),
        ) {
            for (row in grid) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CELL_SPACING),
                ) {
                    for (cell in row) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            if (cell != null) CellButton(cell = cell, onClick = { onCellClick(cell) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CellButton(cell: Cell, onClick: () -> Unit) {
    val isFolder = cell.linkedBoardId != null
    val folderDescription = if (isFolder) stringResource(R.string.board_folder_description, cell.label) else null
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                role = Role.Button
                if (folderDescription != null) contentDescription = folderDescription
            },
        shape = BoardCardShape,
        color = if (isFolder) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (isFolder) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(BOARD_CARD_BORDER_WIDTH, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(CELL_CONTENT_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (isFolder) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    modifier = Modifier.size(FOLDER_ICON_SIZE),
                )
            }
            val contentColor = LocalContentColor.current
            val titleStyle = MaterialTheme.typography.titleLarge
            // Interligne proportionnel : celui du style, fixe, ferait chevaucher les lignes agrandies.
            val labelStyle = remember(titleStyle) {
                titleStyle.copy(textAlign = TextAlign.Center, lineHeight = LABEL_LINE_HEIGHT)
            }
            // Autant de lignes que de mots (deux au plus) : un mot seul rapetisse au lieu
            // d'être coupé en deux, ce que la taille adaptative ferait pour remplir la case.
            val maxLines = remember(cell.label) {
                cell.label.split(' ').count { it.isNotBlank() }.coerceIn(LABEL_MIN_LINES, LABEL_MAX_LINES)
            }
            BasicText(
                text = cell.label,
                style = labelStyle,
                color = { contentColor },
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = LABEL_MIN_FONT_SIZE,
                    maxFontSize = LABEL_MAX_FONT_SIZE,
                    stepSize = LABEL_FONT_STEP,
                ),
            )
        }
    }
}
