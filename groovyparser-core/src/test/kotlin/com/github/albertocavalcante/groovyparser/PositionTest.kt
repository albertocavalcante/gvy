package com.github.albertocavalcante.groovyparser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PositionTest {

    @Test
    fun `position has line and column`() {
        val position = Position(10, 5)

        assertThat(position.line).isEqualTo(10)
        assertThat(position.column).isEqualTo(5)
    }

    @Test
    fun `positions are equal when line and column match`() {
        val pos1 = Position(1, 1)
        val pos2 = Position(1, 1)

        assertThat(pos1).isEqualTo(pos2)
        assertThat(pos1.hashCode()).isEqualTo(pos2.hashCode())
    }

    @Test
    fun `positions can be compared`() {
        val pos1 = Position(1, 1)
        val pos2 = Position(1, 5)
        val pos3 = Position(2, 1)

        assertThat(pos1).isLessThan(pos2)
        assertThat(pos2).isLessThan(pos3)
        assertThat(pos1).isLessThan(pos3)
    }

    @Test
    fun `position toString is human readable`() {
        val position = Position(10, 5)

        assertThat(position.toString()).isEqualTo("(line 10, col 5)")
    }

    @Test
    fun `FIRST_LINE and FIRST_COLUMN constants`() {
        assertThat(Position.FIRST_LINE).isEqualTo(1)
        assertThat(Position.FIRST_COLUMN).isEqualTo(1)
    }

    @Test
    fun `HOME position is line 1 column 1`() {
        assertThat(Position.HOME).isEqualTo(Position(1, 1))
    }
}
