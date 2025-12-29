package com.github.albertocavalcante.groovyparser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProblemTest {

    @Test
    fun `problem has message and position`() {
        val position = Position(10, 5)
        val problem = Problem("Unexpected token", position)

        assertThat(problem.message).isEqualTo("Unexpected token")
        assertThat(problem.position).isEqualTo(position)
    }

    @Test
    fun `problem with range has start and end positions`() {
        val range = Range(Position(1, 1), Position(1, 10))
        val problem = Problem("Invalid syntax", range)

        assertThat(problem.message).isEqualTo("Invalid syntax")
        assertThat(problem.range).isEqualTo(range)
    }

    @Test
    fun `problem toString includes message and position`() {
        val problem = Problem("Error message", Position(5, 3))

        val str = problem.toString()

        assertThat(str).contains("Error message")
        assertThat(str).contains("5")
        assertThat(str).contains("3")
    }

    @Test
    fun `problems can be compared by position`() {
        val problem1 = Problem("First", Position(1, 1))
        val problem2 = Problem("Second", Position(2, 1))
        val problem3 = Problem("Third", Position(1, 5))

        val sorted = listOf(problem2, problem3, problem1).sortedWith(Problem.COMPARATOR_BY_BEGIN_POSITION)

        assertThat(sorted).containsExactly(problem1, problem3, problem2)
    }
}
