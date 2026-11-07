package com.wobblebot.papotons.ui.board

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import com.wobblebot.papotons.R
import com.wobblebot.papotons.domain.Cell
import com.wobblebot.papotons.domain.GrammaticalPerson
import com.wobblebot.papotons.domain.Tense
import com.wobblebot.papotons.domain.VerbForms
import com.wobblebot.papotons.domain.WordCategory
import com.wobblebot.papotons.ui.theme.wordCategoryBackground

/** La page des formes ouverte par un appui long sur [cell] : ce qu'on y touche prend son image et sa catégorie. */
@Immutable
data class VerbFormsPage(val cell: Cell, val forms: VerbForms)

/** Repère des tests : la page des formes. */
const val VERB_FORMS_TEST_TAG = "verb_forms"

/** En-tête de chaque colonne, dans l'ordre de [Tense]. */
@StringRes
private fun tenseName(tense: Tense): Int = when (tense) {
    Tense.PAST_COMPOUND -> R.string.conjugation_tense_past_compound
    Tense.IMPERFECT -> R.string.conjugation_tense_imperfect
    Tense.PRESENT -> R.string.conjugation_tense_present
    Tense.NEAR_FUTURE -> R.string.conjugation_tense_near_future
    Tense.FUTURE -> R.string.conjugation_tense_future
}

/** En-tête de chaque ligne, dans l'ordre de [GrammaticalPerson]. */
@StringRes
private fun personName(person: GrammaticalPerson): Int = when (person) {
    GrammaticalPerson.FIRST_SINGULAR -> R.string.conjugation_person_first_singular
    GrammaticalPerson.SECOND_SINGULAR -> R.string.conjugation_person_second_singular
    GrammaticalPerson.THIRD_SINGULAR -> R.string.conjugation_person_third_singular
    GrammaticalPerson.FIRST_PLURAL -> R.string.conjugation_person_first_plural
    GrammaticalPerson.SECOND_PLURAL -> R.string.conjugation_person_second_plural
    GrammaticalPerson.THIRD_PLURAL -> R.string.conjugation_person_third_plural
}

/**
 * Une ligne d'en-têtes (l'infinitif dans le coin, puis les temps), puis une ligne par personne.
 * Même disposition pour tous les verbes : une forme est toujours au même endroit (invariant 3).
 * Forcée de gauche à droite, comme la grille.
 */
@Composable
fun VerbFormsGrid(page: VerbFormsPage, onFormClick: (String) -> Unit, modifier: Modifier = Modifier) {
    val forms = page.forms
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier = modifier.padding(GRID_PADDING),
            verticalArrangement = Arrangement.spacedBy(CELL_SPACING),
        ) {
            FormsRow {
                val infinitiveDescription = stringResource(R.string.conjugation_infinitive_description, forms.infinitive)
                FormButton(
                    text = forms.infinitive,
                    description = infinitiveDescription,
                    cell = page.cell,
                    showPicture = true,
                    onChoose = { onFormClick(forms.infinitive) },
                )
                for (tense in Tense.entries) Header(stringResource(tenseName(tense)))
            }
            for (person in GrammaticalPerson.entries) {
                val personText = stringResource(personName(person))
                FormsRow {
                    Header(personText)
                    for (tense in Tense.entries) {
                        val form = forms.form(tense, person)
                        FormButton(
                            text = form,
                            description = stringResource(R.string.conjugation_form_description, personText, stringResource(tenseName(tense)), form),
                            cell = page.cell,
                            showPicture = false,
                            onChoose = { onFormClick(form) },
                        )
                    }
                }
            }
        }
    }
}

/** Une ligne de la page, de même hauteur que les autres. */
@Composable
private fun ColumnScope.FormsRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CELL_SPACING),
        content = content,
    )
}

/** Un en-tête de ligne ou de colonne : du texte, pas une cible. */
@Composable
private fun RowScope.Header(text: String) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            // Fusionné : sans lui, TalkBack se pose sur le texte, qui n'est pas un titre.
            .semantics(mergeDescendants = true) { heading() },
        contentAlignment = Alignment.Center,
    ) {
        CellLabel(text, LABEL_MAX_FONT_SIZE, Modifier)
    }
}

@Composable
private fun RowScope.FormButton(text: String, description: String, cell: Cell, showPicture: Boolean, onChoose: () -> Unit) {
    Surface(
        onClick = onChoose,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            // Le clear efface aussi l'action de clic de Surface : redéclarée, sans quoi TalkBack, l'accès
            // par contacteur et Voice Access ne pourraient pas activer la forme.
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = description
                onClick {
                    onChoose()
                    true
                }
            },
        shape = BoardCardShape,
        // Seul un verbe ouvre la page : toutes ses formes en prennent la couleur.
        color = wordCategoryBackground(WordCategory.VERB),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(BOARD_CARD_BORDER_WIDTH, MaterialTheme.colorScheme.outline),
    ) {
        CellFace(text, cell.picture.takeIf { showPicture })
    }
}
