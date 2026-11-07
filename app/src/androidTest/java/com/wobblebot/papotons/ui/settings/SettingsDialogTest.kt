package com.wobblebot.papotons.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.domain.Caregiver
import com.wobblebot.papotons.domain.Profile
import com.wobblebot.papotons.ui.theme.PapotonsTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** La navigation des Réglages : une ligne ouvre sa page, le vrai retour système revient puis ferme. */
@RunWith(AndroidJUnit4::class)
class SettingsDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private var page by mutableStateOf(SettingsPage.Home)
    private var closedCount = 0

    private fun show(caregivers: ImmutableList<Caregiver>? = persistentListOf(), boardSize: BoardSize? = BoardSize.STANDARD, isBoardShown: Boolean = true) {
        composeRule.setContent {
            PapotonsTheme {
                SettingsDialog(
                    page = page,
                    content = SettingsContent(
                        boardSize = boardSize,
                        settingFailure = null,
                        profiles = ProfilesUiState(persistentListOf(Profile(1, "Léo"), Profile(2, "Anna")), openProfileId = 1),
                        caregivers = CaregiversUiState(caregivers),
                        hasUnsavedChanges = false,
                        isBoardShown = isBoardShown,
                    ),
                    profileActions = ProfileActions(onChosen = {}, onCreate = {}, onRename = { _, _ -> }, onDelete = {}),
                    caregiverActions = CaregiverActions(onSave = { _, _, _ -> }, onDelete = {}),
                    onPageChosen = { page = it },
                    onBoardSizeChosen = {},
                    onEditBoards = {},
                    onBackup = {},
                    onClose = { closedCount++ },
                )
            }
        }
    }

    @Test
    fun aRowOpensItsPageAndBackComesHomeThenCloses() {
        show()

        composeRule.onNodeWithText(string(R.string.settings_profiles)).performClick()
        assertEquals(SettingsPage.Profiles, page)
        composeRule.onNodeWithText(string(R.string.profile_add)).assertIsDisplayed()

        Espresso.pressBack()
        composeRule.waitForIdle()
        assertEquals(SettingsPage.Home, page)
        assertEquals(0, closedCount)

        Espresso.pressBack()
        composeRule.waitForIdle()
        assertEquals(1, closedCount)
    }

    /** La modale d'un utilisateur prend le retour d'abord : on reste sur la page Utilisateurs. */
    @Test
    fun backClosesADialogOpenedFromAPageWithoutLeavingThePage() {
        page = SettingsPage.Profiles
        show()

        composeRule.onNodeWithContentDescription(string(R.string.profile_edit_description, "Anna")).performClick()
        composeRule.onNodeWithText(string(R.string.profile_edit_title)).assertIsDisplayed()
        Espresso.pressBack()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.profile_edit_title)).assertDoesNotExist()
        assertEquals(SettingsPage.Profiles, page)
    }

    @Test
    fun theCaregiversSummarySaysNoneOnlyOnceTheListIsRead() {
        show(caregivers = null)
        composeRule.onNodeWithText(string(R.string.settings_caregivers)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.settings_caregivers_summary_none)).assertDoesNotExist()
    }

    @Test
    fun withoutCaregiverTheSummarySaysTheSettingsAreOnlyGuardedByASum() {
        show(caregivers = persistentListOf())
        composeRule.onNodeWithText(string(R.string.settings_caregivers_summary_none)).assertIsDisplayed()
    }

    @Test
    fun theHomeSaysWhoseGridsAreOpen() {
        show()
        composeRule.onNodeWithText(string(R.string.settings_section_grids_of, "Léo")).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.settings_open_profile)).assertIsDisplayed()
    }

    /** Grille pas encore lue : la taille n'est pas « non reconnue », elle est inconnue. */
    @Test
    fun theSizeIsNotCalledUnknownWhileNoGridIsShown() {
        show(boardSize = null, isBoardShown = false)
        composeRule.onNodeWithText(string(R.string.settings_board_size)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.settings_board_size_unknown)).assertDoesNotExist()
    }

    @Test
    fun aShownGridOutsideTheSizesIsCalledUnknown() {
        show(boardSize = null, isBoardShown = true)
        composeRule.onNodeWithText(string(R.string.settings_board_size_unknown)).assertIsDisplayed()
    }
}
