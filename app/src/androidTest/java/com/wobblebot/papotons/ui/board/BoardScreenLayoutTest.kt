package com.wobblebot.papotons.ui.board

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.layoutGrid
import com.wobblebot.papotons.speech.SpeechStatus
import com.wobblebot.papotons.ui.theme.PapotonsTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val LARGE_FONT_SCALE = 2f

/**
 * Invariant 3 à l'écran : ce qui change autour de la grille (alerte de voix, message,
 * boutons de navigation, police agrandie, langue écrite de droite à gauche) ne déplace aucune case.
 */
@RunWith(AndroidJUnit4::class)
class BoardScreenLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cells = listOf(
        Cell(id = 1, row = 0, column = 0, label = "je", linkedBoardId = null),
        Cell(id = 2, row = 1, column = 2, label = "Manger", linkedBoardId = 2),
    )
    private val homeState = BoardUiState(
        // Un nom long, qui se couperait s'il pouvait faire grandir la barre.
        board = DisplayedBoard(id = 1, name = "Accueil de la grille principale de tous les jours", grid = layoutGrid(2, 3, cells), canGoBack = false),
        message = persistentListOf(),
        speechStatus = SpeechStatus.Ready,
    )
    private val noActions = BoardActions({}, {}, {}, {}, {}, {}, {})

    private fun cellBounds(label: String) = composeRule.onNodeWithText(label).getBoundsInRoot()

    private fun assertCellsStayPut(fontScale: Float) {
        var state by mutableStateOf(homeState)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PapotonsTheme { BoardScreen(state = state, actions = noActions) }
            }
        }
        val original = cellBounds("Manger")

        state = homeState.copy(speechStatus = SpeechStatus.Unavailable)
        composeRule.onNodeWithText(context.getString(R.string.speech_unavailable)).assertIsDisplayed()
        assertEquals("voice alert", original, cellBounds("Manger"))

        state = homeState.copy(message = persistentListOf("oui", "non", "encore", "aide", "fini", "pas", "tu"))
        composeRule.onNodeWithContentDescription("oui non encore aide fini pas tu").assertIsDisplayed()
        assertEquals("long message", original, cellBounds("Manger"))

        state = homeState.copy(board = checkNotNull(homeState.board).copy(canGoBack = true))
        composeRule.onNodeWithContentDescription(context.getString(R.string.board_back)).assertIsDisplayed()
        assertEquals("navigation buttons", original, cellBounds("Manger"))
    }

    @Test
    fun cellsKeepTheirBoundsWhateverChangesAroundTheGrid() {
        assertCellsStayPut(fontScale = 1f)
    }

    @Test
    fun cellsKeepTheirBoundsWithALargeSystemFont() {
        assertCellsStayPut(fontScale = LARGE_FONT_SCALE)
    }

    @Test
    fun columnsStayLeftToRightInARightToLeftLanguage() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                PapotonsTheme { BoardScreen(state = homeState, actions = noActions) }
            }
        }

        assertTrue(cellBounds("je").left < cellBounds("Manger").left)
    }
}
