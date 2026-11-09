package com.github.albertocavalcante.gvy.spock

import com.github.albertocavalcante.gvy.spock.UnrollSegmentKind.INVALID
import com.github.albertocavalcante.gvy.spock.UnrollSegmentKind.METHOD
import com.github.albertocavalcante.gvy.spock.UnrollSegmentKind.PROPERTY
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Expectations follow `UnrollIterationNameProvider` at spock-2.3, spock-2.4 and spock-2.5 (identical). */
class SpockUnrollPatternTest {
    private fun single(pattern: String) = SpockUnrollPattern.parse(pattern).single()

    @Test
    fun `plain data variable reference with exclusive pattern offsets`() {
        val placeholder = single("maximum of #a and b")

        assertEquals("a", placeholder.expression)
        assertEquals(11 until 13, placeholder.range)
        assertEquals(UnrollRoot.DataVariable("a"), placeholder.root)
        assertEquals(12 until 13, placeholder.rootRange)
        assertTrue(placeholder.tail.isEmpty())
    }

    @Test
    fun `property and zero argument method tails never become roots`() {
        val placeholder = single("#person.name.size()")

        assertEquals(UnrollRoot.DataVariable("person"), placeholder.root)
        assertEquals(listOf(PROPERTY to "name", METHOD to "size"), placeholder.tail.map { it.kind to it.name })
        assertEquals(listOf(8 until 12, 13 until 19), placeholder.tail.map { it.range })
    }

    @Test
    fun `built ins keep their tails`() {
        val placeholder = single("#featureName.toUpperCase()")

        assertEquals(UnrollRoot.Builtin("featureName"), placeholder.root)
        assertEquals(listOf(METHOD to "toUpperCase"), placeholder.tail.map { it.kind to it.name })
        listOf("iterationIndex", "dataVariables", "dataVariablesWithIndex").forEach { name ->
            assertEquals(UnrollRoot.Builtin(name), single("#$name").root)
        }
    }

    @Test
    fun `trailing dots are part of the replaced text but not segments`() {
        val placeholder = single("#a. end")

        assertEquals("a.", placeholder.expression)
        assertEquals(0 until 3, placeholder.range)
        assertTrue(placeholder.tail.isEmpty())
    }

    @Test
    fun `empty and malformed segments are invalid rather than guessed`() {
        assertEquals(listOf(INVALID to "", PROPERTY to "b"), single("#a..b").tail.map { it.kind to it.name })
        assertEquals(listOf(INVALID), single("#a.()").tail.map { it.kind })
        assertEquals(listOf(INVALID), single("#a.b()c").tail.map { it.kind })
        assertEquals(listOf(INVALID), single("#a.b()()").tail.map { it.kind })
    }

    @Test
    fun `a call on the root is an invalid root, not the variable before the parentheses`() {
        assertEquals(UnrollRoot.Invalid("a()"), single("#a()").root)
        assertEquals(UnrollRoot.Invalid("x()()"), single("#x()()").root)
    }

    @Test
    fun `adjacent and doubled hashes follow the upstream matcher`() {
        assertEquals(listOf("x", "y"), SpockUnrollPattern.parse("#x#y").map { it.expression })
        assertEquals(listOf(1 until 3), SpockUnrollPattern.parse("##x").map { it.range })
        assertEquals("\$x", single("#\$x").expression)
        assertEquals("a1_b", single("#a1_b-c").expression)
    }

    @ParameterizedTest
    @ValueSource(strings = ["#1a", "# x", "#é", "#", "plain", "#(x)", "#.x"])
    fun `text that the upstream matcher ignores has no placeholders`(pattern: String) {
        assertTrue(SpockUnrollPattern.parse(pattern).isEmpty())
    }

    @Test
    fun `identifier characters are ASCII only like the upstream pattern`() {
        val placeholder = single("#aé")
        assertEquals("a", placeholder.expression)
        assertIs<UnrollRoot.DataVariable>(placeholder.root)
    }
}
