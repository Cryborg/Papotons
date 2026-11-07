package com.wobblebot.papotons.ui.edit

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.ui.board.CellEditorState
import com.wobblebot.papotons.ui.board.EditTarget
import com.wobblebot.papotons.ui.board.FoundPictogram
import com.wobblebot.papotons.ui.board.PictogramSearchState
import com.wobblebot.papotons.ui.board.SearchStatus
import com.wobblebot.papotons.ui.theme.PapotonsTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Ce que TalkBack annonce de lui-même pendant une recherche de picto et pendant l'import d'une photo. */
@RunWith(AndroidJUnit4::class)
class EditorAnnouncementsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val announced = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    private fun show(state: PictogramSearchState) = composeRule.setContent {
        PapotonsTheme { PictogramSearchDialog(state = state, onSearch = {}, onPick = {}, onClose = {}) }
    }

    @Test
    fun theWaitIsAnnounced() {
        show(PictogramSearchState("eau", SearchStatus.Loading))

        composeRule.onNodeWithContentDescription(context.getString(R.string.search_loading)).assert(announced)
    }

    @Test
    fun theNumberOfPictogramsFoundIsAnnounced() {
        // Trois résultats : un pluriel, et un compte qui ne se confond pas avec un singulier.
        val results = persistentListOf(FoundPictogram(1, "eau"), FoundPictogram(2, "boire"), FoundPictogram(3, "verre"))
        show(PictogramSearchState("eau", SearchStatus.Done, results))

        val found = context.resources.getQuantityString(R.plurals.search_found, results.size, results.size)
        composeRule.onNodeWithText(found).assert(announced)
    }

    @Test
    fun aPhotoBeingPreparedIsAnnounced() {
        val cell = Cell(id = 4, row = 0, column = 1, label = "Mamie", linkedBoardId = null)
        composeRule.setContent {
            PapotonsTheme {
                CellEditorDialog(
                    state = CellEditorState(EditTarget.ExistingCell(boardId = 1, cell = cell), isImportingPhoto = true),
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

        composeRule.onNodeWithText(context.getString(R.string.editor_photo_importing)).assert(announced)
    }

    @Test
    fun theImageOfTheCellIsNamed() {
        val cell = Cell(id = 4, row = 0, column = 1, label = "Mamie", linkedBoardId = null, photoFile = "mamie.jpg")
        composeRule.setContent {
            PapotonsTheme {
                CellEditorDialog(
                    state = CellEditorState(EditTarget.ExistingCell(boardId = 1, cell = cell)),
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

        composeRule.onNodeWithContentDescription(context.getString(R.string.editor_preview_photo)).assertExists()
    }
}
