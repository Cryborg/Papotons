package com.wobblebot.papotons.ui.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.toSize
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.WordCategory
import com.wobblebot.papotons.ui.theme.wordCategoryBackground
import kotlinx.collections.immutable.ImmutableList

// Grille
internal val GRID_PADDING = 8.dp
// 8 dp au moins entre deux cibles : un appui qui tremble ne doit pas toucher la voisine.
internal val CELL_SPACING = 8.dp

// Glisser-déposer, en édition : la place d'origine de la case portée reste visible, en pâle.
private const val LIFTED_ORIGIN_ALPHA = 0.35f

// Dossier : contour épais (son icône ou sa marque sont dessinées par CellFace).
private val FOLDER_BORDER_WIDTH = 3.dp

/**
 * Toutes les cases, à leur position, sans défilement. Forcée de gauche à droite : une langue
 * écrite de droite à gauche ne doit pas renverser les colonnes (invariant 3). En édition, un appui
 * long soulève une case, qui suit le doigt jusqu'à son nouvel emplacement ([onCellMoved]).
 */
@Composable
fun CommunicationGrid(
    grid: ImmutableList<ImmutableList<Cell?>>,
    onCellClick: (Cell) -> Unit,
    modifier: Modifier = Modifier,
    /** En édition, un emplacement libre devient une case « + » qui en ajoute une. */
    isEditing: Boolean = false,
    onEmptySlotClick: (row: Int, column: Int) -> Unit = { _, _ -> },
    /** Appui long sur un verbe, hors édition : la page de ses formes. */
    onCellLongClick: (Cell) -> Unit = {},
    /** En édition, une case lâchée sur un autre emplacement : une case déjà là prend sa place. */
    onCellMoved: (cell: Cell, toRow: Int, toColumn: Int) -> Unit = { _, _, _ -> },
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        val density = LocalDensity.current
        var gridSize by remember { mutableStateOf(Size.Zero) }
        val rowCount = grid.size
        val columnCount = grid.firstOrNull()?.size ?: 0
        val geometry = remember(gridSize, rowCount, columnCount, density) {
            with(density) { GridGeometry(gridSize, rowCount, columnCount, GRID_PADDING.toPx(), CELL_SPACING.toPx()) }
        }
        // Une édition terminée pendant un glisser retire les `pointerInput` : le geste s'annule, la case retombe.
        val drag = remember { CellDrag() }
        // Lu seulement par la couche du dessus : la grille ne se recompose pas à chaque emplacement franchi.
        val hoveredSlot = remember(geometry) { derivedStateOf { if (drag.cell == null) null else geometry.slotAt(drag.pointer) } }
        val moveLabels = MoveLabels(
            left = stringResource(R.string.edit_move_left),
            right = stringResource(R.string.edit_move_right),
            up = stringResource(R.string.edit_move_up),
            down = stringResource(R.string.edit_move_down),
        )
        Box(modifier = modifier.onSizeChanged { gridSize = it.toSize() }) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(GRID_PADDING),
                verticalArrangement = Arrangement.spacedBy(CELL_SPACING),
            ) {
                for ((rowIndex, row) in grid.withIndex()) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(CELL_SPACING),
                    ) {
                        for ((columnIndex, cell) in row.withIndex()) {
                            val slot = GridSlot(rowIndex, columnIndex)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            ) {
                                when {
                                    cell != null -> CellButton(
                                        cell = cell,
                                        isEditing = isEditing,
                                        onClick = { onCellClick(cell) },
                                        onLongClick = { onCellLongClick(cell) },
                                        isLifted = drag.cell?.id == cell.id,
                                        drag = if (isEditing) drag else null,
                                        slot = slot,
                                        geometry = geometry,
                                        onCellMoved = onCellMoved,
                                        moveActions = if (isEditing) {
                                            remember(cell, slot, rowCount, columnCount, moveLabels, onCellMoved) {
                                                moveActions(slot, rowCount, columnCount, moveLabels) { target -> onCellMoved(cell, target.row, target.column) }
                                            }
                                        } else {
                                            emptyList()
                                        },
                                    )
                                    isEditing -> AddSlot(rowIndex, columnIndex, onClick = { onEmptySlotClick(rowIndex, columnIndex) })
                                }
                            }
                        }
                    }
                }
            }
            val lifted = drag.cell
            if (lifted != null) LiftedCell(lifted, geometry, drag, hoveredSlot)
        }
    }
}

@Composable
private fun CellButton(
    cell: Cell,
    isEditing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isLifted: Boolean = false,
    /** En édition : le glisser partagé par la grille ; `null` hors édition (rien ne se déplace). */
    drag: CellDrag? = null,
    slot: GridSlot = GridSlot(cell.row, cell.column),
    geometry: GridGeometry? = null,
    onCellMoved: (Cell, Int, Int) -> Unit = { _, _, _ -> },
    moveActions: List<CustomAccessibilityAction> = emptyList(),
) {
    val isFolder = cell.linkedBoardId != null
    // Seul un verbe a des formes ; ailleurs (et en édition) un appui long reste un appui simple.
    val hasForms = cell.category == WordCategory.VERB && !isFolder && !isEditing
    val description = when {
        isEditing && isFolder -> stringResource(R.string.edit_folder_description, cell.label)
        isEditing -> stringResource(R.string.edit_cell_description, cell.label)
        isFolder -> stringResource(R.string.board_folder_description, cell.label)
        else -> null
    }
    CellCard(
        cell = cell,
        modifier = Modifier
            .fillMaxSize()
            // Comme Surface(onClick) : une case plus petite que 48 dp reçoit quand même le doigt.
            .minimumInteractiveComponentSize()
            .clip(BoardCardShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (hasForms) onLongClick else null,
                // Annoncé par TalkBack, qui propose l'appui long comme une action.
                onLongClickLabel = if (hasForms) stringResource(R.string.conjugation_long_click_label) else null,
                role = Role.Button,
            )
            .then(if (drag != null && geometry != null) Modifier.cellDrag(cell, slot, geometry, drag, onCellMoved) else Modifier)
            // Soulevée : sa place d'origine reste visible, en pâle.
            .then(if (isLifted) Modifier.alpha(LIFTED_ORIGIN_ALPHA) else Modifier)
            .semantics {
                if (description != null) contentDescription = description
                if (moveActions.isNotEmpty()) customActions = moveActions
            },
    )
}

/** Le fond, le contour et le dessin d'une case : dans la grille, et sous le doigt pendant un glisser. */
@Composable
internal fun CellCard(cell: Cell, modifier: Modifier) {
    val isFolder = cell.linkedBoardId != null
    val category = cell.category
    val background = when {
        category != null -> wordCategoryBackground(category)
        isFolder -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = BoardCardShape,
        color = background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // Un dossier a la couleur de sa catégorie, comme un mot : son contour épais et sa marque
        // disent d'un coup d'œil qu'il ouvre une page au lieu de parler.
        border = BorderStroke(
            if (isFolder) FOLDER_BORDER_WIDTH else BOARD_CARD_BORDER_WIDTH,
            MaterialTheme.colorScheme.outline,
        ),
    ) {
        CellFace(cell.label, cell.picture, isFolder)
    }
}

/** Un emplacement libre en édition : il ajoute une case à cette position, et nulle part ailleurs. */
@Composable
private fun AddSlot(row: Int, column: Int, onClick: () -> Unit) {
    // Lignes et colonnes comptées à partir de 1 pour TalkBack.
    val description = stringResource(R.string.edit_add_cell_description, row + 1, column + 1)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        shape = BoardCardShape,
        color = MaterialTheme.colorScheme.surface,
        // outline : 3:1 au moins sur le fond, la zone à toucher se voit.
        border = BorderStroke(BOARD_CARD_BORDER_WIDTH, MaterialTheme.colorScheme.outline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

