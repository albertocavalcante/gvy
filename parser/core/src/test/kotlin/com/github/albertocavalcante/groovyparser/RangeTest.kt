package com.github.albertocavalcante.groovyparser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RangeTest {

    @Test
    fun `range has begin and end positions`() {
        val begin = Position(1, 1)
        val end = Position(1, 10)
        val range = Range(begin, end)

        assertThat(range.begin).isEqualTo(begin)
        assertThat(range.end).isEqualTo(end)
    }

    @Test
    fun `range contains position within bounds`() {
        val range = Range(Position(1, 1), Position(3, 10))

        assertThat(range.contains(Position(2, 5))).isTrue()
        assertThat(range.contains(Position(1, 1))).isTrue()
        assertThat(range.contains(Position(3, 10))).isTrue()
    }

    @Test
    fun `range does not contain position outside bounds`() {
        val range = Range(Position(2, 5), Position(2, 15))

        assertThat(range.contains(Position(1, 5))).isFalse()
        assertThat(range.contains(Position(2, 4))).isFalse()
        assertThat(range.contains(Position(2, 16))).isFalse()
        assertThat(range.contains(Position(3, 5))).isFalse()
    }

    @Test
    fun `range toString is human readable`() {
        val range = Range(Position(1, 1), Position(1, 10))

        assertThat(range.toString()).contains("1")
        assertThat(range.toString()).contains("10")
    }

    @Test
    fun `ranges are equal when begin and end match`() {
        val range1 = Range(Position(1, 1), Position(1, 10))
        val range2 = Range(Position(1, 1), Position(1, 10))

        assertThat(range1).isEqualTo(range2)
        assertThat(range1.hashCode()).isEqualTo(range2.hashCode())
    }

    @Test
    fun `range can be created from line numbers`() {
        val range = Range.range(1, 1, 1, 10)

        assertThat(range.begin).isEqualTo(Position(1, 1))
        assertThat(range.end).isEqualTo(Position(1, 10))
    }
}
