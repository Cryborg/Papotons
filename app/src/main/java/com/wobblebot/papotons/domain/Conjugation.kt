package com.wobblebot.papotons.domain

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import java.util.Locale

/**
 * Les temps de la page des formes, dans l'ordre de ses colonnes et de `assets/conjugations.tsv`
 * (généré par `tools/conjugaison/generate_conjugations.py`) : changer cet ordre décale toute la table.
 */
enum class Tense { PAST_COMPOUND, IMPERFECT, PRESENT, NEAR_FUTURE, FUTURE }

/** Les personnes, dans l'ordre des lignes de la page et de la table (je, tu, il, nous, vous, ils). */
enum class GrammaticalPerson { FIRST_SINGULAR, SECOND_SINGULAR, THIRD_SINGULAR, FIRST_PLURAL, SECOND_PLURAL, THIRD_PLURAL }

private val PERSON_COUNT = GrammaticalPerson.entries.size
private val FORM_COUNT = Tense.entries.size * PERSON_COUNT

/** Une ligne de la table : l'infinitif, puis chaque forme. */
private val COLUMN_COUNT = 1 + FORM_COUNT
private const val COLUMN_SEPARATOR = '\t'

/** Temps à un seul mot : un libellé conjugué (« veux ») se reconnaît parmi eux. */
private val SIMPLE_TENSES = listOf(Tense.PRESENT, Tense.IMPERFECT, Tense.FUTURE)

/** Place d'une forme dans une ligne de la table, infinitif exclu. */
private fun formIndex(tense: Tense, person: GrammaticalPerson): Int = tense.ordinal * PERSON_COUNT + person.ordinal

/** Les formes d'un verbe, son complément compris (« avoir besoin » : « ai besoin »). */
@Immutable
data class VerbForms(
    val infinitive: String,
    /** Temps par temps, personne par personne : l'ordre de [Tense] puis de [GrammaticalPerson]. */
    private val forms: ImmutableList<String>,
) {
    init {
        require(forms.size == FORM_COUNT) { "${forms.size} forms instead of $FORM_COUNT" }
    }

    fun form(tense: Tense, person: GrammaticalPerson): String = forms[formIndex(tense, person)]
}

/** La table de conjugaison embarquée, et les numéros (à partir de 1) des lignes ignorées : mal formées ou infinitif en double. */
class ConjugationTable private constructor(
    /** Formes de chaque infinitif, dans l'ordre de la table. */
    private val formsByInfinitive: Map<String, List<String>>,
    val malformedLines: List<Int>,
) {
    /** Forme simple → premier infinitif de la table qui la porte (la table est rangée par fréquence : « suis » est être). */
    private val infinitiveBySimpleForm: Map<String, String> = buildMap {
        for ((infinitive, forms) in formsByInfinitive) {
            for (tense in SIMPLE_TENSES) {
                for (person in GrammaticalPerson.entries) {
                    putIfAbsent(forms[formIndex(tense, person)], infinitive)
                }
            }
        }
    }

    val verbCount: Int get() = formsByInfinitive.size

    /**
     * Les formes du verbe que dit une case : son premier mot est un infinitif (« manger ») ou une forme
     * simple (« veux ») de la table, et le reste (« besoin ») suit chaque forme. `null` hors table.
     */
    fun formsOf(spoken: String): VerbForms? {
        val words = splitWords(spoken.trim().trimEnd { !it.isLetter() }).filter { it.isNotEmpty() }
        val head = words.firstOrNull()?.lowercase(Locale.FRENCH) ?: return null
        val infinitive = if (head in formsByInfinitive) head else infinitiveBySimpleForm[head] ?: return null
        val complement = words.drop(1).joinToString(" ")
        fun withComplement(form: String) = if (complement.isEmpty()) form else "$form $complement"
        return VerbForms(
            infinitive = withComplement(infinitive),
            forms = formsByInfinitive.getValue(infinitive).map(::withComplement).toImmutableList(),
        )
    }

    companion object {
        val EMPTY = ConjugationTable(emptyMap(), emptyList())

        /** Lit `assets/conjugations.tsv` : une ligne au mauvais nombre de colonnes, ou d'un infinitif déjà lu, est écartée. */
        fun parse(text: String): ConjugationTable {
            val formsByInfinitive = LinkedHashMap<String, List<String>>()
            val malformedLines = mutableListOf<Int>()
            for ((index, line) in text.lineSequence().withIndex()) {
                if (line.isEmpty()) continue
                val columns = line.split(COLUMN_SEPARATOR)
                if (columns.size != COLUMN_COUNT || columns.any { it.isBlank() } || columns.first() in formsByInfinitive) {
                    malformedLines += index + 1
                    continue
                }
                formsByInfinitive[columns.first()] = columns.drop(1)
            }
            return ConjugationTable(formsByInfinitive, malformedLines)
        }
    }
}
