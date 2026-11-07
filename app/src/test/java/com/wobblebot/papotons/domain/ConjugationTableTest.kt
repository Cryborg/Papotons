package com.wobblebot.papotons.domain

import com.wobblebot.papotons.data.board.CoreVocabulary
import com.wobblebot.papotons.domain.GrammaticalPerson.FIRST_PLURAL
import com.wobblebot.papotons.domain.GrammaticalPerson.FIRST_SINGULAR
import com.wobblebot.papotons.domain.GrammaticalPerson.SECOND_PLURAL
import com.wobblebot.papotons.domain.GrammaticalPerson.THIRD_PLURAL
import com.wobblebot.papotons.domain.GrammaticalPerson.THIRD_SINGULAR
import com.wobblebot.papotons.resourceStrings
import com.wobblebot.papotons.stringNamesById
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import java.io.File

class ConjugationTableTest {
    /** Le vrai fichier embarqué : les tests tournent depuis le dossier du module. */
    private val table = ConjugationTable.parse(File("src/main/assets/conjugations.tsv").readText())

    private fun forms(label: String) = checkNotNull(table.formsOf(label)) { "no forms for $label" }

    @Test
    fun `the bundled table is well formed`() {
        assertEquals(emptyList<Int>(), table.malformedLines)
    }

    @Test
    fun `a conjugated label finds its verb, each form at its tense and person`() {
        val vouloir = forms("veux")

        assertEquals("vouloir", vouloir.infinitive)
        // Personnes et temps différents à chaque fois : une inversion de lignes ou de colonnes rougit.
        assertEquals("ai voulu", vouloir.form(Tense.PAST_COMPOUND, FIRST_SINGULAR))
        assertEquals("vouliez", vouloir.form(Tense.IMPERFECT, SECOND_PLURAL))
        assertEquals("veut", vouloir.form(Tense.PRESENT, THIRD_SINGULAR))
        assertEquals("allons vouloir", vouloir.form(Tense.NEAR_FUTURE, FIRST_PLURAL))
        assertEquals("voudront", vouloir.form(Tense.FUTURE, THIRD_PLURAL))
    }

    @Test
    fun `an infinitive label finds its verb`() {
        assertEquals("mangeons", forms("manger").form(Tense.PRESENT, FIRST_PLURAL))
    }

    @Test
    fun `a phrase conjugates its first word and keeps the rest`() {
        val avoirBesoin = forms("avoir besoin")

        assertEquals("avoir besoin", avoirBesoin.infinitive)
        assertEquals("avaient besoin", avoirBesoin.form(Tense.IMPERFECT, THIRD_PLURAL))
        assertEquals("vais avoir besoin", avoirBesoin.form(Tense.NEAR_FUTURE, FIRST_SINGULAR))
        assertEquals("ai eu besoin", avoirBesoin.form(Tense.PAST_COMPOUND, FIRST_SINGULAR))
    }

    @Test
    fun `a form shared by two verbs goes to the more frequent one`() {
        // « suis » : être et suivre. Être vient d'abord dans la table.
        assertEquals("être", forms("suis").infinitive)
    }

    @Test
    fun `case, final punctuation and a non breaking space do not matter`() {
        assertEquals("manger", forms("Manger !").infinitive)
        assertEquals("avoir besoin", forms("avoir\u00a0besoin").infinitive)
    }

    @Test
    fun `a label outside the table has no forms`() {
        assertNull(table.formsOf("ubériser"))
        assertNull(table.formsOf("eau"))
        assertNull(table.formsOf(""))
        assertNull(table.formsOf(" ! "))
    }

    @Test
    fun `a form shared by two verbs of the table is a choice, not an accident`() {
        // Chaque forme partagée est rendue au premier verbe de la table : la liste est figée ici pour
        // qu'un verbe ajouté qui en crée une nouvelle soit décidé, pas subi.
        val lines = File("src/main/assets/conjugations.tsv").readLines().filter { it.isNotEmpty() }
        val simpleTenses = listOf(Tense.PRESENT, Tense.IMPERFECT, Tense.FUTURE)
        val owners = HashMap<String, MutableSet<String>>()
        for (line in lines) {
            val infinitive = line.substringBefore('\t')
            val forms = checkNotNull(table.formsOf(infinitive))
            for (tense in simpleTenses) {
                for (person in GrammaticalPerson.entries) owners.getOrPut(forms.form(tense, person)) { mutableSetOf() } += infinitive
            }
        }

        assertEquals(mapOf("suis" to setOf("être", "suivre")), owners.filterValues { it.size > 1 })
    }

    @Test
    fun `a duplicated infinitive is set aside and the first one is kept`() {
        val first = (listOf("aimer") + List(30) { "a$it" }).joinToString("\t")
        val second = (listOf("aimer") + List(30) { "b$it" }).joinToString("\t")
        val parsed = ConjugationTable.parse("$first\n$second\n")

        assertEquals(listOf(2), parsed.malformedLines)
        assertEquals("a12", parsed.formsOf("aimer")?.form(Tense.PRESENT, FIRST_SINGULAR))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `verb forms of the wrong size cannot exist`() {
        VerbForms("aimer", persistentListOf("aime"))
    }

    @Test
    fun `a line with the wrong column count is set aside and the others are read`() {
        val good = (listOf("aimer") + List(30) { "f$it" }).joinToString("\t")
        val short = "jouer\tjoue"
        val parsed = ConjugationTable.parse("$short\n$good\n")

        assertEquals(listOf(1), parsed.malformedLines)
        assertEquals(1, parsed.verbCount)
        assertEquals("f12", parsed.formsOf("aimer")?.form(Tense.PRESENT, FIRST_SINGULAR))
    }

    @Test
    fun `every verb of the core vocabulary has its forms`() {
        val french = resourceStrings("values", "strings_vocabulary.xml")
        val vocabulary = CoreVocabulary.build { id -> checkNotNull(french[stringNamesById[id]]) { "no French text for $id" } }
        val verbs = vocabulary.cells.filter { WordCategory.fromStoredName(it.category) == WordCategory.VERB }.map { it.label }

        // Les verbes d'intention conjugués de l'accueil, et l'infinitif qu'on attend pour chacun.
        val expected = mapOf("veux" to "vouloir", "peux" to "pouvoir", "aime" to "aimer", "vais" to "aller", "suis" to "être", "ai" to "avoir")
        // Sans quoi un vocabulaire mal lu (aucun verbe) passerait la boucle sans rien vérifier.
        assertTrue(verbs.containsAll(expected.keys))
        for (label in verbs) {
            assertEquals(label, expected[label] ?: label, table.formsOf(label)?.infinitive)
        }
    }

    @Test
    fun `the table follows the order of verbs txt`() {
        // L'ordre décide d'une forme partagée (« suis ») : une liste modifiée sans régénérer la table rougit ici.
        val listed = File("../tools/conjugaison/verbs.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val generated = File("src/main/assets/conjugations.tsv").readLines().filter { it.isNotEmpty() }.map { it.substringBefore('\t') }

        assertEquals(listed, generated)
    }

    @Test
    fun `every verb of the table starting with h has a mute h`() {
        // L'élision traite tout h d'une forme conjuguée comme muet (« j'habite ») : un h aspiré
        // (« hurler » : « je hurle ») ajouté à la table doit d'abord être traité dans Elision.kt.
        val muteH = setOf("habiter", "habiller")
        val generated = File("src/main/assets/conjugations.tsv").readLines().filter { it.isNotEmpty() }.map { it.substringBefore('\t') }

        assertEquals(muteH, generated.filter { it.startsWith("h") }.toSet())
    }
}
