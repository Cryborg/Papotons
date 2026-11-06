package com.wobblebot.papotons.ui.board

import android.graphics.BitmapFactory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.SentenceToken
import com.wobblebot.papotons.domain.WordCategory
import com.wobblebot.papotons.domain.layoutGrid
import com.wobblebot.papotons.speech.SpeechStatus
import com.wobblebot.papotons.ui.theme.PapotonsTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val LARGE_FONT_SCALE = 2f
// Cases larges et basses : le libellé n'y est plus limité par la largeur, c'est là qu'il pourrait
// prendre la place du picto.
private const val WIDE_CELL_ROWS = 7
private const val WIDE_CELL_COLUMNS = 2
// Cases plus de deux fois plus larges que hautes, comme l'accueil sur une tablette en paysage.
private const val FLAT_CELL_ROWS = 12
// Le réglage de police par défaut d'une tablette Samsung.
private const val TABLET_FONT_SCALE = 1.15f
private const val BUNDLED_PICTOGRAM_ID = 6632L
private const val MIN_PICTOGRAM_SHARE = 0.4f
private const val CENTER_TOLERANCE_DP = 2f

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
    private val noActions = BoardActions({}, {}, {}, {}, {}, {}, {}, {})

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

        // Des mots avec un picto, deux (élision) ou aucun : la barre ne grandit pour aucun d'eux.
        val words = listOf("oui", "non", "encore", "aide", "fini", "pas", "tu")
        val message = words.map { SentenceToken(it, persistentListOf(BUNDLED_PICTOGRAM_ID)) } +
            SentenceToken("j'ai", persistentListOf(BUNDLED_PICTOGRAM_ID, BUNDLED_PICTOGRAM_ID)) +
            SentenceToken("vite", persistentListOf())
        state = homeState.copy(message = message.toImmutableList())
        composeRule.onNodeWithContentDescription("oui non encore aide fini pas tu j'ai vite").assertIsDisplayed()
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
    fun aPictogramKeepsItsShareOfAWideShortCellWithALargeFont() {
        // Cases larges et basses, police à 200 % : le libellé n'est plus limité par la largeur et sa
        // taille adaptative prendrait toute la hauteur si rien ne réservait la part du picto.
        val image = context.assets.open("pictograms/$BUNDLED_PICTOGRAM_ID.png").use(BitmapFactory::decodeStream).asImageBitmap()
        val cells = (0 until WIDE_CELL_ROWS * WIDE_CELL_COLUMNS).map { index ->
            Cell(
                id = index.toLong(),
                row = index / WIDE_CELL_COLUMNS,
                column = index % WIDE_CELL_COLUMNS,
                label = "mot$index",
                linkedBoardId = null,
                pictogramId = BUNDLED_PICTOGRAM_ID,
                category = WordCategory.VERB,
            )
        }
        val state = homeState.copy(board = DisplayedBoard(id = 1, name = "Accueil", grid = layoutGrid(WIDE_CELL_ROWS, WIDE_CELL_COLUMNS, cells), canGoBack = false))
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, LARGE_FONT_SCALE),
                LocalPictogramImages provides { image },
            ) {
                PapotonsTheme { BoardScreen(state = state, actions = noActions) }
            }
        }

        val cell = cellBounds("mot0")
        val pictogram = composeRule.onAllNodesWithTag(PICTOGRAM_TEST_TAG, useUnmergedTree = true)[0].getBoundsInRoot()
        // Le texte lui-même, pas la case cliquable qui le fusionne : il doit garder sa place aussi.
        val label = composeRule.onNodeWithText("mot0", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(
            "pictogram ${pictogram.height} in cell ${cell.height}",
            pictogram.height.value >= cell.height.value * MIN_PICTOGRAM_SHARE,
        )
        assertTrue("label ${label.height}", label.height.value > 0f && label.bottom <= cell.bottom)
    }

    @Test
    fun aLabelShrinksToTheHeightOfAFlatCell() {
        val cells = (0 until FLAT_CELL_ROWS * WIDE_CELL_COLUMNS).map { index ->
            Cell(
                id = index.toLong(),
                row = index / WIDE_CELL_COLUMNS,
                column = index % WIDE_CELL_COLUMNS,
                // Des jambages (g, j, p) : ce sont eux qui sortaient de la case.
                label = if (index == 0) "manger" else "jeu$index",
                linkedBoardId = null,
                pictogramId = BUNDLED_PICTOGRAM_ID,
                category = WordCategory.VERB,
            )
        }
        val state = homeState.copy(board = DisplayedBoard(id = 1, name = "Accueil", grid = layoutGrid(FLAT_CELL_ROWS, WIDE_CELL_COLUMNS, cells), canGoBack = false))
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, TABLET_FONT_SCALE)) {
                PapotonsTheme { BoardScreen(state = state, actions = noActions) }
            }
        }

        val layouts = mutableListOf<TextLayoutResult>()
        val getLayout = composeRule.onNodeWithText("manger", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
        checkNotNull(getLayout.action)(layouts)

        assertFalse("text of ${layouts.single().multiParagraph.height} px in ${layouts.single().size}", layouts.single().hasVisualOverflow)
    }

    @Test
    fun aCellDoesNotMoveWhenItsPictogramArrives() {
        val image = context.assets.open("pictograms/$BUNDLED_PICTOGRAM_ID.png").use(BitmapFactory::decodeStream).asImageBitmap()
        val withPictogram = homeState.copy(
            board = checkNotNull(homeState.board).copy(
                grid = layoutGrid(2, 3, cells.map { it.copy(pictogramId = BUNDLED_PICTOGRAM_ID, category = WordCategory.NOUN) }),
            ),
        )
        var isDecoded by mutableStateOf(false)
        composeRule.setContent {
            CompositionLocalProvider(LocalPictogramImages provides { if (isDecoded) image else null }) {
                PapotonsTheme { BoardScreen(state = withPictogram, actions = noActions) }
            }
        }
        // Le libellé, pas la case : la case a la même taille quoi qu'elle contienne.
        fun labelBounds() = composeRule.onNodeWithText("Manger", useUnmergedTree = true).getBoundsInRoot()
        val beforeDecoding = labelBounds()

        isDecoded = true

        composeRule.onAllNodesWithTag(PICTOGRAM_TEST_TAG, useUnmergedTree = true)[0].assertIsDisplayed()
        assertEquals(beforeDecoding, labelBounds())
    }

    @Test
    fun aFolderMarkNeverCoversItsPictogram() {
        val image = context.assets.open("pictograms/$BUNDLED_PICTOGRAM_ID.png").use(BitmapFactory::decodeStream).asImageBitmap()
        val folder = Cell(id = 1, row = 0, column = 0, label = "Manger", linkedBoardId = 2, pictogramId = BUNDLED_PICTOGRAM_ID, category = WordCategory.NOUN)
        val state = homeState.copy(board = checkNotNull(homeState.board).copy(grid = layoutGrid(2, 3, listOf(folder))))
        composeRule.setContent {
            CompositionLocalProvider(LocalPictogramImages provides { image }) {
                PapotonsTheme { BoardScreen(state = state, actions = noActions) }
            }
        }

        val mark = composeRule.onNodeWithTag(FOLDER_MARK_TEST_TAG, useUnmergedTree = true).getBoundsInRoot()
        val pictogram = composeRule.onNodeWithTag(PICTOGRAM_TEST_TAG, useUnmergedTree = true).getBoundsInRoot()

        assertTrue("mark ends at ${mark.bottom}, pictogram starts at ${pictogram.top}", mark.bottom <= pictogram.top)
    }

    @Test
    fun aTextOnlyLabelIsCenteredInItsCell() {
        composeRule.setContent { PapotonsTheme { BoardScreen(state = homeState, actions = noActions) } }

        val cell = cellBounds("je")
        val label = composeRule.onNodeWithText("je", useUnmergedTree = true).getBoundsInRoot()

        assertEquals((cell.left + cell.right).value / 2, (label.left + label.right).value / 2, CENTER_TOLERANCE_DP)
        assertEquals((cell.top + cell.bottom).value / 2, (label.top + label.bottom).value / 2, CENTER_TOLERANCE_DP)
    }

    @Test
    fun aMessageWordIsCenteredUnderItsPictogram() {
        val image = context.assets.open("pictograms/$BUNDLED_PICTOGRAM_ID.png").use(BitmapFactory::decodeStream).asImageBitmap()
        // Un mot plus large que son picto : c'est le cas où un alignement à gauche se verrait.
        val state = homeState.copy(message = persistentListOf(SentenceToken("aujourd'hui", persistentListOf(BUNDLED_PICTOGRAM_ID))))
        composeRule.setContent {
            CompositionLocalProvider(LocalPictogramImages provides { image }) {
                PapotonsTheme { BoardScreen(state = state, actions = noActions) }
            }
        }

        val pictogram = composeRule.onNodeWithTag(PICTOGRAM_TEST_TAG, useUnmergedTree = true).getBoundsInRoot()
        val label = composeRule.onNodeWithText("aujourd'hui", useUnmergedTree = true).getBoundsInRoot()

        assertTrue("pictogram ${pictogram.height}", pictogram.height.value > 0f)
        // Sinon un mot aligné à gauche serait aussi « centré » : le test ne verrait rien.
        assertTrue("label ${label.width}, pictogram ${pictogram.width}", label.width > pictogram.width)
        assertTrue("label starts at ${label.top}, pictogram ends at ${pictogram.bottom}", pictogram.bottom <= label.top)
        assertEquals((pictogram.left + pictogram.right).value / 2, (label.left + label.right).value / 2, CENTER_TOLERANCE_DP)
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
