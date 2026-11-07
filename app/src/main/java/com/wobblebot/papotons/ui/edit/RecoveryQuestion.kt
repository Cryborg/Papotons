package com.wobblebot.papotons.ui.edit

import kotlin.random.Random

private const val SMALLEST_FIRST_FACTOR = 12
private const val LARGEST_FIRST_FACTOR = 29
private const val SMALLEST_SECOND_FACTOR = 3
private const val LARGEST_SECOND_FACTOR = 9

/**
 * « Code oublié ? » : un nombre de 12 à 29 fois un chiffre de 3 à 9. Plus dur que l'addition du
 * contrôle parental, faisable de tête par un adulte. Une mauvaise réponse referme le contrôle.
 */
data class RecoveryQuestion(val first: Int, val second: Int) {
    fun isAnsweredBy(answer: String): Boolean = answer.trim().toIntOrNull() == first * second

    companion object {
        fun random(random: Random = Random.Default) = RecoveryQuestion(
            first = random.nextInt(SMALLEST_FIRST_FACTOR, LARGEST_FIRST_FACTOR + 1),
            second = random.nextInt(SMALLEST_SECOND_FACTOR, LARGEST_SECOND_FACTOR + 1),
        )
    }
}
