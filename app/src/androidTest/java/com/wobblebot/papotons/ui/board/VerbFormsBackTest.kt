package com.wobblebot.papotons.ui.board

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wobblebot.papotons.MainActivity
import com.wobblebot.papotons.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val SPLASH_TIMEOUT_MS = 10_000L

/**
 * Ouverte depuis l'accueil, où le retour arrière quitte d'habitude l'appli, la page des formes se
 * referme d'abord. Sur l'émulateur seulement : l'appli réelle se lance.
 */
@RunWith(AndroidJUnit4::class)
class VerbFormsBackTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun backOnTheHomeBoardClosesTheFormsAndStaysInTheApp() {
        val want = composeRule.activity.getString(R.string.vocabulary_want)
        // La case de la grille, pas le mot de la phrase : un appui long parti avant la lecture de la
        // table dit « veux » et l'ajoute à la barre.
        val wantCell = hasText(want) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)
        // Le carton du studio couvre l'écran au lancement et prend les appuis : on insiste.
        composeRule.waitUntil(SPLASH_TIMEOUT_MS) {
            // La grille peut ne pas être encore là : l'appui échoue, on réessaie.
            runCatching { composeRule.onNode(wantCell).performTouchInput { longClick() } }
            composeRule.onAllNodesWithTag(VERB_FORMS_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

        composeRule.onNodeWithTag(VERB_FORMS_TEST_TAG).assertDoesNotExist()
        composeRule.onNode(wantCell).assertExists()
        // Sans le retour intercepté, l'activité racine passerait en arrière-plan (API 31+) sans se terminer.
        assertTrue(composeRule.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
}
