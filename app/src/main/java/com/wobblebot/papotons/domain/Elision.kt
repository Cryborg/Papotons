package com.wobblebot.papotons.domain

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

private const val ELIDABLE_SUBJECT = "je"
private const val APOSTROPHE = "'"
/** Voyelles en tête de mot (partagées avec les tests du vocabulaire). */
internal const val VOWELS = "aàâäeéèêëiîïoôöuùûüyÿœæ"

/**
 * Terminaisons de l'infinitif : « je aller » ne devient pas « j'aller ». Elles écartent aussi les
 * formes conjuguées en -re (« j'adore », « j'ouvre ») : mieux vaut « je adore » qu'un « j' » faux.
 */
private val INFINITIVE_ENDINGS = listOf("er", "ir", "re")

/** Un mot de la phrase en cours, tel qu'il a été touché. */
data class SentenceWord(val label: String, val category: WordCategory?, val pictogramId: Long?)

/**
 * Un mot de la phrase tel qu'il s'affiche et se dit, avec le picto de chaque case touchée qui en a
 * un : une élision en garde jusqu'à deux (« j'ai » : celui de « je », puis celui du verbe).
 */
@Immutable
data class SentenceToken(val text: String, val pictogramIds: ImmutableList<Long>)

/**
 * Les mots à afficher et à dire : « je » devant un verbe conjugué qui commence par une voyelle
 * devient « j' » collé au verbe (« j'ai », « j'aime »). Devant un infinitif (« je aller ») ou un
 * autre mot (« je eau ») rien ne change : la voix doit dire ce qui a été touché. Le h et les
 * formes conjuguées en -re (« j'adore », « j'ouvre ») attendent la grammaire (IDEE-1).
 */
fun sentenceTokens(words: List<SentenceWord>): List<SentenceToken> = buildList {
    var index = 0
    while (index < words.size) {
        val word = words[index]
        val next = words.getOrNull(index + 1)
        if (next != null && word.label.equals(ELIDABLE_SUBJECT, ignoreCase = true) && next.isElidingVerb()) {
            // take(1) garde la casse : « Je ai » donne « J'ai ».
            add(SentenceToken(word.label.take(1) + APOSTROPHE + next.label, pictogramIdsOf(word, next)))
            index += 2
        } else {
            add(SentenceToken(word.label, pictogramIdsOf(word)))
            index += 1
        }
    }
}

private fun pictogramIdsOf(vararg words: SentenceWord): ImmutableList<Long> =
    words.mapNotNull { it.pictogramId }.toImmutableList()

private fun SentenceWord.isElidingVerb(): Boolean {
    if (category != WordCategory.VERB) return false
    val startsWithVowel = label.firstOrNull()?.lowercaseChar()?.let { it in VOWELS } == true
    // Le premier mot seul : « aller vite » ou « aller ! » restent des infinitifs.
    val firstWord = label.split(' ', '\u00a0').first().trimEnd { !it.isLetter() }
    val isInfinitive = INFINITIVE_ENDINGS.any { firstWord.endsWith(it, ignoreCase = true) }
    return startsWithVowel && !isInfinitive
}
