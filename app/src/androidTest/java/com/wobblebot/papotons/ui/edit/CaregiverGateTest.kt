package com.wobblebot.papotons.ui.edit

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
import com.wobblebot.papotons.data.caregiver.CAREGIVER_CODE_LENGTH
import com.wobblebot.papotons.ui.board.BoardViewModel
import com.wobblebot.papotons.ui.settings.CaregiversViewModel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val SPLASH_TIMEOUT_MS = 10_000L
private const val STORE_TIMEOUT_MS = 5_000L
private const val CODE = "4721"

/**
 * Dès qu'un accompagnant existe, la roue dentée demande son code, plus le calcul. L'accompagnant est
 * créé dans le vrai fichier de l'appli, puis retiré : EditEntryTest attend le calcul.
 */
@RunWith(AndroidJUnit4::class)
class CaregiverGateTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun string(id: Int) = composeRule.activity.getString(id)

    private val caregivers get() = ViewModelProvider(composeRule.activity)[CaregiversViewModel::class.java]

    private fun isSettingsOpen(): Boolean {
        composeRule.waitForIdle()
        return ViewModelProvider(composeRule.activity)[BoardViewModel::class.java].uiState.value.isSettingsOpen
    }

    @Before
    fun addCaregiver() {
        // Un test tué en cours a pu laisser un accompagnant : avec deux, rien n'est présélectionné.
        composeRule.waitUntil(STORE_TIMEOUT_MS) { caregivers.uiState.value.caregivers != null }
        removeCaregivers()
        composeRule.runOnUiThread { caregivers.onSave(null, "Marie", CODE) }
        composeRule.waitUntil(STORE_TIMEOUT_MS) { caregivers.uiState.value.caregivers?.isNotEmpty() == true && !caregivers.uiState.value.isWorking }
    }

    @After
    fun removeCaregivers() {
        for (caregiver in caregivers.uiState.value.caregivers.orEmpty()) {
            composeRule.runOnUiThread { caregivers.onDelete(caregiver.id) }
            // Pas de nouvelle action tant que la précédente tourne : le ViewModel l'ignorerait.
            composeRule.waitUntil(STORE_TIMEOUT_MS) {
                caregivers.uiState.value.caregivers?.none { it.id == caregiver.id } == true && !caregivers.uiState.value.isWorking
            }
        }
    }

    private fun openGate() {
        composeRule.waitUntil(SPLASH_TIMEOUT_MS) {
            try {
                composeRule.onNodeWithContentDescription(string(R.string.settings_open)).performClick()
                composeRule.onAllNodesWithText(string(R.string.caregiver_gate_title)).fetchSemanticsNodes().isNotEmpty()
            } catch (exception: IllegalStateException) {
                false
            }
        }
    }

    private fun typeCode(code: String) {
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.caregiver_code, CAREGIVER_CODE_LENGTH)).performTextInput(code)
        composeRule.onNodeWithText(string(R.string.caregiver_gate_confirm)).performClick()
        composeRule.waitUntil(STORE_TIMEOUT_MS) { composeRule.onAllNodesWithText(string(R.string.caregiver_gate_title)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun theGateAsksForTheCodeInsteadOfTheParentalQuestion() {
        openGate()

        composeRule.onNodeWithText(string(R.string.parental_gate_title)).assertDoesNotExist()
        assertFalse(isSettingsOpen())
    }

    @Test
    fun aWrongCodeClosesTheGate() {
        openGate()

        typeCode("0000")

        assertFalse(isSettingsOpen())
    }

    @Test
    fun theRightCodeOpensTheSettings() {
        openGate()

        typeCode(CODE)

        assertTrue(isSettingsOpen())
    }
}
