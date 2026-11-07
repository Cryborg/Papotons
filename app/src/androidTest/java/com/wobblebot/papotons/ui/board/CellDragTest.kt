package com.wobblebot.papotons.ui.board

import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.layoutGrid
import com.wobblebot.papotons.ui.theme.PapotonsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Au-delà du délai d'appui long d'Android. */
private const val LONG_PRESS_MARGIN_MS = 200L
private const val DRAG_STEPS = 10

/** Le geste de l'édition : appui long, glisser, lâcher. Le vrai traitement des gestes de Compose. */
@RunWith(AndroidJUnit4::class)
class CellDragTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cells = listOf(
        Cell(id = 1, row = 0, column = 0, label = "je", linkedBoardId = null),
        Cell(id = 2, row = 1, column = 1, label = "veux", linkedBoardId = null),
    )
    private val clicks = mutableListOf<String>()
    private val moves = mutableListOf<Triple<String, Int, Int>>()

    private var isEditingState by mutableStateOf(true)

    private fun showEditing(isEditing: Boolean = true) {
        isEditingState = isEditing
        composeRule.setContent {
            PapotonsTheme {
                CommunicationGrid(
                    grid = layoutGrid(2, 3, cells),
                    onCellClick = { clicks += it.label },
                    isEditing = isEditingState,
                    onCellMoved = { cell, row, column -> moves += Triple(cell.label, row, column) },
                )
            }
        }
    }

    private fun centerOfEmptySlot(row: Int, column: Int): Offset =
        composeRule.onNodeWithContentDescription(context.getString(R.string.edit_add_cell_description, row + 1, column + 1)).fetchSemanticsNode().boundsInRoot.center

    /** Lâchée sans avoir bougé : ni éditeur ouvert, ni déplacement. */
    @Test
    fun aLongPressReleasedInPlaceOpensNothing() {
        showEditing()

        composeRule.onNodeWithText("je").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + LONG_PRESS_MARGIN_MS)
            up()
        }
        composeRule.waitForIdle()

        assertEquals(emptyList<String>(), clicks)
        assertEquals(emptyList<Triple<String, Int, Int>>(), moves)
    }

    @Test
    fun aCellDraggedOntoAnEmptySlotMovesThere() {
        showEditing()
        val start = composeRule.onNodeWithText("je").fetchSemanticsNode().boundsInRoot.center
        val target = centerOfEmptySlot(1, 2)

        composeRule.onNodeWithText("je").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + LONG_PRESS_MARGIN_MS)
            val step = (target - start) / DRAG_STEPS.toFloat()
            repeat(DRAG_STEPS) { moveBy(step) }
            up()
        }
        composeRule.waitForIdle()

        assertEquals(listOf(Triple("je", 1, 2)), moves)
        assertEquals(emptyList<String>(), clicks)
    }

    /** Un appui ordinaire ouvre toujours l'éditeur. */
    @Test
    fun aShortTapStillOpensTheEditor() {
        showEditing()

        composeRule.onNodeWithText("je").performTouchInput { click(center) }
        composeRule.waitForIdle()

        assertEquals(listOf("je"), clicks)
        assertEquals(emptyList<Triple<String, Int, Int>>(), moves)
    }

    private fun dragJe(by: (start: Offset) -> Offset, back: Boolean = false) {
        val start = composeRule.onNodeWithText("je").fetchSemanticsNode().boundsInRoot.center
        composeRule.onNodeWithText("je").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + LONG_PRESS_MARGIN_MS)
            val step = by(start) / DRAG_STEPS.toFloat()
            repeat(DRAG_STEPS) { moveBy(step) }
            if (back) repeat(DRAG_STEPS) { moveBy(-step) }
            up()
        }
        composeRule.waitForIdle()
    }

    /** Lâchée hors de la grille : rien ne bouge (jamais la case collée au bord le plus proche). */
    @Test
    fun aCellDroppedOutsideTheGridStaysPut() {
        showEditing()
        val gridHeight = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.height

        dragJe(by = { Offset(0f, gridHeight * 2) })

        assertEquals(emptyList<Triple<String, Int, Int>>(), moves)
        assertEquals(emptyList<String>(), clicks)
    }

    @Test
    fun aCellBroughtBackToItsPlaceDoesNotMove() {
        showEditing()
        val target = centerOfEmptySlot(1, 2)

        dragJe(by = { start -> target - start }, back = true)

        assertEquals(emptyList<Triple<String, Int, Int>>(), moves)
    }

    /** Hors édition, l'enfant ne déplace jamais une case (invariant 3). */
    @Test
    fun outsideTheEditModeALongPressDragMovesNothing() {
        showEditing(isEditing = false)
        val width = composeRule.onNodeWithText("je").fetchSemanticsNode().boundsInRoot.width

        dragJe(by = { Offset(width * 2, 0f) })

        assertEquals(emptyList<Triple<String, Int, Int>>(), moves)
    }

    private fun moveActionLabels(label: String): List<String> =
        composeRule.onNodeWithText(label).fetchSemanticsNode().config.getOrElse(SemanticsActions.CustomActions) { emptyList() }.map { it.label }

    /** Pour TalkBack et Switch Access : un cran dans chaque direction qui existe, et seulement celles-là. */
    @Test
    fun moveActionsGoOneStepAndOnlyTowardExistingSlots() {
        showEditing()
        val left = context.getString(R.string.edit_move_left)
        val right = context.getString(R.string.edit_move_right)
        val up = context.getString(R.string.edit_move_up)
        val down = context.getString(R.string.edit_move_down)

        assertEquals(listOf(right, down), moveActionLabels("je"))
        assertEquals(listOf(left, right, up), moveActionLabels("veux"))
        val veux = composeRule.onNodeWithText("veux").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        veux.single { it.label == right }.action()
        veux.single { it.label == up }.action()

        assertEquals(listOf(Triple("veux", 1, 2), Triple("veux", 0, 1)), moves)
    }

    @Test
    fun outsideTheEditModeThereIsNoMoveAction() {
        showEditing(isEditing = false)

        assertEquals(emptyList<String>(), moveActionLabels("je"))
    }

    /** L'édition se termine en plein glisser : rien ne bouge, aucune case ne reste en l'air, le geste suivant marche. */
    @Test
    fun anEditModeEndedDuringADragMovesNothingAndLeavesNoLiftedCell() {
        showEditing()
        val start = composeRule.onNodeWithText("je").fetchSemanticsNode().boundsInRoot.center
        val target = centerOfEmptySlot(1, 2)
        composeRule.onNodeWithText("je").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + LONG_PRESS_MARGIN_MS)
            moveBy((target - start) / 2f)
        }
        isEditingState = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("je").performTouchInput { up() }
        composeRule.waitForIdle()

        assertEquals(emptyList<Triple<String, Int, Int>>(), moves)
        // Une seule case « je » : la case portée a disparu avec le geste.
        composeRule.onAllNodesWithText("je", useUnmergedTree = true).assertCountEquals(1)

        isEditingState = true
        composeRule.waitForIdle()
        dragJe(by = { centerOfEmptySlot(1, 2) - it })
        assertEquals(listOf(Triple("je", 1, 2)), moves)
    }

    /** Un second doigt soulève une autre case pendant un glisser : seule la première bouge. */
    @Test
    fun aSecondFingerDoesNotTakeOverTheDrag() {
        showEditing()
        val je = composeRule.onNodeWithText("je").fetchSemanticsNode().boundsInRoot.center
        val veux = composeRule.onNodeWithText("veux").fetchSemanticsNode().boundsInRoot.center
        val target = centerOfEmptySlot(1, 2)

        composeRule.onRoot().performTouchInput {
            down(0, je)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + LONG_PRESS_MARGIN_MS)
            down(1, veux)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + LONG_PRESS_MARGIN_MS)
            val step = (target - je) / DRAG_STEPS.toFloat()
            repeat(DRAG_STEPS) { moveBy(0, step) }
            up(0)
            up(1)
        }
        composeRule.waitForIdle()

        assertEquals(listOf(Triple("je", 1, 2)), moves)
    }

    /** Un balayage sans appui long ne déplace rien : seul le geste voulu de l'adulte déplace une case (invariant 3). */
    @Test
    fun aQuickSwipeWithoutALongPressMovesNothing() {
        showEditing()
        val start = composeRule.onNodeWithText("je").fetchSemanticsNode().boundsInRoot.center
        val target = centerOfEmptySlot(1, 2)

        composeRule.onNodeWithText("je").performTouchInput {
            down(center)
            val step = (target - start) / DRAG_STEPS.toFloat()
            repeat(DRAG_STEPS) { moveBy(step) }
            up()
        }
        composeRule.waitForIdle()

        assertEquals(emptyList<Triple<String, Int, Int>>(), moves)
    }

    /** Lâchée sur une autre case : le déplacement est demandé vers sa place (l'échange se fait en base). */
    @Test
    fun aCellDroppedOnAnotherCellIsMovedThere() {
        showEditing()
        val veux = composeRule.onNodeWithText("veux").fetchSemanticsNode().boundsInRoot.center

        dragJe(by = { start -> veux - start })

        assertEquals(listOf(Triple("je", 1, 1)), moves)
    }
}

