package com.wobblebot.papotons.data.pictogram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** Ce qui part chez ARASAAC et ce qu'on lit de sa réponse (réponse réelle enregistrée). */
class ArasaacApiTest {
    @Test
    fun `a keyword is percent encoded as a path segment, never with a plus`() {
        assertEquals("https://api.arasaac.org/v1/pictograms/fr/search/pomme%20de%20terre", searchUrl("pomme de terre"))
        assertEquals("https://api.arasaac.org/v1/pictograms/fr/search/l%27eau", searchUrl("l'eau"))
        assertEquals("https://api.arasaac.org/v1/pictograms/fr/search/a%2Fb", searchUrl("a/b"))
        assertEquals("https://api.arasaac.org/v1/pictograms/fr/search/g%C3%A2teau", searchUrl(" gâteau "))
        // `+` et `%` encodés eux aussi ; les non réservés restent tels quels.
        assertEquals("https://api.arasaac.org/v1/pictograms/fr/search/a%2Bb%25c-d_e.f~g", searchUrl("a+b%c-d_e.f~g"))
    }

    @Test
    fun `only keywords with a letter or a digit are searched`() {
        assertTrue(isSearchable("eau"))
        assertFalse(isSearchable(""))
        assertFalse(isSearchable(" "))
        assertFalse(isSearchable(".."))
    }

    @Test
    fun `the image address follows the ARASAAC layout`() {
        assertEquals("https://static.arasaac.org/pictograms/2462/2462_300.png", imageUrl(2462))
    }

    @Test
    fun `results keep their order and drop sexual or violent pictograms`() {
        val json = checkNotNull(javaClass.classLoader).getResource("arasaac_search_pomme.json").readText()

        val results = parseSearchResults(json)

        assertEquals(listOf(2462L, 13644L, 11403L), results.map { it.id })
        assertEquals("jus de pomme", results.last().keyword)
    }

    @Test
    fun `an unreadable answer is a failure, never an empty list`() {
        assertThrows(ArasaacException::class.java) { parseSearchResults("<html>Erreur</html>") }
        assertEquals(emptyList<PictogramResult>(), parseSearchResults("[]"))
    }

    @Test
    fun `at most thirty results, counted after the filter`() {
        val json = (1..40).joinToString(prefix = "[", postfix = "]") { id ->
            val isSexual = id % 4 == 0
            """{"_id": $id, "sex": $isSexual, "violence": false, "keywords": [{"keyword": "k$id"}]}"""
        }

        val results = parseSearchResults(json)

        assertEquals(30, results.size)
        assertEquals((1..40).filter { it % 4 != 0 }.take(30).map(Int::toLong), results.map { it.id })
    }

    @Test
    fun `a result without keyword is kept unnamed, one without id is a failure`() {
        val flags = """"sex": false, "violence": false"""
        assertEquals(listOf(PictogramResult(7, "")), parseSearchResults("""[{"_id": 7, $flags}]"""))
        assertEquals(listOf(PictogramResult(7, "")), parseSearchResults("""[{"_id": 7, "keywords": [{"keyword": null}], $flags}]"""))
        // Le même picto deux fois : une seule vignette (clé unique dans la grille).
        assertEquals(listOf(7L), parseSearchResults("""[{"_id": 7, $flags}, {"_id": 7, $flags}]""").map { it.id })
        assertThrows(ArasaacException::class.java) { parseSearchResults("""[{"keywords": [], $flags}]""") }
        // Sans ses drapeaux (API changée), un résultat est écarté : le filtre enfant ne s'ouvre pas.
        assertEquals(emptyList<PictogramResult>(), parseSearchResults("""[{"_id": 8}]"""))
        // Un drapeau nul n'écarte que son résultat, sans faire échouer toute la recherche.
        assertEquals(listOf(7L), parseSearchResults("""[{"_id": 8, "sex": null, "violence": false}, {"_id": 7, $flags}]""").map { it.id })
    }

    @Test
    fun `a body is read whole up to its cap, never beyond`() {
        assertEquals(100, readCapped(ByteArrayInputStream(ByteArray(100)), maxBytes = 100).size)
        assertThrows(ArasaacException::class.java) { readCapped(ByteArrayInputStream(ByteArray(101)), maxBytes = 100) }
    }
}
