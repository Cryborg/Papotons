package com.wobblebot.papotons.ui.edit

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.ui.board.CellEditorState
import com.wobblebot.papotons.ui.board.EditTarget
import com.wobblebot.papotons.ui.theme.PapotonsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Le brouillon de l'éditeur, rangé sous son ouverture. */
@RunWith(AndroidJUnit4::class)
class CellEditorDraftTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val water = CellEditorState(EditTarget.ExistingCell(boardId = 1, cell = Cell(id = 4, row = 0, column = 1, label = "eau", linkedBoardId = null)))
    private val bread = CellEditorState(EditTarget.ExistingCell(boardId = 1, cell = Cell(id = 9, row = 1, column = 2, label = "pain", linkedBoardId = null)))

    /** L'éditeur ouvert, lu à chaque composition : une variable simple, qui ne recompose rien en changeant. */
    private var opened = water

    private fun show(tester: StateRestorationTester) = tester.setContent {
        PapotonsTheme {
            CellEditorDialog(
                state = opened,
                onSave = { _, _ -> },
                onDelete = {},
                onOpenFolder = {},
                onSearchPictogram = {},
                onRemovePictogram = {},
                onTakePhoto = null,
                onChoosePhoto = {},
                onClose = {},
            )
        }
    }

    private fun typeInLabel(text: String) =
        composeRule.onNodeWithText(context.getString(R.string.editor_label)).performTextReplacement(text)

    @Test
    fun theDraftSurvivesARotation() {
        val tester = StateRestorationTester(composeRule)
        show(tester)
        typeInLabel("eau fraîche")

        // Le ViewModel survit à la rotation : même ouverture.
        tester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("eau fraîche").assertExists()
    }

    @Test
    fun aDraftSavedBeforeTheProcessDiedIsNotTakenByAnotherCell() {
        val tester = StateRestorationTester(composeRule)
        show(tester)
        typeInLabel("eau fraîche")

        // Après la mort du processus, le ViewModel est neuf : la case ouverte ensuite est une autre ouverture.
        opened = bread
        tester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("pain").assertExists()
        composeRule.onNodeWithText("eau fraîche").assertDoesNotExist()
    }

    @Test
    fun aDraftSavedBeforeTheProcessDiedIsNotTakenWhenTheSameCellIsReopened() {
        val tester = StateRestorationTester(composeRule)
        show(tester)
        typeInLabel("eau fraîche")

        // Même case, ouverture neuve : la photo d'un brouillon d'avant la mort a pu être effacée au lancement.
        opened = CellEditorState(water.target)
        tester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("eau").assertExists()
        composeRule.onNodeWithText("eau fraîche").assertDoesNotExist()
    }
}
