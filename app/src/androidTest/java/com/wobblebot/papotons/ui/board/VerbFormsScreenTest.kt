package com.wobblebot.papotons.ui.board

import android.graphics.BitmapFactory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.ConjugationTable
import com.wobblebot.papotons.domain.GrammaticalPerson
import com.wobblebot.papotons.domain.Tense
import com.wobblebot.papotons.domain.WordCategory
import com.wobblebot.papotons.domain.layoutGrid
import com.wobblebot.papotons.speech.SpeechStatus
import com.wobblebot.papotons.ui.theme.PapotonsTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Un picto embarqué dans les assets. */
private const val BUNDLED_PICTOGRAM_ID = 6632L

/** L'appui long d'une case verbe et la page des formes, à l'écran. */
@RunWith(AndroidJUnit4::class)
class VerbFormsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val verb = Cell(id = 1, row = 0, column = 0, label = "ai", linkedBoardId = null, category = WordCategory.VERB)
    private val noun = Cell(id = 2, row = 0, column = 1, label = "eau", linkedBoardId = null, category = WordCategory.NOUN)
    private val homeState = BoardUiState(
        board = DisplayedBoard(id = 1, name = "Accueil", grid = layoutGrid(1, 2, listOf(verb, noun)), canGoBack = false),
        message = persistentListOf(),
        speechStatus = SpeechStatus.Ready,
    )
    private val table = context.assets.open("conjugations.tsv").bufferedReader().use { ConjugationTable.parse(it.readText()) }
    private val avoir = checkNotNull(table.formsOf("ai"))

    @Test
    fun aLongPressOnAVerbAsksForItsForms_andTalkBackAnnouncesIt() {
        val longPressed = mutableListOf<Cell>()
        val tapped = mutableListOf<Cell>()
        composeRule.setContent {
            PapotonsTheme {
                BoardScreen(state = homeState, actions = actions(onCellClick = { tapped += it }, onCellLongClick = { longPressed += it }))
            }
        }

        composeRule.onNodeWithText("ai").performTouchInput { longClick() }

        assertEquals(listOf(verb), longPressed)
        assertEquals(emptyList<Cell>(), tapped)
        val label = context.getString(R.string.conjugation_long_click_label)
        composeRule.onNodeWithText("ai").assert(
            SemanticsMatcher("long click labelled $label") { node ->
                node.config.getOrNull(SemanticsActions.OnLongClick)?.label == label
            },
        )
        // Un nom n'a pas de formes : pas d'action d'appui long proposée à TalkBack.
        composeRule.onNodeWithText("eau").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }

    @Test
    fun noLongPressIsOfferedWhileEditing() {
        composeRule.setContent {
            PapotonsTheme { BoardScreen(state = homeState.copy(isEditing = true), actions = actions()) }
        }

        composeRule.onNodeWithText("ai").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }

    @Test
    fun theFormsReplaceTheGrid_aFormIsChosen_andBackIsOffered() {
        val chosen = mutableListOf<String>()
        var state by mutableStateOf(homeState)
        composeRule.setContent {
            PapotonsTheme { BoardScreen(state = state, actions = actions(onVerbFormClick = { chosen += it })) }
        }

        state = homeState.copy(verbForms = VerbFormsPage(verb, avoir))

        composeRule.onNodeWithTag(VERB_FORMS_TEST_TAG).assertIsDisplayed()
        // La page remplace la grille, elle ne la recouvre pas.
        composeRule.onNodeWithText("eau").assertDoesNotExist()
        // Ouverte depuis l'accueil, la page se referme quand même par « Retour ».
        composeRule.onNodeWithContentDescription(context.getString(R.string.board_back)).assertIsDisplayed()
        val description = context.getString(
            R.string.conjugation_form_description,
            context.getString(R.string.conjugation_person_first_plural),
            context.getString(R.string.conjugation_tense_past_compound),
            "avons eu",
        )
        // performClick simule un toucher : assertHasClickAction vérifie que l'accessibilité peut l'activer aussi.
        composeRule.onNodeWithContentDescription(description).assertHasClickAction().performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.conjugation_infinitive_description, "avoir")).assertHasClickAction().performClick()

        assertEquals(listOf("avons eu", "avoir"), chosen)
    }

    @Test
    fun onlyTheInfinitiveShowsThePictureOfTheCell() {
        val image = context.assets.open("pictograms/$BUNDLED_PICTOGRAM_ID.png").use(BitmapFactory::decodeStream).asImageBitmap()
        val pictured = verb.copy(pictogramId = BUNDLED_PICTOGRAM_ID)
        composeRule.setContent {
            CompositionLocalProvider(LocalPictogramImages provides { image }) {
                PapotonsTheme { BoardScreen(state = homeState.copy(verbForms = VerbFormsPage(pictured, avoir)), actions = actions()) }
            }
        }

        val pictograms = composeRule.onAllNodesWithTag(PICTOGRAM_TEST_TAG, useUnmergedTree = true)
        pictograms.assertCountEquals(1)
        val infinitive = composeRule.onNodeWithContentDescription(context.getString(R.string.conjugation_infinitive_description, "avoir")).getBoundsInRoot()
        val pictogram = pictograms[0].getBoundsInRoot()
        assertTrue(pictogram.left >= infinitive.left && pictogram.right <= infinitive.right && pictogram.top >= infinitive.top && pictogram.bottom <= infinitive.bottom)
    }

    @Test
    fun eachFormSitsUnderItsTenseAndOnItsPersonRow() {
        composeRule.setContent {
            PapotonsTheme { BoardScreen(state = homeState.copy(verbForms = VerbFormsPage(verb, avoir)), actions = actions()) }
        }
        val tenseNames = mapOf(
            Tense.PAST_COMPOUND to R.string.conjugation_tense_past_compound,
            Tense.IMPERFECT to R.string.conjugation_tense_imperfect,
            Tense.PRESENT to R.string.conjugation_tense_present,
            Tense.NEAR_FUTURE to R.string.conjugation_tense_near_future,
            Tense.FUTURE to R.string.conjugation_tense_future,
        ).mapValues { context.getString(it.value) }
        val personNames = mapOf(
            GrammaticalPerson.FIRST_SINGULAR to R.string.conjugation_person_first_singular,
            GrammaticalPerson.SECOND_SINGULAR to R.string.conjugation_person_second_singular,
            GrammaticalPerson.THIRD_SINGULAR to R.string.conjugation_person_third_singular,
            GrammaticalPerson.FIRST_PLURAL to R.string.conjugation_person_first_plural,
            GrammaticalPerson.SECOND_PLURAL to R.string.conjugation_person_second_plural,
            GrammaticalPerson.THIRD_PLURAL to R.string.conjugation_person_third_plural,
        ).mapValues { context.getString(it.value) }
        val tenseHeaders = tenseNames.mapValues { composeRule.onNodeWithText(it.value).getBoundsInRoot() }
        val personHeaders = personNames.mapValues { composeRule.onNodeWithText(it.value).getBoundsInRoot() }

        // Les temps dans l'ordre du temps qui passe, de gauche à droite ; les personnes de haut en bas.
        assertEquals(Tense.entries, Tense.entries.sortedBy { tenseHeaders.getValue(it).left })
        assertEquals(GrammaticalPerson.entries, GrammaticalPerson.entries.sortedBy { personHeaders.getValue(it).top })
        for (tense in Tense.entries) {
            for (person in GrammaticalPerson.entries) {
                val description = context.getString(R.string.conjugation_form_description, personNames[person], tenseNames[tense], avoir.form(tense, person))
                val form = composeRule.onNodeWithContentDescription(description).getBoundsInRoot()
                val column = tenseHeaders.getValue(tense)
                val row = personHeaders.getValue(person)
                val where = "$person $tense"
                assertTrue(where, (form.left + form.right) / 2 in column.left..column.right)
                assertTrue(where, (form.top + form.bottom) / 2 in row.top..row.bottom)
            }
        }
    }

    private fun actions(
        onCellClick: (Cell) -> Unit = {},
        onCellLongClick: (Cell) -> Unit = {},
        onVerbFormClick: (String) -> Unit = {},
    ) = BoardActions(
        onCellClick, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {},
        onCellLongClick = onCellLongClick,
        onVerbFormClick = onVerbFormClick,
    )
}
