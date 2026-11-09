package com.github.albertocavalcante.groovyparser.provider

import com.github.albertocavalcante.groovyparser.api.model.NodeInfo
import com.github.albertocavalcante.groovyparser.api.model.NodeKind
import com.github.albertocavalcante.groovyparser.api.model.Position
import com.github.albertocavalcante.groovyparser.api.model.Range
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoreParseUnitNodeAtTest {

    @Test
    fun `nodeAt returns the class at its name with exact source text`() {
        val source = "class Foo { String name }"
        val parseUnit = parseCore(source)

        assertThat(parseUnit.isSuccessful).isTrue()
        assertThat(parseUnit.diagnostics()).isEmpty()
        assertThat(parseUnit.nodeAt(Position(1, 7))).isEqualTo(
            NodeInfo(NodeKind.CLASS, "Foo", Range(Position(1, 1), Position(1, 26)), source),
        )
    }

    @Test
    fun `nodeAt returns the field rather than its containing class`() {
        val parseUnit = parseCore(CLASS_WITH_MEMBERS)

        assertThat(parseUnit.nodeAt(Position(2, 9))).isEqualTo(
            NodeInfo(NodeKind.FIELD, "name", Range(Position(2, 2), Position(2, 13)), "String name"),
        )
    }

    @Test
    fun `nodeAt returns the method at its declaration`() {
        val parseUnit = parseCore(CLASS_WITH_MEMBERS)

        assertThat(parseUnit.nodeAt(Position(3, 9))).isEqualTo(
            NodeInfo(
                NodeKind.METHOD,
                "greet",
                Range(Position(3, 2), Position(3, 25)),
                "String greet() { name }",
            ),
        )
    }

    @Test
    fun `nodeAt returns the deepest expression inside a method`() {
        val parseUnit = parseCore(CLASS_WITH_MEMBERS)

        assertThat(parseUnit.nodeAt(Position(3, 19))).isEqualTo(
            NodeInfo(NodeKind.VARIABLE_REFERENCE, "name", Range(Position(3, 19), Position(3, 23)), "name"),
        )
    }

    @Test
    fun `nodeAt includes the start and final character but excludes the native end position`() {
        val source = "class Foo {}"
        val parseUnit = parseCore(source)
        val expected = NodeInfo(NodeKind.CLASS, "Foo", Range(Position(1, 1), Position(1, 13)), source)

        assertThat(parseUnit.nodeAt(Position(1, 1))).isEqualTo(expected)
        assertThat(parseUnit.nodeAt(Position(1, 12))).isEqualTo(expected)
        assertThat(parseUnit.nodeAt(Position(1, 13))).isNull()
    }

    @Test
    fun `nodeAt does not extend the first class into the next line`() {
        val parseUnit = parseCore("class One {}\nclass Two {}")

        assertThat(parseUnit.nodeAt(Position(1, 13))).isNull()
        assertThat(parseUnit.nodeAt(Position(2, 7))).isEqualTo(
            NodeInfo(NodeKind.CLASS, "Two", Range(Position(2, 1), Position(2, 13)), "class Two {}"),
        )
    }

    @Test
    fun `nodeAt preserves line endings when extracting a multiline class`() {
        val source = CLASS_WITH_MEMBERS.replace("\n", "\r\n")
        val parseUnit = parseCore(source)

        assertThat(parseUnit.nodeAt(Position(1, 7))).isEqualTo(
            NodeInfo(NodeKind.CLASS, "Foo", Range(Position(1, 1), Position(4, 2)), source),
        )
        assertThat(parseUnit.nodeAt(Position(3, 19))?.text).isEqualTo("name")
    }

    @Test
    fun `nodeAt retains Groovy line coordinates for bare carriage returns`() {
        val source = CLASS_WITH_MEMBERS.replace("\n", "\r")
        val parseUnit = parseCore(source)

        assertThat(parseUnit.nodeAt(Position(1, 7))).isEqualTo(
            NodeInfo(NodeKind.CLASS, "Foo", Range(Position(1, 1), Position(1, 52)), source),
        )
        assertThat(parseUnit.nodeAt(Position(1, 21))).isEqualTo(
            NodeInfo(NodeKind.FIELD, "name", Range(Position(1, 14), Position(1, 25)), "String name"),
        )
    }

    @Test
    fun `nodeAt does not guess text or lookup columns after a supplementary character`() {
        val parseUnit = parseCore("class Foo { String emoji = '😀'; String name }")

        assertThat(parseUnit.nodeAt(Position(1, 7))).isEqualTo(
            NodeInfo(NodeKind.CLASS, "Foo", Range(Position(1, 1), Position(1, 46)), null),
        )
        assertThat(parseUnit.nodeAt(Position(1, 33))).isNull()
    }

    @Test
    fun `nodeAt preserves supplementary text on an intermediate line with unambiguous endpoints`() {
        val source = "class Foo {\n String emoji = '😀'\n}"
        val parseUnit = parseCore(source)

        assertThat(parseUnit.nodeAt(Position(1, 7))).isEqualTo(
            NodeInfo(NodeKind.CLASS, "Foo", Range(Position(1, 1), Position(3, 2)), source),
        )
    }

    @Test
    fun `nodeAt preserves BMP unicode source text`() {
        val source = "class Café { String résumé }"
        val parseUnit = parseCore(source)

        assertThat(parseUnit.nodeAt(Position(1, 7))).isEqualTo(
            NodeInfo(NodeKind.CLASS, "Café", Range(Position(1, 1), Position(1, source.length + 1)), source),
        )
    }

    @Test
    fun `nodeAt traverses synthetic script containers to find the method call`() {
        val source = "println 'hello'"
        val parseUnit = parseCore(source)

        assertThat(parseUnit.isSuccessful).isTrue()
        assertThat(parseUnit.nodeAt(Position(1, 1))).isEqualTo(
            NodeInfo(NodeKind.METHOD_CALL, "println", Range(Position(1, 1), Position(1, 16)), source),
        )
    }

    @Test
    fun `nodeAt traverses a script call to find its literal argument`() {
        val parseUnit = parseCore("println 'hello'")

        assertThat(parseUnit.nodeAt(Position(1, 10))).isEqualTo(
            NodeInfo(NodeKind.LITERAL, null, Range(Position(1, 9), Position(1, 16)), "'hello'"),
        )
        assertThat(parseUnit.nodeAt(Position(1, 16))).isNull()
    }

    @Test
    fun `nodeAt returns null for positions outside the source`() {
        val parseUnit = parseCore(CLASS_WITH_MEMBERS)

        for (position in listOf(Position(0, 1), Position(1, 0), Position(-1, 1), Position(2, 100), Position(100, 1))) {
            assertThat(parseUnit.nodeAt(position)).describedAs("lookup at %s", position).isNull()
        }
    }

    @Test
    fun `nodeAt returns null when partial syntax has no recoverable source node`() {
        val parseUnit = parseCore("class Broken {")

        assertThat(parseUnit.isSuccessful).isFalse()
        assertThat(parseUnit.diagnostics()).isNotEmpty()
        assertThat(parseUnit.nodeAt(Position(1, 7))).isNull()
    }

    @Test
    fun `nodeAt returns null for empty source`() {
        val parseUnit = parseCore("")

        assertThat(parseUnit.nodeAt(Position(1, 1))).isNull()
    }

    private fun parseCore(source: String): CoreParseUnit = CoreParserProvider().parse(source) as CoreParseUnit

    private companion object {
        const val CLASS_WITH_MEMBERS = "class Foo {\n String name\n String greet() { name }\n}"
    }
}
