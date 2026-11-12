// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.provider

import com.github.albertocavalcante.groovyparser.ParseResult
import com.github.albertocavalcante.groovyparser.api.model.NodeInfo
import com.github.albertocavalcante.groovyparser.api.model.NodeKind
import com.github.albertocavalcante.groovyparser.api.model.Position
import com.github.albertocavalcante.groovyparser.api.model.Range
import com.github.albertocavalcante.groovyparser.ast.AnnotationExpr
import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import com.github.albertocavalcante.groovyparser.Position as CorePosition
import com.github.albertocavalcante.groovyparser.Range as CoreRange

class CoreParseUnitAttachedNodesTest {
    @Test
    fun `constructor lookup retains the declaration name`() {
        val unit = CoreParserProvider().parse("class Foo { Foo() {} }")

        assertThat(unit.nodeAt(Position(1, 14))).isEqualTo(
            NodeInfo(NodeKind.CONSTRUCTOR, "Foo", Range(Position(1, 13), Position(1, 21)), "Foo() {}"),
        )
    }

    @Test
    fun `class annotation lookup searches annotation metadata`() {
        val unit = CoreParserProvider().parse("@Deprecated\nclass Foo {}")

        assertThat(unit.nodeAt(Position(1, 2))).isEqualTo(
            NodeInfo(NodeKind.ANNOTATION, "Deprecated", Range(Position(1, 1), Position(1, 12)), "@Deprecated"),
        )
    }

    @Test
    fun `parameter annotation lookup searches metadata below its method`() {
        val source = "class Foo { void accept(@Deprecated String name) {} }"
        val start = source.indexOf("@Deprecated") + 1
        val unit = CoreParserProvider().parse(source)

        assertThat(unit.nodeAt(Position(1, start + 1))).isEqualTo(
            NodeInfo(
                NodeKind.ANNOTATION,
                "Deprecated",
                Range(Position(1, start), Position(1, start + "@Deprecated".length)),
                "@Deprecated",
            ),
        )
    }

    @Test
    fun `annotation metadata is visible before its owner span`() {
        val declaration = ClassDeclaration("Foo").apply {
            range = CoreRange(CorePosition(2, 1), CorePosition(2, 13))
            addAnnotation(
                AnnotationExpr("Deprecated").apply {
                    range = CoreRange(CorePosition(1, 1), CorePosition(1, 12))
                },
            )
        }
        val tree = CompilationUnit().apply { addType(declaration) }
        val unit = CoreParseUnit("@Deprecated\nclass Foo {}", null, ParseResult(tree, emptyList()))

        assertThat(unit.nodeAt(Position(1, 2))).isEqualTo(
            NodeInfo(NodeKind.ANNOTATION, "Deprecated", Range(Position(1, 1), Position(1, 12)), "@Deprecated"),
        )
    }

    @Test
    fun `leading line comment lookup excludes the following newline`() {
        val unit = CoreParserProvider().parse("// hello\nclass Foo {}")
        val expected = NodeInfo(NodeKind.COMMENT, null, Range(Position(1, 1), Position(1, 9)), "// hello")

        assertThat(unit.nodeAt(Position(1, 4))).isEqualTo(expected)
        assertThat(unit.nodeAt(Position(1, 8))).isEqualTo(expected)
        assertThat(unit.nodeAt(Position(1, 9))).isNull()
    }

    @Test
    fun `orphan and attached comments can both be queried`() {
        val unit = CoreParserProvider().parse("// first\n// second\nclass Foo {}")

        assertThat(unit.nodeAt(Position(1, 4))).isEqualTo(
            NodeInfo(NodeKind.COMMENT, null, Range(Position(1, 1), Position(1, 9)), "// first"),
        )
        assertThat(unit.nodeAt(Position(2, 4))).isEqualTo(
            NodeInfo(NodeKind.COMMENT, null, Range(Position(2, 1), Position(2, 10)), "// second"),
        )
    }

    @Test
    fun `a descendant attachment is visible outside every enclosing declaration range`() {
        val unit = CoreParserProvider().parse("// header\nclass Foo {\n String name\n}")

        assertThat(unit.nodeAt(Position(1, 4))).isEqualTo(
            NodeInfo(NodeKind.COMMENT, null, Range(Position(1, 1), Position(1, 10)), "// header"),
        )
    }

    @Test
    fun `block comment lookup includes its closing delimiter`() {
        val unit = CoreParserProvider().parse("/* note */\nclass Foo {}")

        assertThat(unit.nodeAt(Position(1, 10))).isEqualTo(
            NodeInfo(NodeKind.COMMENT, null, Range(Position(1, 1), Position(1, 11)), "/* note */"),
        )
        assertThat(unit.nodeAt(Position(1, 11))).isNull()
    }

    @Test
    fun `javadoc lookup preserves original multiline CRLF text`() {
        val comment = "/** docs\r\n * second\r\n */"
        val unit = CoreParserProvider().parse("$comment\r\nclass Foo {}")

        assertThat(unit.nodeAt(Position(2, 5))).isEqualTo(
            NodeInfo(NodeKind.COMMENT, null, Range(Position(1, 1), Position(3, 4)), comment),
        )
    }

    @Test
    fun `bare CR comment coordinates are translated to native lookup rows`() {
        val comment = "/* docs\r * second\r */"
        val unit = CoreParserProvider().parse("$comment\rclass Foo {}")

        assertThat(unit.nodeAt(Position(1, comment.indexOf("second") + 1))).isEqualTo(
            NodeInfo(NodeKind.COMMENT, null, Range(Position(1, 1), Position(1, comment.length + 1)), comment),
        )
    }
}
