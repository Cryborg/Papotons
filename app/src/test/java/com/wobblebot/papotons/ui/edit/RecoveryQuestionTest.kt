package com.wobblebot.papotons.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RecoveryQuestionTest {
    @Test
    fun `only the product opens the gate`() {
        val question = RecoveryQuestion(17, 8)

        assertTrue(question.isAnsweredBy("136"))
        assertTrue(question.isAnsweredBy(" 136 "))
        assertFalse(question.isAnsweredBy("25"))
        assertFalse(question.isAnsweredBy("178"))
        assertFalse(question.isAnsweredBy(""))
    }

    @Test
    fun `factors cover 12 to 29 and 3 to 9`() {
        val random = Random(seed = 7)
        val questions = (1..2_000).map { RecoveryQuestion.random(random) }

        assertEquals((12..29).toSet(), questions.map { it.first }.toSet())
        assertEquals((3..9).toSet(), questions.map { it.second }.toSet())
    }
}
