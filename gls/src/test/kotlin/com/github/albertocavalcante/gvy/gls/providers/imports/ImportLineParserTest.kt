// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.imports

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImportLineParserTest {

    @Test
    fun `reads a plain type import`() {
        val line = assertNotNull(ImportLineParser.parse("import java.util.List"))

        assertEquals(ImportTarget.Type.of("java.util.List", null), line.target)
        assertEquals("", line.trailing)
        assertEquals(setOf("List"), line.writtenNames)
    }

    @Test
    fun `reads indentation, semicolon and spacing around dots`() {
        assertEquals(
            ImportTarget.Type.of("java.util.List", null),
            ImportLineParser.parse("  import  java . util.List ;  ")?.target,
        )
    }

    @Test
    fun `reads an alias`() {
        assertEquals(
            ImportTarget.Type.of("java.util.ArrayList", "AL"),
            ImportLineParser.parse("import java.util.ArrayList as AL")?.target,
        )
    }

    @Test
    fun `an alias equal to the simple name is no alias`() {
        assertEquals(
            ImportTarget.Type.of("a.B", null),
            ImportLineParser.parse("import a.B as B")?.target,
        )
    }

    @Test
    fun `reads star and static star imports`() {
        assertEquals(ImportTarget.Star("java.util"), ImportLineParser.parse("import java.util.*")?.target)
        assertEquals(
            ImportTarget.StaticStar("java.lang.Math"),
            ImportLineParser.parse("import static java.lang.Math.*")?.target,
        )
    }

    @Test
    fun `reads static member imports with and without alias`() {
        assertEquals(
            ImportTarget.StaticMember.of("java.lang.Math", "PI", null),
            ImportLineParser.parse("import static java.lang.Math.PI")?.target,
        )
        assertEquals(
            ImportTarget.StaticMember.of("java.lang.Math", "PI", "P"),
            ImportLineParser.parse("import static java.lang.Math.PI as P")?.target,
        )
    }

    @Test
    fun `a package segment named static is not the static keyword`() {
        assertEquals(
            ImportTarget.Type.of("static.foo.Bar", null),
            ImportLineParser.parse("import static.foo.Bar")?.target,
        )
    }

    @Test
    fun `reads unicode identifiers`() {
        assertEquals(
            ImportTarget.Type.of("café.Ünï", null),
            ImportLineParser.parse("import café.Ünï")?.target,
        )
    }

    @Test
    fun `keeps a trailing comment for the caller to see`() {
        assertEquals("// why", ImportLineParser.parse("import a.B // why")?.trailing)
        assertEquals("/* why */", ImportLineParser.parse("import a.B; /* why */")?.trailing)
    }

    @Test
    fun `nested class separators are normalized but the written names are kept`() {
        assertEquals(ImportTarget.Type.of("a.Outer.Inner", null), ImportTarget.Type.of("a.Outer\$Inner", null))
        assertEquals(
            setOf("Outer\$Inner", "Outer", "Inner"),
            ImportLineParser.parse("import a.Outer\$Inner")?.writtenNames,
        )
    }

    @Test
    fun `a comment must be one complete comment to count as the end of the statement`() {
        assertNull(ImportLineParser.parse("import a.B /* c */ foo()"))
        assertNull(ImportLineParser.parse("import a.B /* unterminated"))
        assertNull(ImportLineParser.parse("import a.B /*/"))
        assertEquals("/* c */", ImportLineParser.parse("import a.B /* c */")?.trailing)
    }

    @Test
    fun `rejects what is not one complete import statement`() {
        listOf(
            "import",
            "import a.",
            "import a.B as",
            "import a.B import c.D",
            "import a.B foo()",
            "import static B",
            "import a.* as X",
            "importa.B",
            "// import a.B",
            "def x = 1",
            "",
        ).forEach { line -> assertNull(ImportLineParser.parse(line), "should reject: $line") }
    }

    @Test
    fun `rejects an absurdly long line`() {
        assertNull(ImportLineParser.parse("import a." + "b.".repeat(2_000) + "C"))
    }
}
