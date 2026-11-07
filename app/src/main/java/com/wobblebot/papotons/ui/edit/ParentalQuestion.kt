package com.wobblebot.papotons.ui.edit

import kotlin.random.Random

private const val SMALLEST_FIRST_TERM = 12
private const val LARGEST_FIRST_TERM = 19
private const val SMALLEST_SECOND_TERM = 3
private const val LARGEST_SECOND_TERM = 9

/**
 * Le contrôle parental : un nombre de 12 à 19 plus un chiffre de 3 à 9 (14 sommes possibles, de 15
 * à 28). Hors de portée d'un enfant qui tape au hasard ou qui a vu une réponse par-dessus l'épaule,
 * immédiat pour un adulte. Une mauvaise réponse referme le contrôle (pas d'essais en rafale).
 */
data class ParentalQuestion(val first: Int, val second: Int) {
    fun isAnsweredBy(answer: String): Boolean = answer.trim().toIntOrNull() == first + second

    companion object {
        fun random(random: Random = Random.Default) = ParentalQuestion(
            first = random.nextInt(SMALLEST_FIRST_TERM, LARGEST_FIRST_TERM + 1),
            second = random.nextInt(SMALLEST_SECOND_TERM, LARGEST_SECOND_TERM + 1),
        )
    }
}
