package com.wobblebot.papotons.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ParentalQuestionTest {
    @Test
    fun `only the sum opens the gate`() {
        val question = ParentalQuestion(14, 7)

        assertTrue(question.isAnsweredBy("21"))
        assertTrue(question.isAnsweredBy(" 21 "))
        assertFalse(question.isAnsweredBy("147"))
        assertFalse(question.isAnsweredBy("7"))
        assertFalse(question.isAnsweredBy(""))
        assertFalse(question.isAnsweredBy("quinze"))
    }

    @Test
    fun `questions spread over fourteen sums from 15 to 28`() {
        val random = Random(seed = 42)
        val questions = (1..2_000).map { ParentalQuestion.random(random) }

        assertEquals((15..28).toSet(), questions.map { it.first + it.second }.toSet())
        assertEquals((12..19).toSet(), questions.map { it.first }.toSet())
        assertEquals((3..9).toSet(), questions.map { it.second }.toSet())
    }
}
