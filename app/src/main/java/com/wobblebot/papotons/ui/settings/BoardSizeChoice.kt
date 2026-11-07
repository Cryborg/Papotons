package com.wobblebot.papotons.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.BoardSize

private val CARD_PADDING = 16.dp
private val CARD_SPACING = 12.dp
private val SELECTED_BORDER_WIDTH = 2.dp
private val PREVIEW_SIZE = 56.dp
private val PREVIEW_GAP = 2.dp
private val PREVIEW_CORNER = 2.dp
private val PREVIEW_SPACING = 16.dp

/**
 * Largeur de carte, à police normale, au-dessous de laquelle l'aperçu passe au-dessus du texte : à
 * côté, il le hacherait. Grandit avec la police (à 200 %, il faut deux fois plus de place).
 */
private val PREVIEW_BESIDE_TEXT_MIN_WIDTH = 300.dp
private val TEXT_SPACING = 8.dp

@StringRes
internal fun BoardSize.title(): Int = when (this) {
    BoardSize.LARGE_CELLS -> R.string.board_size_large_cells
    BoardSize.MEDIUM -> R.string.board_size_medium
    BoardSize.STANDARD -> R.string.board_size_standard
}

@StringRes
private fun BoardSize.description(): Int = when (this) {
    BoardSize.LARGE_CELLS -> R.string.board_size_large_cells_description
    BoardSize.MEDIUM -> R.string.board_size_medium_description
    BoardSize.STANDARD -> R.string.board_size_standard_description
}

/**
 * Les trois tailles en cartes empilées (lisibles à police agrandie), groupe radio : le bouton radio dit
 * le choix, le contour le souligne. Le choix de la taille d'un nouvel écran.
 */
@Composable
fun BoardSizeChoice(selected: BoardSize, onChosen: (BoardSize) -> Unit) {
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(CARD_SPACING)) {
        for (size in BoardSize.entries) {
            BoardSizeCard(size, isSelected = size == selected, onClick = { onChosen(size) })
        }
    }
}

@Composable
private fun BoardSizeCard(size: BoardSize, isSelected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val previewColor = if (isSelected) colors.primary else colors.outline
    Card(
        shape = CARD_SHAPE,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = if (isSelected) BorderStroke(SELECTED_BORDER_WIDTH, colors.primary) else null,
        // Découpé avant la sélection : l'ondulation de l'appui suit les coins arrondis.
        modifier = Modifier
            .fillMaxWidth()
            .clip(CARD_SHAPE)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick),
    ) {
        BoxWithConstraints {
            val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            val isPreviewBeside = maxWidth >= PREVIEW_BESIDE_TEXT_MIN_WIDTH * fontScale
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(CARD_PADDING)) {
                RadioButton(selected = isSelected, onClick = null)
                Spacer(modifier = Modifier.width(PREVIEW_SPACING))
                if (isPreviewBeside) {
                    GridPreview(size.homeRowCount, size.homeColumnCount, previewColor)
                    Spacer(modifier = Modifier.width(PREVIEW_SPACING))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TEXT_SPACING)) {
                    if (!isPreviewBeside) GridPreview(size.homeRowCount, size.homeColumnCount, previewColor)
                    Text(stringResource(size.title()), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(size.description()), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** Un dessin de la grille, cases comprises. Décoratif : le titre dit déjà « 3 × 4 ». */
@Composable
private fun GridPreview(rowCount: Int, columnCount: Int, color: Color) {
    Canvas(modifier = Modifier.size(PREVIEW_SIZE)) {
        val gap = PREVIEW_GAP.toPx()
        val cellWidth = (size.width - gap * (columnCount - 1)) / columnCount
        val cellHeight = (size.height - gap * (rowCount - 1)) / rowCount
        val corner = CornerRadius(PREVIEW_CORNER.toPx())
        for (row in 0 until rowCount) {
            for (column in 0 until columnCount) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(column * (cellWidth + gap), row * (cellHeight + gap)),
                    size = Size(cellWidth, cellHeight),
                    cornerRadius = corner,
                )
            }
        }
    }
}
