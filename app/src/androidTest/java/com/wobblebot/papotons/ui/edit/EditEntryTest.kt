package com.wobblebot.papotons.ui.edit

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wobblebot.papotons.MainActivity
import com.wobblebot.papotons.R
import com.wobblebot.papotons.ui.board.BoardViewModel
import com.wobblebot.papotons.domain.BoardSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val SPLASH_TIMEOUT_MS = 10_000L

/** L'accueil des grandes cases, tel que le vocabulaire de base le dessine. */
private const val LARGE_CELLS_ROWS = 3
private const val LARGE_CELLS_COLUMNS = 4

/**
 * L'enfant n'entre ni dans les Réglages ni en édition par hasard : la roue dentée passe par le
 * contrôle parental. Sur l'émulateur seulement : l'appli réelle se lance.
 */
@RunWith(AndroidJUnit4::class)
class EditEntryTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun isEditing(): Boolean {
        composeRule.waitForIdle()
        return ViewModelProvider(composeRule.activity)[BoardViewModel::class.java].uiState.value.isEditing
    }

    private fun isSettingsOpen(): Boolean {
        composeRule.waitForIdle()
        return ViewModelProvider(composeRule.activity)[BoardViewModel::class.java].uiState.value.isSettingsOpen
    }

    private fun openGate() {
        // Le carton du studio couvre l'écran au lancement et prend les appuis, et l'écran de démarrage
        // du système retarde l'interface (aucune hiérarchie Compose : exception) : on insiste jusqu'à
        // ce que le contrôle s'ouvre.
        composeRule.waitUntil(SPLASH_TIMEOUT_MS) {
            try {
                composeRule.onNodeWithContentDescription(string(R.string.settings_open)).performClick()
                composeRule.onAllNodesWithText(string(R.string.parental_gate_title)).fetchSemanticsNodes().isNotEmpty()
            } catch (exception: IllegalStateException) {
                false
            }
        }
    }

    /** La bonne réponse à la question affichée. */
    private fun answer(): Int {
        val node = composeRule.onNodeWithText(" + ", substring = true).fetchSemanticsNode()
        val text = node.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().joinToString { it.text }
        val (first, second) = checkNotNull(Regex("""(\d+) \+ (\d+)""").find(text)).destructured
        return first.toInt() + second.toInt()
    }

    @Test
    fun theSettingsButtonOpensTheGateNotTheSettings() {
        openGate()

        assertFalse(isSettingsOpen())
        assertFalse(isEditing())
    }

    @Test
    fun aWrongAnswerClosesTheGateWithoutEditing() {
        openGate()

        composeRule.onNodeWithText(string(R.string.parental_gate_answer)).performTextInput("${answer() + 1}")
        composeRule.onNodeWithText(string(R.string.parental_gate_confirm)).performClick()

        composeRule.onNodeWithText(string(R.string.parental_gate_title)).assertDoesNotExist()
        assertFalse(isSettingsOpen())
        assertFalse(isEditing())
    }

    private fun openSettings() {
        openGate()
        composeRule.onNodeWithText(string(R.string.parental_gate_answer)).performTextInput("${answer()}")
        composeRule.onNodeWithText(string(R.string.parental_gate_confirm)).performClick()
        assertTrue(isSettingsOpen())
    }

    @Test
    fun whileEditingTheGearOpensTheSettingsWithoutTheGate() {
        openSettings()
        composeRule.onNodeWithText(string(R.string.edit_open)).performClick()
        assertTrue(isEditing())

        composeRule.onNodeWithContentDescription(string(R.string.settings_open)).performClick()

        composeRule.onNodeWithText(string(R.string.parental_gate_title)).assertDoesNotExist()
        assertTrue(isSettingsOpen())
    }

    @Test
    fun theBackupEntryOpensTheBackupInEditMode() {
        openSettings()

        composeRule.onNodeWithText(string(R.string.backup_open)).performScrollTo().performClick()

        assertTrue(isEditing())
        assertFalse(isSettingsOpen())
        composeRule.onNodeWithText(string(R.string.backup_title)).assertIsDisplayed()
    }

    /** Invariant 5 : le crédit ARASAAC reste dans l'appli, dans Réglages > À propos. */
    @Test
    fun theAboutEntryShowsTheArasaacCredit() {
        openSettings()

        composeRule.onNodeWithText(string(R.string.about_open)).performScrollTo().performClick()

        composeRule.onNodeWithText(string(R.string.about_arasaac_credit)).assertIsDisplayed()
    }

    @Test
    fun choosingTheLargeCellsOpensTheirHome() {
        openSettings()

        try {
            // Dans sa sous-page, depuis l'accueil des Réglages.
            composeRule.onNodeWithText(string(R.string.settings_board_size)).performScrollTo().performClick()
            composeRule.onNodeWithText(string(R.string.board_size_large_cells)).performScrollTo().performClick()
            composeRule.waitUntil(SPLASH_TIMEOUT_MS) { board()?.id == BoardSize.LARGE_CELLS.homeBoardId }
            assertEquals(LARGE_CELLS_ROWS, board()?.grid?.size)
            assertEquals(LARGE_CELLS_COLUMNS, board()?.grid?.first()?.size)
        } finally {
            // Remise en standard, même en échec : l'émulateur garde ses données d'un test à l'autre.
            ViewModelProvider(composeRule.activity)[BoardViewModel::class.java].onBoardSizeChosen(BoardSize.STANDARD)
            composeRule.waitUntil(SPLASH_TIMEOUT_MS) { board()?.id == BoardSize.STANDARD.homeBoardId }
        }
    }

    private fun board() = ViewModelProvider(composeRule.activity)[BoardViewModel::class.java].uiState.value.board

    @Test
    fun theRightAnswerOpensTheSettingsThenTheEditMode() {
        openGate()

        composeRule.onNodeWithText(string(R.string.parental_gate_answer)).performTextInput("${answer()}")
        composeRule.onNodeWithText(string(R.string.parental_gate_confirm)).performClick()

        assertTrue(isSettingsOpen())
        assertFalse(isEditing())
        composeRule.onNodeWithText(string(R.string.edit_open)).performClick()

        assertTrue(isEditing())
        assertFalse(isSettingsOpen())
        composeRule.onNodeWithText(string(R.string.edit_banner)).assertIsDisplayed()
    }
}
