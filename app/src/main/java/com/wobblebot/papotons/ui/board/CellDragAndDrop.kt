package com.wobblebot.papotons.ui.board

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.wobblebot.papotons.domain.Cell

// La case portée grossit un peu et flotte au-dessus de la grille ; l'emplacement visé est entouré.
private const val LIFTED_CELL_SCALE = 1.08f
private val LIFTED_CELL_ELEVATION = 8.dp
private val DROP_TARGET_BORDER_WIDTH = 3.dp

/** La case qu'on glisse, en édition : d'où elle part, où est le doigt (coordonnées de la grille). */
@Stable
internal class CellDrag {
    var cell by mutableStateOf<Cell?>(null)
    var origin by mutableStateOf(GridSlot(0, 0))

    /** Où le doigt a pris la case, depuis son coin : elle suit le doigt sans sauter. */
    var grab by mutableStateOf(Offset.Zero)

    /** Change à chaque image du glisser : lu seulement dans `graphicsLayer` et un `derivedStateOf`. */
    var pointer by mutableStateOf(Offset.Zero)
}

/** Les libellés des déplacements d'un cran, lus une fois pour toute la grille. */
@Immutable
internal data class MoveLabels(val left: String, val right: String, val up: String, val down: String)

/**
 * Appui long, puis glisser : la case suit le doigt et va où il la lâche. Après le clic de la case dans
 * la chaîne : le relâcher d'un glisser est consommé, l'éditeur ne s'ouvre pas, même sans avoir bougé.
 * Un seul glisser à la fois : un second doigt qui soulève une autre case pendant ce temps est ignoré.
 */
@Composable
internal fun Modifier.cellDrag(cell: Cell, slot: GridSlot, geometry: GridGeometry, drag: CellDrag, onCellMoved: (Cell, Int, Int) -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    val currentGeometry by rememberUpdatedState(geometry)
    val currentOnCellMoved by rememberUpdatedState(onCellMoved)
    return pointerInput(cell, slot) {
        // Ce geste a-t-il pris la case ? Faux pour un second doigt pendant qu'une autre est portée.
        var isOwner = false
        detectDragGesturesAfterLongPress(
            onDragStart = { position ->
                isOwner = drag.cell == null
                if (isOwner) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    drag.origin = slot
                    drag.grab = position
                    drag.pointer = currentGeometry.originOf(slot) + position
                    drag.cell = cell
                }
            },
            onDrag = { change, amount ->
                change.consume()
                if (isOwner) drag.pointer += amount
            },
            onDragEnd = {
                if (isOwner) {
                    isOwner = false
                    val target = currentGeometry.slotAt(drag.pointer)
                    drag.cell = null
                    if (target != null && target != slot) currentOnCellMoved(cell, target.row, target.column)
                }
            },
            onDragCancel = {
                if (isOwner) {
                    isOwner = false
                    drag.cell = null
                }
            },
        )
    }
}

/** Les quatre déplacements d'un cran, pour TalkBack et Switch Access : seulement vers un emplacement qui existe. */
internal fun moveActions(slot: GridSlot, rowCount: Int, columnCount: Int, labels: MoveLabels, onMove: (GridSlot) -> Unit): List<CustomAccessibilityAction> {
    fun action(label: String, target: GridSlot) = CustomAccessibilityAction(label) {
        onMove(target)
        true
    }
    return listOfNotNull(
        action(labels.left, GridSlot(slot.row, slot.column - 1)).takeIf { slot.column > 0 },
        action(labels.right, GridSlot(slot.row, slot.column + 1)).takeIf { slot.column < columnCount - 1 },
        action(labels.up, GridSlot(slot.row - 1, slot.column)).takeIf { slot.row > 0 },
        action(labels.down, GridSlot(slot.row + 1, slot.column)).takeIf { slot.row < rowCount - 1 },
    )
}

/** Au-dessus de toute la grille : l'emplacement visé, entouré, et la case qui suit le doigt. */
@Composable
internal fun LiftedCell(cell: Cell, geometry: GridGeometry, drag: CellDrag, hoveredSlot: State<GridSlot?>) {
    val density = LocalDensity.current
    val cellSize = with(density) { DpSize(geometry.cellSize.width.toDp(), geometry.cellSize.height.toDp()) }
    val hovered = hoveredSlot.value
    if (hovered != null && hovered != drag.origin) {
        Box(
            modifier = Modifier
                .offset { geometry.originOf(hovered).round() }
                .size(cellSize)
                .border(DROP_TARGET_BORDER_WIDTH, MaterialTheme.colorScheme.primary, BoardCardShape),
        )
    }
    CellCard(
        cell = cell,
        modifier = Modifier
            .size(cellSize)
            .graphicsLayer {
                translationX = drag.pointer.x - drag.grab.x
                translationY = drag.pointer.y - drag.grab.y
                scaleX = LIFTED_CELL_SCALE
                scaleY = LIFTED_CELL_SCALE
                shadowElevation = LIFTED_CELL_ELEVATION.toPx()
                shape = BoardCardShape
                clip = true
            }
            // Un dessin seulement : TalkBack lit la case restée à sa place.
            .clearAndSetSemantics {},
    )
}
