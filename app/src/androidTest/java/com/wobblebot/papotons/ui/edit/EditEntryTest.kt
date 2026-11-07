package com.wobblebot.papotons.ui.edit

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wobblebot.papotons.MainActivity
import com.wobblebot.papotons.R
import com.wobblebot.papotons.ui.board.BoardViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val SPLASH_TIMEOUT_MS = 10_000L

/**
 * L'enfant n'entre pas en édition par hasard : « Modifier les grilles » passe par le contrôle
 * parental. Sur l'émulateur seulement : l'appli réelle se lance.
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

    private fun openGate() {
        // Le carton du studio couvre l'écran au lancement et prend les appuis : on insiste jusqu'à
        // ce que la modale « À propos » s'ouvre.
        composeRule.waitUntil(SPLASH_TIMEOUT_MS) {
            composeRule.onNodeWithContentDescription(string(R.string.about_open)).performClick()
            composeRule.onAllNodesWithText(string(R.string.edit_open)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(string(R.string.edit_open)).performClick()
        composeRule.onNodeWithText(string(R.string.parental_gate_title)).assertIsDisplayed()
    }

    /** La bonne réponse à la question affichée. */
    private fun answer(): Int {
        val node = composeRule.onNodeWithText(" + ", substring = true).fetchSemanticsNode()
        val text = node.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().joinToString { it.text }
        val (first, second) = checkNotNull(Regex("""(\d+) \+ (\d+)""").find(text)).destructured
        return first.toInt() + second.toInt()
    }

    @Test
    fun theEditButtonOpensTheGateNotTheEditMode() {
        openGate()

        assertFalse(isEditing())
    }

    @Test
    fun aWrongAnswerClosesTheGateWithoutEditing() {
        openGate()

        composeRule.onNodeWithText(string(R.string.parental_gate_answer)).performTextInput("${answer() + 1}")
        composeRule.onNodeWithText(string(R.string.parental_gate_confirm)).performClick()

        composeRule.onNodeWithText(string(R.string.parental_gate_title)).assertDoesNotExist()
        assertFalse(isEditing())
    }

    @Test
    fun theRightAnswerStartsTheEditMode() {
        openGate()

        composeRule.onNodeWithText(string(R.string.parental_gate_answer)).performTextInput("${answer()}")
        composeRule.onNodeWithText(string(R.string.parental_gate_confirm)).performClick()

        assertTrue(isEditing())
        composeRule.onNodeWithText(string(R.string.edit_banner)).assertIsDisplayed()
    }
}
