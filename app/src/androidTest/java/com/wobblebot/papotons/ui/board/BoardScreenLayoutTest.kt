package com.wobblebot.papotons.ui.board

import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.graphics.BitmapFactory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpRect
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
import com.wobblebot.papotons.domain.Screen
import com.wobblebot.papotons.domain.BoardSize
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
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
private val PORTRAIT_PHONE_WIDTH = 360.dp

/** Assez d'écrans aux noms longs pour remplir la rangée d'un téléphone. */
private const val MANY_SCREENS = 4

/** Un téléphone de 360 dp au zoom d'affichage maximal. */
private val MAXIMUM_ZOOM_PHONE_WIDTH = 320.dp
private val PORTRAIT_PHONE_HEIGHT = 740.dp
// Un téléphone couché.
private val LANDSCAPE_PHONE_WIDTH = 740.dp
private val LANDSCAPE_PHONE_HEIGHT = 360.dp
private const val LARGE_CELLS_ROWS = 3
private const val LARGE_CELLS_COLUMNS = 4
// Une barre de navigation à trois boutons, ou plus.
private const val NAVIGATION_BAR_HEIGHT_PX = 200
private const val INSET_TOLERANCE_DP = 0.5f

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
        var profileName by mutableStateOf<String?>(null)
        var isLeaveGated by mutableStateOf(true)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PapotonsTheme { BoardScreen(state = state, actions = noActions, profileName = profileName, isLeaveGated = isLeaveGated) }
            }
        }
        val original = cellBounds("Manger")

        // Le prénom arrive dans la barre des adultes après la lecture, long ou court : elle garde son épaisseur.
        profileName = "Léo"
        composeRule.onNodeWithContentDescription(context.getString(R.string.adult_bar_profile_description, "Léo")).assertIsDisplayed()
        assertEquals("profile name", original, cellBounds("Manger"))
        profileName = "Marie-Charlotte Bernadette de la Fontaine"
        assertEquals("long profile name", original, cellBounds("Manger"))
        // L'option de sortie décochée change le bouton Bureau, pas la barre.
        isLeaveGated = false
        composeRule.onNodeWithContentDescription(context.getString(R.string.adult_bar_leave)).assertIsDisplayed()
        assertEquals("exit lock", original, cellBounds("Manger"))

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

    /**
     * Case plate (téléphone couché) : le picto centré passe à côté de la marque du coin, il ne descend
     * pas sous elle. Descendu, il ne restait presque rien de lui.
     */
    @Test
    fun aFolderPictogramInAFlatCellKeepsTheSizeOfAWordPictogram() {
        val image = context.assets.open("pictograms/$BUNDLED_PICTOGRAM_ID.png").use(BitmapFactory::decodeStream).asImageBitmap()
        val folder = Cell(id = 1, row = 0, column = 0, label = "Manger", linkedBoardId = 2, pictogramId = BUNDLED_PICTOGRAM_ID, category = WordCategory.NOUN)
        val word = Cell(id = 2, row = 0, column = 1, label = "pomme", linkedBoardId = null, pictogramId = BUNDLED_PICTOGRAM_ID, category = WordCategory.NOUN)
        val state = homeState.copy(board = checkNotNull(homeState.board).copy(grid = layoutGrid(FLAT_CELL_ROWS, WIDE_CELL_COLUMNS, listOf(folder, word))))
        composeRule.setContent {
            CompositionLocalProvider(LocalPictogramImages provides { image }) {
                PapotonsTheme { BoardScreen(state = state, actions = noActions) }
            }
        }

        val pictograms = composeRule.onAllNodesWithTag(PICTOGRAM_TEST_TAG, useUnmergedTree = true)
        val folderPictogram = pictograms[0].getBoundsInRoot()
        val wordPictogram = pictograms[1].getBoundsInRoot()
        val mark = composeRule.onNodeWithTag(FOLDER_MARK_TEST_TAG, useUnmergedTree = true).getBoundsInRoot()

        assertTrue("cell must be flat for this test: ${folderPictogram.width} x ${folderPictogram.height}", folderPictogram.width > folderPictogram.height)
        assertEquals(wordPictogram.height.value, folderPictogram.height.value, CENTER_TOLERANCE_DP)
        // L'image remplit sa zone et dessine un carré centré (ContentScale.Fit) : c'est lui que la marque ne doit pas toucher.
        val side = minOf(folderPictogram.width, folderPictogram.height)
        val drawnLeft = (folderPictogram.left + folderPictogram.right) / 2 - side / 2
        val drawnTop = (folderPictogram.top + folderPictogram.bottom) / 2 - side / 2
        val overlaps = mark.left < drawnLeft + side && drawnLeft < mark.right && mark.top < drawnTop + side && drawnTop < mark.bottom
        assertFalse("mark $mark covers the drawn pictogram at ($drawnLeft, $drawnTop), side $side", overlaps)
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

    /** Les écrans sont dans la barre du bas, d'épaisseur fixe : créer le deuxième ne déplace aucune case (invariant 3). */
    @Test
    fun aSecondScreenDoesNotMoveTheCells() {
        val home = checkNotNull(homeState.board)
        var state by mutableStateOf(homeState.copy(board = home.copy(screens = persistentListOf(Screen(1, "Accueil", BoardSize.STANDARD)))))
        composeRule.setContent { PapotonsTheme { BoardScreen(state = state, actions = noActions, profileName = "Léo") } }
        val original = cellBounds("Manger")

        state = homeState.copy(board = home.copy(screens = persistentListOf(Screen(1, "Accueil", BoardSize.STANDARD), Screen(1000, "Maison", BoardSize.LARGE_CELLS))))

        composeRule.onNodeWithText("Maison").assertIsDisplayed()
        assertEquals(original, cellBounds("Manger"))
    }

    /** Centrés sur la barre, même quand un long prénom occupe la gauche. */
    @Test
    fun theScreensAreCenteredOnTheBottomBar() {
        val screens = persistentListOf(Screen(1, "Accueil", BoardSize.STANDARD), Screen(1000, "Maison", BoardSize.LARGE_CELLS))
        val twoScreens = homeState.copy(board = checkNotNull(homeState.board).copy(screens = screens))
        composeRule.setContent { PapotonsTheme { BoardScreen(state = twoScreens, actions = noActions, profileName = "Marie-Charlotte Dupont-Lefèvre") } }

        val rowBounds = composeRule.onNodeWithContentDescription(context.getString(R.string.screen_tabs_description)).getBoundsInRoot()
        val rootBounds = composeRule.onRoot().getBoundsInRoot()

        assertEquals(((rootBounds.left + rootBounds.right) / 2).value, ((rowBounds.left + rowBounds.right) / 2).value, CENTER_TOLERANCE_DP)
    }

    /** Un seul écran : aucun onglet, la grille garde toute sa place. */
    @Test
    fun aSingleScreenShowsNoTabs() {
        val oneScreen = homeState.copy(board = checkNotNull(homeState.board).copy(screens = persistentListOf(Screen(1, "Accueil", BoardSize.STANDARD))))
        composeRule.setContent { PapotonsTheme { BoardScreen(state = oneScreen, actions = noActions) } }

        composeRule.onNodeWithContentDescription(context.getString(R.string.screen_tabs_description)).assertDoesNotExist()
    }

    /** Deux écrans : leurs onglets, l'ouvert marqué ; en toucher un l'ouvre. */
    @Test
    fun twoScreensShowTheirTabsAndATabOpensItsScreen() {
        val chosen = mutableListOf<Long>()
        val screens = persistentListOf(Screen(1, "Accueil", BoardSize.STANDARD), Screen(1000, "Maison", BoardSize.LARGE_CELLS))
        // Dans un dossier (grille 2) de « Maison » : l'onglet marqué est celui de l'écran, pas de la grille.
        val inHouseFolder = homeState.copy(board = checkNotNull(homeState.board).copy(id = 2, canGoBack = true, rootId = 1000, screens = screens))
        composeRule.setContent { PapotonsTheme { BoardScreen(state = inHouseFolder, actions = noActions.copy(onScreenChosen = { chosen += it })) } }

        composeRule.onNodeWithContentDescription(context.getString(R.string.screen_tabs_description)).assertExists()
        composeRule.onNodeWithText("Maison").assertIsSelected()
        composeRule.onNodeWithText("Accueil").assertIsNotSelected().performClick()

        assertEquals(listOf(1L), chosen)
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
        val settings = composeRule.onNodeWithContentDescription(context.getString(R.string.settings_open)).getBoundsInRoot()

        // Collés, un appui qui tremble sur « Effacer le dernier mot » viderait toute la phrase.
        assertTrue("delete ends at ${deleteLast.right}, clear starts at ${clear.left}", clear.left - deleteLast.right >= TARGET_SPACING)
        // Entre l'alerte et les boutons, la phrase, qu'on touche pour la dire, garde au moins une cible.
        assertTrue(
            "alert ends at ${alert.right}, delete starts at ${deleteLast.left}",
            deleteLast.left - alert.right >= CHILD_BUTTON_MIN_WIDTH + TARGET_SPACING * 2,
        )
        // La roue est sous la barre du haut, dans la barre des adultes.
        assertTrue("settings top ${settings.top} must be below the top bar (clear bottom ${clear.bottom})", settings.top >= clear.bottom)
        assertEquals(clear.width, deleteLast.width)
    }

    @Test
    fun aPhoneAtMaximumZoomKeepsTheSentenceTouchableInAFolder() {
        // Téléphone de 360 dp au zoom d'affichage maximal : environ 320 dp. Dossier, voix en panne, phrase longue.
        val words = (0 until LONG_SENTENCE_WORD_COUNT).map { SentenceToken("mot$it", persistentListOf()) }
        val state = homeState.copy(
            board = checkNotNull(homeState.board).copy(canGoBack = true),
            message = words.toImmutableList(),
            speechStatus = SpeechStatus.Unavailable,
        )
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(MAXIMUM_ZOOM_PHONE_WIDTH, PORTRAIT_PHONE_HEIGHT))) {
                PapotonsTheme { BoardScreen(state = state, actions = noActions) }
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.board_back)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.board_home)).assertDoesNotExist()
        val sentence = composeRule.onNodeWithContentDescription(words.joinToString(" ") { it.text }).getBoundsInRoot()
        val clear = composeRule.onNodeWithContentDescription(context.getString(R.string.message_clear)).getBoundsInRoot()
        assertTrue("sentence is ${sentence.width} wide", sentence.width >= CHILD_BUTTON_MIN_WIDTH)
        assertTrue("clear ends at ${clear.right}", clear.right <= MAXIMUM_ZOOM_PHONE_WIDTH)
    }

    @Test
    fun aPhoneWithoutZoomKeepsBothNavigationButtonsAndATouchableSentence() {
        // La borne du seuil : 360 dp, dossier, voix en panne, phrase longue.
        val words = (0 until LONG_SENTENCE_WORD_COUNT).map { SentenceToken("mot$it", persistentListOf()) }
        val state = homeState.copy(
            board = checkNotNull(homeState.board).copy(canGoBack = true),
            message = words.toImmutableList(),
            speechStatus = SpeechStatus.Unavailable,
        )
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(PORTRAIT_PHONE_WIDTH, PORTRAIT_PHONE_HEIGHT))) {
                PapotonsTheme { BoardScreen(state = state, actions = noActions) }
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.board_back)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.board_home)).assertIsDisplayed()
        val sentence = composeRule.onNodeWithContentDescription(words.joinToString(" ") { it.text }).getBoundsInRoot()
        assertTrue("sentence is ${sentence.width} wide", sentence.width >= CHILD_BUTTON_MIN_WIDTH)
    }

    @Test
    fun touchingTheSentenceSaysItAndEachEraseButtonDoesItsOwnJob() {
        var spoken = 0
        var deleted = 0
        var cleared = 0
        val actions = noActions.copy(onSpeakMessage = { spoken++ }, onDeleteLastWord = { deleted++ }, onClearMessage = { cleared++ })
        val state = homeState.copy(message = persistentListOf(SentenceToken("je", persistentListOf()), SentenceToken("veux", persistentListOf())))
        composeRule.setContent { PapotonsTheme { BoardScreen(state = state, actions = actions) } }

        val speakLabel = context.getString(R.string.message_speak)
        val sentence = composeRule.onNodeWithContentDescription("je veux")
        // TalkBack annonce l'action « Dire la phrase » sur la carte.
        sentence.assert(SemanticsMatcher("says the sentence") { it.config.getOrNull(SemanticsActions.OnClick)?.label == speakLabel })
        sentence.performClick()
        val deleteLast = composeRule.onNodeWithContentDescription(context.getString(R.string.message_delete_last)).assertIsEnabled()
        deleteLast.performClick()
        deleteLast.performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.message_clear)).assertIsEnabled().performClick()

        // Deux effacements pour un vidage : une inversion des deux boutons se verrait.
        assertEquals(listOf(1, 2, 1), listOf(spoken, deleted, cleared))
    }

    @Test
    fun theChildButtonsAreAnnouncedAsButtonsAndTheEraseButtonsWaitForAWord() {
        val state = homeState.copy(board = checkNotNull(homeState.board).copy(canGoBack = true))
        composeRule.setContent { PapotonsTheme { BoardScreen(state = state, actions = noActions) } }
        val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

        composeRule.onNodeWithContentDescription(context.getString(R.string.board_back)).assert(isButton)
        composeRule.onNodeWithContentDescription(context.getString(R.string.message_delete_last)).assert(isButton).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(context.getString(R.string.message_clear)).assertIsNotEnabled()
    }

    @Test
    fun screenTabsAreSpacedAndWideEnough() {
        // Un nom d'une lettre : la pastille garde la largeur d'une cible.
        val screens = persistentListOf(Screen(1, "A", BoardSize.STANDARD), Screen(1000, "Maison", BoardSize.LARGE_CELLS))
        val state = homeState.copy(board = checkNotNull(homeState.board).copy(screens = screens))
        composeRule.setContent { PapotonsTheme { BoardScreen(state = state, actions = noActions) } }

        val first = composeRule.onNodeWithText("A").getBoundsInRoot()
        val house = composeRule.onNodeWithText("Maison").getBoundsInRoot()

        assertTrue("tabs ${first.right} → ${house.left}", house.left - first.right >= TARGET_SPACING)
        assertTrue("tab is ${first.width} wide", first.width >= CHILD_BUTTON_MIN_WIDTH)
    }

    @Test
    fun manyScreenTabsKeepAwayFromTheLeaveButton() {
        // Assez d'écrans aux noms longs pour que la rangée prenne toute sa place, sur un téléphone.
        val screens = (0 until MANY_SCREENS).map { Screen(1000L + it, "Écran au nom très long $it", BoardSize.STANDARD) }.toImmutableList()
        val state = homeState.copy(board = checkNotNull(homeState.board).copy(screens = screens))
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(PORTRAIT_PHONE_WIDTH, PORTRAIT_PHONE_HEIGHT))) {
                PapotonsTheme { BoardScreen(state = state, actions = noActions) }
            }
        }

        val row = composeRule.onNodeWithContentDescription(context.getString(R.string.screen_tabs_description)).getBoundsInRoot()
        val leaveLabels = listOf(R.string.adult_bar_leave, R.string.adult_bar_leave_gated).map { context.getString(it) }
        val leave = composeRule.onNode(hasContentDescription(leaveLabels[0]) or hasContentDescription(leaveLabels[1])).getBoundsInRoot()

        assertTrue("tabs end at ${row.right}, leave starts at ${leave.left}", leave.left - row.right >= TARGET_SPACING)
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
        fun buttons() = listOf(R.string.message_delete_last, R.string.message_clear)
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
        val settings = composeRule.onNodeWithContentDescription(context.getString(R.string.settings_open)).getBoundsInRoot()
        composeRule.onNodeWithContentDescription(context.getString(R.string.lost_boards_alert)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.speech_language_missing)).assertDoesNotExist()

        val deleteLast = composeRule.onNodeWithContentDescription(context.getString(R.string.message_delete_last)).getBoundsInRoot()
        assertTrue("muted alert ends at ${muted.right}, delete starts at ${deleteLast.left}", muted.right <= deleteLast.left)
        // La roue est sous la barre du haut, dans la barre des adultes.
        assertTrue("settings top ${settings.top} must be below the top bar (clear bottom ${clear.bottom})", settings.top >= clear.bottom)
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
        val settings = composeRule.onNodeWithContentDescription(context.getString(R.string.settings_open)).getBoundsInRoot()

        assertTrue("delete ends at ${deleteLast.right}, clear starts at ${clear.left}", clear.left - deleteLast.right >= TARGET_SPACING)
        assertTrue("voice alert ends at ${speech.right}, muted starts at ${muted.left}", muted.left - speech.right >= TARGET_SPACING)
        assertTrue("muted ends at ${muted.right}, lost boards starts at ${lost.left}", lost.left - muted.right >= TARGET_SPACING)
        assertTrue("lost boards ends at ${lost.right}, delete starts at ${deleteLast.left}", lost.right <= deleteLast.left)
        // La roue est sous la barre du haut, dans la barre des adultes.
        assertTrue("settings top ${settings.top} must be below the top bar (clear bottom ${clear.bottom})", settings.top >= clear.bottom)
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

    @Test
    fun inPortraitTheAdultBarIsBelowTheGrid() {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(PORTRAIT_PHONE_WIDTH, PORTRAIT_PHONE_HEIGHT))) {
                PapotonsTheme { BoardScreen(state = homeState, actions = noActions, profileName = "Léo") }
            }
        }

        assertAdultBarBelow(cellBounds("Manger"))
        composeRule.onNodeWithContentDescription(context.getString(R.string.adult_bar_profile_description, "Léo")).assertIsDisplayed()
    }

    /** Téléphone couché, grandes cases : la barre reste en bas (plus de rail), sous la dernière ligne. */
    @Test
    fun inLandscapeTheAdultBarStaysBelowTheGrid() {
        val largeCells = (0 until LARGE_CELLS_ROWS * LARGE_CELLS_COLUMNS).map { index ->
            Cell(id = index + 1L, row = index / LARGE_CELLS_COLUMNS, column = index % LARGE_CELLS_COLUMNS, label = "c$index", linkedBoardId = null)
        }
        val state = homeState.copy(board = DisplayedBoard(id = 13, name = "Grandes cases", grid = layoutGrid(LARGE_CELLS_ROWS, LARGE_CELLS_COLUMNS, largeCells), canGoBack = false))
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(LANDSCAPE_PHONE_WIDTH, LANDSCAPE_PHONE_HEIGHT))) {
                PapotonsTheme { BoardScreen(state = state, actions = noActions, profileName = "Léo") }
            }
        }

        assertAdultBarBelow(cellBounds("c${LARGE_CELLS_ROWS * LARGE_CELLS_COLUMNS - 1}"))
    }

    private fun assertAdultBarBelow(lastCell: DpRect) {
        val settings = composeRule.onNodeWithContentDescription(context.getString(R.string.settings_open)).getBoundsInRoot()
        val leave = composeRule.onNodeWithContentDescription(context.getString(R.string.adult_bar_leave_gated)).getBoundsInRoot()
        assertTrue("settings top ${settings.top}, last cell bottom ${lastCell.bottom}", settings.top >= lastCell.bottom)
        assertTrue("leave top ${leave.top}, last cell bottom ${lastCell.bottom}", leave.top >= lastCell.bottom)
        assertTrue("leave right ${leave.right}, settings left ${settings.left}", leave.right <= settings.left)
    }

    /** Le bouton Bureau appelle sa propre action, protégé (grisé mais touchable) ou non : le contrôle se décide plus haut. */
    @Test
    fun theLeaveButtonCallsItsActionEvenWhenGated() {
        var leaveClicks = 0
        var settingsClicks = 0
        var isLeaveGated by mutableStateOf(true)
        val actions = noActions.copy(onLeaveClick = { leaveClicks++ }, onSettingsClick = { settingsClicks++ })
        composeRule.setContent { PapotonsTheme { BoardScreen(state = homeState, actions = actions, isLeaveGated = isLeaveGated) } }

        composeRule.onNodeWithContentDescription(context.getString(R.string.adult_bar_leave_gated)).performClick()
        isLeaveGated = false
        composeRule.onNodeWithContentDescription(context.getString(R.string.adult_bar_leave)).performClick()

        assertEquals(2, leaveClicks)
        assertEquals(0, settingsClicks)
    }

    /**
     * La barre de navigation masquée revient par-dessus (glissement, modale, clavier) : son encart ne
     * déplace aucune case. En écran partagé, Android ne la masque pas : sa place est réservée.
     */
    @Test
    fun theNavigationBarComingBackMovesNoCell() {
        lateinit var view: View
        var isInMultiWindow by mutableStateOf(false)
        composeRule.setContent {
            view = LocalView.current
            PapotonsTheme { BoardScreen(state = homeState, actions = noActions, isInMultiWindow = isInMultiWindow) }
        }
        val original = cellBounds("Manger")
        val settings = composeRule.onNodeWithContentDescription(context.getString(R.string.settings_open)).getBoundsInRoot()
        // Les encarts du moment (barre d'état, encoche), plus la barre de navigation.
        val navigationBar = WindowInsetsCompat.Builder(checkNotNull(ViewCompat.getRootWindowInsets(view)))
            // En bas comme sur une tablette ; sur le côté comme sur un téléphone couché à trois boutons.
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, NAVIGATION_BAR_HEIGHT_PX, NAVIGATION_BAR_HEIGHT_PX))
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .build()

        composeRule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view, navigationBar) }
        composeRule.waitForIdle()
        assertEquals("navigation bar shown", original, cellBounds("Manger"))

        isInMultiWindow = true
        composeRule.waitForIdle()
        val reservedSettings = composeRule.onNodeWithContentDescription(context.getString(R.string.settings_open)).getBoundsInRoot()
        // Exactement la barre injectée : preuve qu'elle est bien arrivée jusqu'à l'écran.
        val navigationBarHeight = with(composeRule.density) { NAVIGATION_BAR_HEIGHT_PX.toDp() }
        assertEquals(navigationBarHeight.value, (settings.bottom - reservedSettings.bottom).value, INSET_TOLERANCE_DP)
    }

    /** Une grille qui ne se charge pas ne doit pas fermer l'accès aux Réglages (sauvegarde, utilisateurs). */
    @Test
    fun theSettingsStayReachableWhileTheGridLoads() {
        composeRule.setContent { PapotonsTheme { BoardScreen(state = homeState.copy(board = null), actions = noActions) } }

        composeRule.onNodeWithContentDescription(context.getString(R.string.settings_open)).assertIsDisplayed()
    }
}
