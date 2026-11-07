package com.wobblebot.papotons.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.BoardSize
import com.wobblebot.papotons.domain.Caregiver
import com.wobblebot.papotons.domain.Profile
import com.wobblebot.papotons.domain.Screen
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
    private val exitLockChanges = mutableListOf<Pair<Long, Boolean>>()
    private val leoAndAnna = persistentListOf(Profile(1, "Léo", isExitLocked = true), Profile(2, "Anna", isExitLocked = false))
    private val screenMoves = mutableListOf<Pair<Long, Int>>()
    private val deletedScreens = mutableListOf<Long>()
    private val createdScreens = mutableListOf<Pair<String, BoardSize>>()
    private val homeAndHouse = persistentListOf(Screen(1, "Accueil", BoardSize.STANDARD), Screen(1000, "Maison", BoardSize.LARGE_CELLS))
    private var shownScreens by mutableStateOf<ImmutableList<Screen>>(homeAndHouse)
    private var screensState by mutableStateOf(ScreensUiState())

    private fun show(
        caregivers: ImmutableList<Caregiver>? = persistentListOf(),
        screens: ImmutableList<Screen> = homeAndHouse,
        profiles: ProfilesUiState = ProfilesUiState(leoAndAnna, openProfileId = 1),
        isAppPinned: Boolean = false,
    ) {
        shownScreens = screens
        composeRule.setContent {
            PapotonsTheme {
                SettingsDialog(
                    page = page,
                    content = SettingsContent(
                        screens = shownScreens,
                        screensState = screensState,
                        newScreenSize = BoardSize.STANDARD,
                        profiles = profiles,
                        caregivers = CaregiversUiState(caregivers),
                        hasUnsavedChanges = false,
                        isAppPinned = isAppPinned,
                    ),
                    profileActions = ProfileActions(
                        onChosen = {},
                        onCreate = {},
                        onRename = { _, _ -> },
                        onDelete = {},
                        onExitLockChanged = { id, isLocked -> exitLockChanges += id to isLocked },
                    ),
                    caregiverActions = CaregiverActions(onSave = { _, _, _ -> }, onDelete = {}),
                    screenActions = ScreenActions(onCreate = { name, size -> createdScreens += name to size }, onRename = { _, _ -> }, onDelete = { deletedScreens += it }, onMove = { id, offset -> screenMoves += id to offset }),
                    onPageChosen = { page = it },
                    onEditBoards = {},
                    onBackup = {},
                    onClose = { closedCount++ },
                )
            }
        }
    }

    /** Anna, ouverte, n'est pas protégée ; Léo, premier de la liste, l'est : la ligne suit l'utilisatrice ouverte. */
    @Test
    fun theExitLockRowTogglesTheOpenProfile() {
        show(profiles = ProfilesUiState(leoAndAnna, openProfileId = 2))

        composeRule.onNodeWithText(string(R.string.settings_exit_lock)).assertIsOff().performClick()

        assertEquals(listOf(2L to true), exitLockChanges)
        // Anna n'est pas protégée : pas d'avertissement d'épinglage.
        composeRule.onNodeWithText(string(R.string.settings_exit_lock_not_pinned)).assertDoesNotExist()
    }

    /** Coché ne veut pas dire épinglé : l'adulte le lit sous l'option. */
    @Test
    fun aLockedProfileWhoseAppIsNotPinnedSaysSo() {
        show()

        composeRule.onNodeWithText(string(R.string.settings_exit_lock_not_pinned)).assertIsDisplayed()
    }

    @Test
    fun aPinnedAppHasNoWarning() {
        show(isAppPinned = true)

        composeRule.onNodeWithText(string(R.string.settings_exit_lock)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.settings_exit_lock_not_pinned)).assertDoesNotExist()
    }

    @Test
    fun theExitLockRowWaitsForTheOpenProfile() {
        show(profiles = ProfilesUiState(leoAndAnna, openProfileId = null))
        composeRule.onNodeWithText(string(R.string.settings_exit_lock)).assertDoesNotExist()
    }

    @Test
    fun theExitLockRowIsDisabledWhileAWriteRunsAndShowsItsFailure() {
        show(profiles = ProfilesUiState(leoAndAnna, openProfileId = 1, isWorking = true, failure = ProfileFailure.ExitLockNotSaved))

        composeRule.onNodeWithText(string(R.string.settings_exit_lock)).assertIsOn().assertIsNotEnabled().performClick()
        composeRule.onNodeWithText(string(R.string.profile_exit_lock_not_saved)).assertIsDisplayed()
        assertEquals(emptyList<Pair<Long, Boolean>>(), exitLockChanges)
    }

    /** Un échec de la page des utilisateurs reste sur sa page : l'accueil n'affiche que celui de l'option. */
    @Test
    fun theHomeShowsOnlyTheExitLockFailure() {
        show(profiles = ProfilesUiState(leoAndAnna, openProfileId = 1, failure = ProfileFailure.NotRenamed))

        composeRule.onNodeWithText(string(R.string.profile_not_renamed)).assertDoesNotExist()
    }

    /** L'échec de l'option a son message sur l'accueil, pas en double sur la page des utilisateurs. */
    @Test
    fun theProfilesPageLeavesTheExitLockFailureToTheHome() {
        page = SettingsPage.Profiles
        show(profiles = ProfilesUiState(leoAndAnna, openProfileId = 1, failure = ProfileFailure.ExitLockNotSaved))

        composeRule.onNodeWithText(string(R.string.profile_add)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.profile_exit_lock_not_saved)).assertDoesNotExist()
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

    @Test
    fun theScreensRowNamesTheScreensAndOpensTheirPage() {
        show()

        composeRule.onNodeWithText("Accueil, Maison").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.settings_screens)).performScrollTo().performClick()

        assertEquals(SettingsPage.Screens, page)
        composeRule.onNodeWithText(string(R.string.screen_add)).assertIsDisplayed()
    }

    /** Pas de « Monter » au premier, pas de « Descendre » au dernier : seuls les vrais voisins. */
    @Test
    fun theScreensPageMovesOnlyTowardARealNeighbor() {
        page = SettingsPage.Screens
        show()

        composeRule.onNodeWithContentDescription(string(R.string.screen_move_up_description, "Accueil")).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.screen_move_down_description, "Maison")).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.screen_move_up_description, "Maison")).performClick()

        assertEquals(listOf(1000L to -1), screenMoves)
        composeRule.onNodeWithText(string(R.string.screen_first)).assertIsDisplayed()
    }

    /** Après « Monter », les flèches attendent le nouvel ordre : un double appui ne vise pas la ligne voisine. */
    @Test
    fun theArrowsWaitForTheNewOrderAfterAMove() {
        page = SettingsPage.Screens
        show()
        val upHouse = string(R.string.screen_move_up_description, "Maison")

        composeRule.onNodeWithContentDescription(upHouse).performClick()
        composeRule.onNodeWithContentDescription(upHouse).assertIsNotEnabled().performClick()
        assertEquals(listOf(1000L to -1), screenMoves)

        shownScreens = persistentListOf(homeAndHouse[1], homeAndHouse[0])
        val downHouse = string(R.string.screen_move_down_description, "Maison")
        composeRule.onNodeWithContentDescription(downHouse).assertIsEnabled().performClick()

        assertEquals(listOf(1000L to -1, 1000L to 1), screenMoves)
    }

    /** Un déplacement refusé ne change pas l'ordre : les flèches se rallument pour réessayer. */
    @Test
    fun theArrowsComeBackAfterAFailedMove() {
        page = SettingsPage.Screens
        show()
        val upHouse = string(R.string.screen_move_up_description, "Maison")
        composeRule.onNodeWithContentDescription(upHouse).performClick()
        composeRule.onNodeWithContentDescription(upHouse).assertIsNotEnabled()

        screensState = ScreensUiState(failure = ScreenFailure.NotMoved)

        composeRule.onNodeWithContentDescription(upHouse).assertIsEnabled()
        // Deuxième échec identique : l'état ne change pas d'une image à l'autre, les flèches restent là.
        composeRule.onNodeWithContentDescription(upHouse).performClick()
        composeRule.onNodeWithContentDescription(upHouse).assertIsEnabled()
    }

    /** Supprimer un écran passe par une confirmation : « Annuler » ne supprime rien. */
    @Test
    fun theDeletionOfAScreenWaitsForConfirmation() {
        page = SettingsPage.Screens
        show()
        val confirmText = string(R.string.screen_delete_confirm_text)

        composeRule.onNodeWithContentDescription(string(R.string.screen_edit_description, "Maison")).performClick()
        composeRule.onNodeWithText(string(R.string.screen_delete)).performClick()
        composeRule.onNodeWithText(confirmText).assertIsDisplayed()
        assertEquals(emptyList<Long>(), deletedScreens)

        composeRule.onNodeWithText(string(R.string.screen_cancel)).performClick()
        assertEquals(emptyList<Long>(), deletedScreens)
        composeRule.onNodeWithText(string(R.string.screen_delete)).performClick()
        composeRule.onNodeWithText(confirmText).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.screen_delete)).performClick()

        assertEquals(listOf(1000L), deletedScreens)
    }

    /** La taille choisie dans la modale arrive à la création, pas la présélection ; Entrée ne crée rien. */
    @Test
    fun aNewScreenIsCreatedWithTheChosenSizeOnlyByCreate() {
        page = SettingsPage.Screens
        show()

        composeRule.onNodeWithText(string(R.string.screen_add)).performClick()
        composeRule.onNodeWithText(string(R.string.screen_name)).performTextInput("École")
        composeRule.onNodeWithText(string(R.string.screen_name)).performImeAction()
        assertEquals(emptyList<Pair<String, BoardSize>>(), createdScreens)
        // La carte de la modale, pas la ligne « Maison, Grandes cases » de la liste derrière elle.
        composeRule.onNode(hasText(string(R.string.board_size_large_cells)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performScrollTo().performClick()
        composeRule.onNodeWithText(string(R.string.screen_create)).performClick()

        assertEquals(listOf("École" to BoardSize.LARGE_CELLS), createdScreens)
    }

    /** Il faut un écran pour parler : le dernier ne propose pas « Supprimer ». */
    @Test
    fun theLastScreenCannotBeDeleted() {
        page = SettingsPage.Screens
        show(screens = persistentListOf(Screen(1, "Accueil", BoardSize.STANDARD)))

        composeRule.onNodeWithContentDescription(string(R.string.screen_edit_description, "Accueil")).performClick()

        composeRule.onNodeWithText(string(R.string.screen_edit_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.screen_delete)).assertDoesNotExist()
    }
}
