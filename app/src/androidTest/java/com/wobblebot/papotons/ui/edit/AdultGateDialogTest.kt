package com.wobblebot.papotons.ui.edit

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wobblebot.papotons.R
import com.wobblebot.papotons.data.caregiver.CAREGIVER_CODE_LENGTH
import com.wobblebot.papotons.domain.Caregiver
import com.wobblebot.papotons.ui.settings.CaregiversUiState
import com.wobblebot.papotons.ui.theme.PapotonsTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Le câblage du contrôle, sans fichier ni Hilt : des accompagnants donnés, des réponses factices. */
@RunWith(AndroidJUnit4::class)
class AdultGateDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    // Ids asymétriques : un « premier de la liste » ou une inversion se voit.
    private val marie = Caregiver(7, "Marie", codeResetAtMs = null)
    private val paul = Caregiver(3, "Paul", codeResetAtMs = null)

    private val verified = mutableListOf<Pair<Long, String>>()
    private val recovered = mutableListOf<Pair<Long, String>>()
    private var passedCount = 0
    private var closedCount = 0

    private fun show(caregivers: List<Caregiver>?, isRightCode: Boolean = true, canWriteRecovered: Boolean = true) {
        composeRule.setContent {
            PapotonsTheme {
                AdultGateDialog(
                    state = CaregiversUiState(caregivers?.let { persistentListOf(*it.toTypedArray()) }),
                    onVerify = { id, code -> verified += id to code; isRightCode },
                    onRecover = { id, code -> recovered += id to code; canWriteRecovered },
                    onPassed = { passedCount++ },
                    onClose = { closedCount++ },
                )
            }
        }
    }

    private fun typeCode(code: String) =
        composeRule.onNodeWithText(string(R.string.caregiver_code, CAREGIVER_CODE_LENGTH)).performTextInput(code)

    private fun confirm() = composeRule.onNodeWithText(string(R.string.caregiver_gate_confirm)).performClick()

    /** La question de secours affichée, et sa bonne réponse. */
    private fun recoveryAnswer(): Int {
        val node = composeRule.onNodeWithText(" × ", substring = true).fetchSemanticsNode()
        val text = node.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().joinToString { it.text }
        val (first, second) = checkNotNull(Regex("""(\d+) × (\d+)""").find(text)).destructured
        return first.toInt() * second.toInt()
    }

    private fun goToNewCode() {
        composeRule.onNodeWithText(string(R.string.caregiver_gate_forgotten)).performClick()
        composeRule.onNodeWithText(string(R.string.caregiver_recovery_answer)).performTextInput("${recoveryAnswer()}")
        confirm()
    }

    @Test
    fun withoutCaregiverTheParentalQuestionComesBack() {
        show(emptyList())

        composeRule.onNodeWithText(string(R.string.parental_gate_title)).assertExists()
        composeRule.onNodeWithText(string(R.string.caregiver_gate_title)).assertDoesNotExist()
    }

    @Test
    fun nothingShowsWhileTheCaregiversAreUnknown() {
        show(null)

        composeRule.onNodeWithText(string(R.string.parental_gate_title)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.caregiver_gate_title)).assertDoesNotExist()
    }

    @Test
    fun withSeveralCaregiversNobodyIsChosenForThem() {
        show(listOf(marie, paul))
        typeCode("4721")

        composeRule.onNodeWithText(string(R.string.caregiver_gate_confirm)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.caregiver_gate_forgotten)).assertIsNotEnabled()
        composeRule.onNodeWithText("Paul").performClick()
        composeRule.onNodeWithText(string(R.string.caregiver_gate_confirm)).assertIsEnabled()
        confirm()

        composeRule.waitUntil { passedCount == 1 }
        assertEquals(listOf(paul.id to "4721"), verified)
    }

    @Test
    fun aWrongCodeClosesWithoutPassing() {
        show(listOf(marie), isRightCode = false)
        typeCode("0000")
        confirm()

        composeRule.waitUntil { closedCount == 1 }
        assertEquals(0, passedCount)
    }

    @Test
    fun aWrongRecoveryAnswerClosesWithoutChangingAnyCode() {
        show(listOf(marie))
        composeRule.onNodeWithText(string(R.string.caregiver_gate_forgotten)).performClick()
        composeRule.onNodeWithText(string(R.string.caregiver_recovery_answer)).performTextInput("${recoveryAnswer() + 1}")
        confirm()

        assertEquals(1, closedCount)
        assertEquals(0, passedCount)
        assertTrue(recovered.isEmpty())
    }

    /** Le code remis est celui de l'accompagnant choisi, jamais du premier de la liste. */
    @Test
    fun aRecoveredCodeGoesToTheChosenCaregiver() {
        show(listOf(marie, paul))
        composeRule.onNodeWithText("Paul").performClick()
        goToNewCode()
        composeRule.onNodeWithText(string(R.string.caregiver_code, CAREGIVER_CODE_LENGTH)).performTextInput("1357")
        composeRule.onNodeWithText(string(R.string.caregiver_code_again)).performTextInput("1357")
        confirm()

        composeRule.waitUntil { passedCount == 1 }
        assertEquals(listOf(paul.id to "1357"), recovered)
    }

    /** L'adulte a répondu : un disque plein ne doit pas le laisser dehors. */
    @Test
    fun aRecoveredCodeOpensEvenWhenItCannotBeWritten() {
        show(listOf(marie), canWriteRecovered = false)
        goToNewCode()
        composeRule.onNodeWithText(string(R.string.caregiver_code, CAREGIVER_CODE_LENGTH)).performTextInput("1357")
        composeRule.onNodeWithText(string(R.string.caregiver_code_again)).performTextInput("1357")
        confirm()

        composeRule.waitUntil { passedCount == 1 }
        assertEquals(listOf(marie.id to "1357"), recovered)
        assertEquals(0, closedCount)
    }
}
