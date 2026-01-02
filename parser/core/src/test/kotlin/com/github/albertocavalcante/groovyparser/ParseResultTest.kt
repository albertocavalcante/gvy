package com.github.albertocavalcante.groovyparser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class ParseResultTest {

    @Test
    fun `successful result has result present`() {
        val result = ParseResult("parsed value", emptyList())

        assertThat(result.isSuccessful).isTrue()
        assertThat(result.result).isEqualTo(Optional.of("parsed value"))
        assertThat(result.problems).isEmpty()
    }

    @Test
    fun `result with problems is not successful`() {
        val problem = Problem("Syntax error", Position(1, 1))
        val result = ParseResult("value", listOf(problem))

        assertThat(result.isSuccessful).isFalse()
        assertThat(result.problems).hasSize(1)
    }

    @Test
    fun `result with null value is not successful`() {
        val result = ParseResult<String>(null, emptyList())

        assertThat(result.isSuccessful).isFalse()
        assertThat(result.result).isEmpty()
    }

    @Test
    fun `ifSuccessful executes consumer when successful`() {
        val result = ParseResult("value", emptyList())
        var executed = false

        result.ifSuccessful { executed = true }

        assertThat(executed).isTrue()
    }

    @Test
    fun `ifSuccessful does not execute consumer when not successful`() {
        val result = ParseResult<String>(null, emptyList())
        var executed = false

        result.ifSuccessful { executed = true }

        assertThat(executed).isFalse()
    }

    @Test
    fun `getProblems returns immutable list`() {
        val problem = Problem("Error", Position(1, 1))
        val result = ParseResult("value", listOf(problem))

        assertThat(result.problems).containsExactly(problem)
    }
}
