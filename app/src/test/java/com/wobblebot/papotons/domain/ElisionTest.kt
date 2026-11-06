package com.wobblebot.papotons.domain

import com.wobblebot.papotons.data.board.CoreVocabulary
import com.wobblebot.papotons.resourceStrings
import com.wobblebot.papotons.stringNamesById
import org.junit.Assert.assertEquals
import org.junit.Test

class ElisionTest {
    private fun verb(label: String) = SentenceWord(label, WordCategory.VERB, pictogramId = null)
    private fun word(label: String, category: WordCategory? = null) = SentenceWord(label, category, pictogramId = null)
    private val subject = word("je", WordCategory.PEOPLE)

    private fun texts(words: List<SentenceWord>) = sentenceTokens(words).map { it.text }

    private fun afterSubject(verbLabel: String) = texts(listOf(subject, verb(verbLabel)))

    @Test
    fun `je before a conjugated verb starting with a vowel is elided`() {
        assertEquals(listOf("j'ai", "faim"), texts(listOf(subject, verb("ai"), word("faim", WordCategory.NOUN))))
        assertEquals(listOf("j'aime"), afterSubject("aime"))
        assertEquals(listOf("j'écoute"), afterSubject("écoute"))
        assertEquals(listOf("j'imagine"), afterSubject("imagine"))
        assertEquals(listOf("j'utilise"), afterSubject("utilise"))
        assertEquals(listOf("j'Écoute"), afterSubject("Écoute"))
    }

    @Test
    fun `je before an infinitive stays as touched`() {
        // « j'aller » n'existe pas : un infinitif garde « je » tel qu'il a été touché.
        for (infinitive in listOf("aller", "écouter", "aider", "arrêter", "ouvrir", "attendre", "aller vite", "aller !")) {
            assertEquals(infinitive, listOf("je", infinitive), afterSubject(infinitive))
        }
    }

    @Test
    fun `je before a consonant stays`() {
        assertEquals(listOf("je", "veux"), afterSubject("veux"))
    }

    @Test
    fun `je before a word that is not a verb stays as touched`() {
        // La voix doit dire ce qui a été touché : « j'eau » ferait disparaître le « je ».
        assertEquals(listOf("je", "eau"), texts(listOf(subject, word("eau", WordCategory.NOUN))))
        assertEquals(listOf("je", "elle"), texts(listOf(subject, word("elle", WordCategory.PEOPLE))))
    }

    @Test
    fun `je before a word starting with h stays`() {
        assertEquals(listOf("je", "habite"), afterSubject("habite"))
    }

    @Test
    fun `je at the end of the sentence stays`() {
        assertEquals(listOf("veux", "je"), texts(listOf(verb("veux"), subject)))
    }

    @Test
    fun `each word keeps the pictogram of its cell and an elision keeps both`() {
        val tokens = sentenceTokens(
            listOf(
                SentenceWord("je", WordCategory.PEOPLE, pictogramId = 10),
                SentenceWord("ai", WordCategory.VERB, pictogramId = 20),
                SentenceWord("faim", WordCategory.NOUN, pictogramId = 30),
                SentenceWord("vite", null, pictogramId = null),
                SentenceWord("je", WordCategory.PEOPLE, pictogramId = null),
                SentenceWord("aime", WordCategory.VERB, pictogramId = 40),
            ),
        )

        assertEquals(listOf(listOf(10L, 20L), listOf(30L), emptyList(), listOf(40L)), tokens.map { it.pictogramIds })
    }

    @Test
    fun `a capital Je keeps its capital`() {
        assertEquals(listOf("J'ai"), texts(listOf(word("Je", WordCategory.PEOPLE), verb("ai"))))
    }

    @Test
    fun `two subjects are each handled`() {
        assertEquals(
            listOf("j'ai", "je", "veux", "j'aime"),
            texts(listOf(subject, verb("ai"), subject, verb("veux"), subject, verb("aime"))),
        )
    }

    @Test
    fun `every French verb of the core vocabulary that starts with a vowel is said right after je`() {
        // Le vrai texte français : une forme ajoutée au vocabulaire oblige à décider de son élision ici.
        val french = resourceStrings("values", "strings_vocabulary.xml")
        val vocabulary = CoreVocabulary.build { id -> checkNotNull(french[stringNamesById[id]]) { "no French text for $id" } }
        val expected = mapOf(
            "aime" to listOf("j'aime"),
            "ai" to listOf("j'ai"),
            "aller" to listOf("je", "aller"),
            "écouter" to listOf("je", "écouter"),
            "aider" to listOf("je", "aider"),
            "arrêter" to listOf("je", "arrêter"),
        )
        val vowelVerbs = vocabulary.cells
            .filter { WordCategory.fromStoredName(it.category) == WordCategory.VERB }
            .map { it.label }
            .filter { it.first().lowercaseChar() in VOWELS }
        assertEquals(expected.keys, vowelVerbs.toSet())
        for (label in vowelVerbs) {
            assertEquals(label, expected.getValue(label), afterSubject(label))
        }
    }
}
