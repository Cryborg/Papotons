package com.wobblebot.papotons.domain

import com.wobblebot.papotons.data.board.CoreVocabulary
import com.wobblebot.papotons.resourceStrings
import com.wobblebot.papotons.stringNamesById
import org.junit.Assert.assertEquals
import org.junit.Test

class ElisionTest {
    private fun verb(label: String) = SentenceWord(label, WordCategory.VERB, picture = null)
    private fun word(label: String, category: WordCategory? = null) = SentenceWord(label, category, picture = null)
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
    fun `je before a form taken from the forms page is elided, even with h or -re`() {
        fun conjugated(label: String) = SentenceWord(label, WordCategory.VERB, picture = null, isConjugated = true)

        assertEquals(listOf("j'habite"), texts(listOf(subject, conjugated("habite"))))
        assertEquals(listOf("j'ouvre"), texts(listOf(subject, conjugated("ouvre"))))
        assertEquals(listOf("je", "vais ouvrir"), texts(listOf(subject, conjugated("vais ouvrir"))))
    }

    @Test
    fun `je at the end of the sentence stays`() {
        assertEquals(listOf("veux", "je"), texts(listOf(verb("veux"), subject)))
    }

    @Test
    fun `each word keeps the pictogram of its cell and an elision keeps both`() {
        val tokens = sentenceTokens(
            listOf(
                SentenceWord("je", WordCategory.PEOPLE, picture = Picture.Pictogram(10)),
                SentenceWord("ai", WordCategory.VERB, picture = Picture.Pictogram(20)),
                SentenceWord("faim", WordCategory.NOUN, picture = Picture.Pictogram(30)),
                SentenceWord("vite", null, picture = null),
                SentenceWord("je", WordCategory.PEOPLE, picture = null),
                SentenceWord("aime", WordCategory.VERB, picture = Picture.Pictogram(40)),
            ),
        )

        assertEquals(listOf(listOf(10L, 20L), listOf(30L), emptyList(), listOf(40L)), tokens.map { token -> token.pictures.map { (it as Picture.Pictogram).id } })
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

    @Test
    fun `an elision keeps a photo and a pictogram in order`() {
        val tokens = sentenceTokens(
            listOf(
                SentenceWord("je", WordCategory.PEOPLE, Picture.Photo("moi.jpg")),
                SentenceWord("ai", WordCategory.VERB, Picture.Pictogram(20)),
            ),
        )

        assertEquals(listOf(listOf(Picture.Photo("moi.jpg"), Picture.Pictogram(20))), tokens.map { it.pictures })
    }
}
