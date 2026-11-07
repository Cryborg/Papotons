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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.Picture
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
// Plus de mots qu'il n'en tient dans la phrase, même sur tablette.
private const val LONG_SENTENCE_WORD_COUNT = 30
private const val BUNDLED_PICTOGRAM_ID = 6632L
private val BUNDLED_PICTURE = Picture.Pictogram(BUNDLED_PICTOGRAM_ID)
private const val MIN_PICTOGRAM_SHARE = 0.4f
private const val CENTER_TOLERANCE_DP = 2f
// Un peu au-dessus du seuil où l'alerte de voix s'écrit (WRITTEN_ALERT_MIN_BAR_WIDTH, plus marges et insets).
private val WIDE_BAR_WIDTH = 800.dp
private const val WIDTH_TOLERANCE_DP = 0.01f
private val WIDE_BAR_HEIGHT = 900.dp

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
        board = DisplayedBoard(id = 1, name = "Accueil", grid = layoutGrid(2, 3, cells), canGoBack = false),
        message = persistentListOf(),
        speechStatus = SpeechStatus.Ready,
    )
    private val noActions = BoardActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {})

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
        // Écrite sur un écran large, réduite à son icône sur un écran étroit.
        val alertLabel = context.getString(R.string.speech_unavailable)
        composeRule.onNode(hasText(alertLabel) or hasContentDescription(alertLabel)).assertIsDisplayed()
        assertEquals("voice alert", original, cellBounds("Manger"))

        // Des mots avec un picto, deux (élision) ou aucun : la barre ne grandit pour aucun d'eux.
        val words = listOf("oui", "non", "encore", "aide", "fini", "pas", "tu")
        val message = words.map { SentenceToken(it, persistentListOf(BUNDLED_PICTURE)) } +
            SentenceToken("j'ai", persistentListOf(BUNDLED_PICTURE, BUNDLED_PICTURE)) +
            SentenceToken("vite", persistentListOf())
        state = homeState.copy(message = message.toImmutableList())
        composeRule.onNodeWithContentDescription("oui non encore aide fini pas tu j'ai vite").assertIsDisplayed()
        assertEquals("long message", original, cellBounds("Manger"))

        state = homeState.copy(board = checkNotNull(homeState.board).copy(canGoBack = true))
        composeRule.onNodeWithContentDescription(context.getString(R.string.board_back)).assertIsDisplayed()
        assertEquals("navigation buttons", original, cellBounds("Manger"))
        // Mode édition : le bandeau remplace la phrase dans la même barre, les emplacements vides
        // deviennent des « + », et aucune case ne bouge.
        state = homeState.copy(isEditing = true)
        composeRule.onNodeWithContentDescription(context.getString(R.string.edit_add_cell_description, 1, 2)).assertIsDisplayed()
        assertEquals("edit mode", original, cellBounds("Manger"))

        // Toutes les alertes à la fois : une seule tient en barre étroite, toutes en barre large.
        state = homeState.copy(speechStatus = SpeechStatus.LanguageMissing, isMuted = true, hasLostBoards = true)
        composeRule.onNodeWithContentDescription(context.getString(R.string.volume_muted_alert)).assertIsDisplayed()
        assertEquals("every alert", original, cellBounds("Manger"))
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
        val state = homeState.copy(message = persistentListOf(SentenceToken("aujourd'hui", persistentListOf(BUNDLED_PICTURE))))
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
    fun theBoardNameFollowsTheOpenedBoardForTalkBack() {
        var state by mutableStateOf(homeState)
        composeRule.setContent { PapotonsTheme { BoardScreen(state = state, actions = noActions) } }
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Accueil")).assertExists()

        state = homeState.copy(board = checkNotNull(homeState.board).copy(name = "Nourriture", canGoBack = true))

        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Nourriture")).assertExists()
    }

    @Test
    fun theLastWordStaysWhollyVisibleWhenTheSentenceNarrows() {
        val words = (0 until LONG_SENTENCE_WORD_COUNT).map { SentenceToken("mot$it", persistentListOf()) }
        val lastWord = "mot${LONG_SENTENCE_WORD_COUNT - 1}"
        var state by mutableStateOf(homeState.copy(message = words.toImmutableList()))
        composeRule.setContent { PapotonsTheme { BoardScreen(state = state, actions = noActions) } }
        fun lastWordBounds() = composeRule.onNodeWithText(lastWord, useUnmergedTree = true).getBoundsInRoot()
        val wholeWord = lastWordBounds()

        // Ouvrir un dossier fait apparaître Retour et Accueil, qui prennent de la largeur à la phrase.
        state = state.copy(board = checkNotNull(homeState.board).copy(canGoBack = true))
        composeRule.waitForIdle()

        assertEquals(wholeWord.width, lastWordBounds().width)
    }

    @Test
    fun aNarrowBarKeepsEveryButtonReachable() {
        // Le pire cas en largeur sur l'émulateur (411 dp) : dossier ouvert, voix en panne, phrase longue.
        val words = (0 until LONG_SENTENCE_WORD_COUNT).map { SentenceToken("mot$it", persistentListOf()) }
        val state = homeState.copy(
            board = checkNotNull(homeState.board).copy(canGoBack = true),
            message = words.toImmutableList(),
            speechStatus = SpeechStatus.Unavailable,
        )
        composeRule.setContent { PapotonsTheme { BoardScreen(state = state, actions = noActions) } }

        val deleteLast = composeRule.onNodeWithContentDescription(context.getString(R.string.message_delete_last)).getBoundsInRoot()
        val clear = composeRule.onNodeWithContentDescription(context.getString(R.string.message_clear)).getBoundsInRoot()
        val alertLabel = context.getString(R.string.speech_unavailable)
        val alert = composeRule.onNode(hasText(alertLabel) or hasContentDescription(alertLabel)).getBoundsInRoot()
        val about = composeRule.onNodeWithContentDescription(context.getString(R.string.about_open)).getBoundsInRoot()

        // Chevauchés, l'appui sur « Effacer le dernier mot » effacerait toute la phrase.
        assertTrue("delete ends at ${deleteLast.right}, clear starts at ${clear.left}", deleteLast.right <= clear.left)
        val speak = composeRule.onNodeWithContentDescription(context.getString(R.string.message_speak)).getBoundsInRoot()
        assertTrue("alert ends at ${alert.right}, speak starts at ${speak.left}", alert.right <= speak.left)
        assertTrue("clear ends at ${clear.right}, about starts at ${about.left}", clear.right <= about.left)
        assertEquals(clear.width, deleteLast.width)
    }

    @Test
    fun theMutedAlertRaisesTheVolume() {
        var raiseCount = 0
        val actions = noActions.copy(onMutedAlertClick = { raiseCount++ })
        composeRule.setContent { PapotonsTheme { BoardScreen(state = homeState.copy(isMuted = true), actions = actions) } }

        composeRule.onNodeWithContentDescription(context.getString(R.string.volume_muted_alert)).performClick()

        assertEquals(1, raiseCount)
    }

    @Test
    fun anAlertThatComesOrGoesNeverMovesTheMessageButtons() {
        // Son coupé détecté en disant la phrase, puis remonté par l'appui : le second appui au même
        // endroit ne doit ni effacer un mot ni vider la phrase.
        var state by mutableStateOf(homeState.copy(message = persistentListOf(SentenceToken("je", persistentListOf()))))
        composeRule.setContent { PapotonsTheme { BoardScreen(state = state, actions = noActions) } }
        fun buttons() = listOf(R.string.message_speak, R.string.message_delete_last, R.string.message_clear, R.string.about_open)
            .map { composeRule.onNodeWithContentDescription(context.getString(it)).getBoundsInRoot() }
        val withoutAlert = buttons()

        state = state.copy(isMuted = true)
        composeRule.onNodeWithContentDescription(context.getString(R.string.volume_muted_alert)).assertIsDisplayed()
        assertEquals("alert appearing", withoutAlert, buttons())

        state = state.copy(isMuted = false)
        composeRule.waitForIdle()
        assertEquals("alert vanishing", withoutAlert, buttons())
    }

    @Test
    fun aNarrowBarWithEveryAlertShowsOnlyTheMostImportant() {
        // L'émulateur fait 411 dp : barre étroite. Dossier ouvert, phrase longue, toutes les alertes.
        val words = (0 until LONG_SENTENCE_WORD_COUNT).map { SentenceToken("mot$it", persistentListOf()) }
        val state = homeState.copy(
            board = checkNotNull(homeState.board).copy(canGoBack = true),
            message = words.toImmutableList(),
            speechStatus = SpeechStatus.LanguageMissing,
            isMuted = true,
            hasLostBoards = true,
        )
        composeRule.setContent { PapotonsTheme { BoardScreen(state = state, actions = noActions) } }

        val clear = composeRule.onNodeWithContentDescription(context.getString(R.string.message_clear)).getBoundsInRoot()
        val muted = composeRule.onNodeWithContentDescription(context.getString(R.string.volume_muted_alert)).getBoundsInRoot()
        val about = composeRule.onNodeWithContentDescription(context.getString(R.string.about_open)).getBoundsInRoot()
        composeRule.onNodeWithContentDescription(context.getString(R.string.lost_boards_alert)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.speech_language_missing)).assertDoesNotExist()

        val speak = composeRule.onNodeWithContentDescription(context.getString(R.string.message_speak)).getBoundsInRoot()
        assertTrue("muted alert ends at ${muted.right}, speak starts at ${speak.left}", muted.right <= speak.left)
        assertTrue("clear ends at ${clear.right}, about starts at ${about.left}", clear.right <= about.left)
    }

    @Test
    fun aWideBarWithEveryAlertKeepsEveryButtonReachable() {
        // Le pire cas en barre large : à peine assez pour écrire l'alerte de voix, dossier ouvert,
        // phrase longue, voix française manquante, son coupé, grilles perdues.
        val words = (0 until LONG_SENTENCE_WORD_COUNT).map { SentenceToken("mot$it", persistentListOf()) }
        val state = homeState.copy(
            board = checkNotNull(homeState.board).copy(canGoBack = true),
            message = words.toImmutableList(),
            speechStatus = SpeechStatus.LanguageMissing,
            isMuted = true,
            hasLostBoards = true,
        )
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(WIDE_BAR_WIDTH, WIDE_BAR_HEIGHT))) {
                PapotonsTheme { BoardScreen(state = state, actions = noActions) }
            }
        }

        val deleteLast = composeRule.onNodeWithContentDescription(context.getString(R.string.message_delete_last)).getBoundsInRoot()
        val clear = composeRule.onNodeWithContentDescription(context.getString(R.string.message_clear)).getBoundsInRoot()
        val speech = composeRule.onNodeWithText(context.getString(R.string.speech_language_missing)).getBoundsInRoot()
        val muted = composeRule.onNodeWithContentDescription(context.getString(R.string.volume_muted_alert)).getBoundsInRoot()
        val lost = composeRule.onNodeWithContentDescription(context.getString(R.string.lost_boards_alert)).getBoundsInRoot()
        val about = composeRule.onNodeWithContentDescription(context.getString(R.string.about_open)).getBoundsInRoot()

        assertTrue("delete ends at ${deleteLast.right}, clear starts at ${clear.left}", deleteLast.right <= clear.left)
        val speak = composeRule.onNodeWithContentDescription(context.getString(R.string.message_speak)).getBoundsInRoot()
        assertTrue("voice alert ends at ${speech.right}, muted starts at ${muted.left}", speech.right <= muted.left)
        assertTrue("muted ends at ${muted.right}, lost boards starts at ${lost.left}", muted.right <= lost.left)
        assertTrue("lost boards ends at ${lost.right}, speak starts at ${speak.left}", lost.right <= speak.left)
        assertTrue("clear ends at ${clear.right}, about starts at ${about.left}", clear.right <= about.left)
        // Densité forcée : arrondis au millième de dp près.
        assertEquals(clear.width.value, deleteLast.width.value, WIDTH_TOLERANCE_DP)
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
