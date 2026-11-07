package com.wobblebot.papotons.domain

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

private const val ELIDABLE_SUBJECT = "je"
private const val APOSTROPHE = "'"
private const val MUTE_H = 'h'
/** Voyelles en tête de mot (partagées avec les tests du vocabulaire). */
internal const val VOWELS = "aàâäeéèêëiîïoôöuùûüyÿœæ"
/** Les mots d'une case, séparés par une espace ou une espace insécable (« quoi ? », « avoir besoin »). */
internal fun splitWords(text: String): List<String> = text.split(' ', '\u00a0')

/**
 * Terminaisons de l'infinitif : « je aller » ne devient pas « j'aller ». Elles écartent aussi les
 * formes conjuguées en -re (« j'adore », « j'ouvre ») : mieux vaut « je adore » qu'un « j' » faux.
 */
private val INFINITIVE_ENDINGS = listOf("er", "ir", "re")

/**
 * Un mot de la phrase en cours, tel qu'il a été touché. [isConjugated] : une forme prise dans la
 * page des formes d'un verbe, conjuguée à coup sûr (la table le dit, pas sa terminaison).
 */
data class SentenceWord(val label: String, val category: WordCategory?, val picture: Picture?, val isConjugated: Boolean = false)

/**
 * Un mot de la phrase tel qu'il s'affiche et se dit, avec l'image de chaque case touchée qui en a
 * une : une élision en garde jusqu'à deux (« j'ai » : celle de « je », puis celle du verbe).
 */
@Immutable
data class SentenceToken(val text: String, val pictures: ImmutableList<Picture>)

/**
 * Les mots à afficher et à dire : « je » devant un verbe conjugué qui commence par une voyelle
 * devient « j' » collé au verbe (« j'ai », « j'aime »). Devant un infinitif (« je aller ») ou un
 * autre mot (« je eau ») rien ne change : la voix doit dire ce qui a été touché. Une forme de la page
 * des formes s'élide aussi devant un h (« j'habite ») et en -re (« j'ouvre ») : les verbes en h de la
 * table sont tous à h muet (`ConjugationTableTest` le vérifie).
 */
fun sentenceTokens(words: List<SentenceWord>): List<SentenceToken> = buildList {
    var index = 0
    while (index < words.size) {
        val word = words[index]
        val next = words.getOrNull(index + 1)
        if (next != null && word.label.equals(ELIDABLE_SUBJECT, ignoreCase = true) && next.isElidingVerb()) {
            // take(1) garde la casse : « Je ai » donne « J'ai ».
            add(SentenceToken(word.label.take(1) + APOSTROPHE + next.label, picturesOf(word, next)))
            index += 2
        } else {
            add(SentenceToken(word.label, picturesOf(word)))
            index += 1
        }
    }
}

private fun picturesOf(vararg words: SentenceWord): ImmutableList<Picture> =
    words.mapNotNull { it.picture }.toImmutableList()

private fun SentenceWord.isElidingVerb(): Boolean {
    if (category != WordCategory.VERB) return false
    val first = label.firstOrNull()?.lowercaseChar()
    if (isConjugated) return first != null && (first in VOWELS || first == MUTE_H)
    val startsWithVowel = first?.let { it in VOWELS } == true
    // Le premier mot seul : « aller vite » ou « aller ! » restent des infinitifs.
    val firstWord = splitWords(label).first().trimEnd { !it.isLetter() }
    val isInfinitive = INFINITIVE_ENDINGS.any { firstWord.endsWith(it, ignoreCase = true) }
    return startsWithVowel && !isInfinitive
}
