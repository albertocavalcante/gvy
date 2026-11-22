// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.imports

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportOrganizerTest {

    /** The detector's verdict for the imports on [lines], read from the text so tests stay short. */
    private fun unusedOn(text: String, lines: List<Int>): List<UnusedImport> {
        val textLines = text.split("\r\n", "\n", "\r")
        return lines.map { line -> UnusedImport(checkNotNull(ImportLineParser.parse(textLines[line])).target, line) }
    }

    /** A binding as the organizer sees it: the target and how its name is written. */
    private fun bindingOf(line: String): Pair<ImportTarget, Set<String>>? =
        ImportLineParser.parse(line)?.let { it.target to it.writtenNames }

    private fun edits(text: String, vararg unusedLines: Int): List<TextReplacement> {
        val edits = ImportOrganizer.organize(text, unusedOn(text, unusedLines.toList()))
        assertTrue(edits.zipWithNext().all { (a, b) -> a.end <= b.start }, "edits overlap or are unordered: $edits")
        return edits
    }

    /** Organizes [text]; asserts that organizing the result again changes nothing. */
    private fun organize(text: String, vararg unusedLines: Int): String {
        var result = text
        for (edit in edits(text, *unusedLines).sortedByDescending { it.start }) {
            result = result.substring(0, edit.start) + edit.newText + result.substring(edit.end)
        }
        // A second pass, with the detector flagging the same imports wherever they now sit, has nothing to do.
        val textLines = text.split("\r\n", "\n", "\r")
        val flagged = unusedLines.map { bindingOf(textLines[it]) }.toSet()
        val again = result.split("\r\n", "\n", "\r").mapIndexedNotNull { line, content ->
            val parsed = ImportLineParser.parse(content)
            parsed?.takeIf { bindingOf(content) in flagged }?.let { UnusedImport(it.target, line) }
        }
        assertEquals(emptyList(), ImportOrganizer.organize(result, again), "not idempotent: $result")
        return result
    }

    @Test
    fun `sorts and groups with the default layout`() {
        val text = """
            package p

            import static java.lang.Math.max
            import java.util.List
            import com.foo.Bar
            import javax.swing.JFrame
            import java.io.File
            import static a.Z.zed

            Bar b
        """.trimIndent() + "\n"

        val expected = """
            package p

            import com.foo.Bar

            import javax.swing.JFrame
            import java.io.File
            import java.util.List

            import static a.Z.zed
            import static java.lang.Math.max

            Bar b
        """.trimIndent() + "\n"
        assertEquals(expected, organize(text))
    }

    @Test
    fun `an organized file gets no edit`() {
        val text = "package p\n\nimport a.A\nimport b.B\n\nimport static c.C.c\n\nclass X {}\n"

        assertEquals(emptyList(), edits(text))
    }

    @Test
    fun `sorts by qualified name, case sensitive`() {
        assertEquals("import a.B\nimport a.b\n", organize("import a.b\nimport a.B\n"))
    }

    @Test
    fun `an alias sorts by its target and different aliases are different bindings`() {
        assertEquals(
            "import a.A\nimport a.B as Z\nimport a.C\n",
            organize("import a.B as Z\nimport a.C\nimport a.A\n"),
        )
        val both = "import java.util.List\nimport java.util.List as JList\n"
        assertEquals(emptyList(), edits(both))
    }

    @Test
    fun `a star import sorts with its package before the types in it`() {
        assertEquals(
            "import java.util.*\nimport java.util.List\n",
            organize("import java.util.List\nimport java.util.*\n"),
        )
    }

    @Test
    fun `static imports sort by class then member`() {
        assertEquals(
            "import static a.A.z\nimport static a.B.a\nimport static a.B.b\n",
            organize("import static a.B.b\nimport static a.B.a\nimport static a.A.z\n"),
        )
    }

    @Test
    fun `drops an exact duplicate and keeps different bindings`() {
        assertEquals("import a.B\n\nB b\n", organize("import a.B\nimport a.B\nB b\n"))
    }

    @Test
    fun `keeps a duplicate that carries a comment so no comment is lost`() {
        val text = "import a.A\nimport a.A // again\n"

        assertEquals(emptyList(), edits(text))
    }

    @Test
    fun `removes provably unused imports and keeps star imports`() {
        val text = "import z.Gone\nimport java.util.*\nimport b.Kept\nimport a.Other\n"

        assertEquals("import a.Other\nimport b.Kept\n\nimport java.util.*\n", organize(text, 0))
    }

    @Test
    fun `an unused import with a comment stays`() {
        val text = "import b.Gone // needed\nimport a.A\n"

        assertEquals("import a.A\nimport b.Gone // needed\n", organize(text, 0))
    }

    @Test
    fun `a trailing comment moves with its import`() {
        assertEquals("import a.A\nimport b.B // bee\n", organize("import b.B // bee\nimport a.A\n"))
    }

    @Test
    fun `comments directly above an import move with it`() {
        assertEquals(
            "import a.A\n// the bee\nimport b.B\n",
            organize("// the bee\nimport b.B\nimport a.A\n"),
        )
        assertEquals(
            "import a.A\n/**\n * why\n */\nimport b.B\n",
            organize("/**\n * why\n */\nimport b.B\nimport a.A\n"),
        )
    }

    @Test
    fun `a comment that belongs to no import disables reordering but not safe removal`() {
        val text = "import b.B\n// stray\n\nimport a.A\nimport z.Gone\n"

        assertEquals("import b.B\n// stray\n\nimport a.A\n", organize(text, 4))
    }

    @Test
    fun `a comment after the last import does not disable reordering`() {
        val text = "import b.B\nimport a.A\n\n// about X\n\nclass X {}\n"

        assertEquals("import a.A\nimport b.B\n\n// about X\n\nclass X {}\n", organize(text))
    }

    @Test
    fun `a header above a blank line stays put`() {
        val text = "/* license */\n\nimport b.B\nimport a.A\n"

        assertEquals("/* license */\n\nimport a.A\nimport b.B\n", organize(text))
    }

    @Test
    fun `keeps each line ending style`() {
        val input = listOf("package p", "", "import b.B", "import a.A", "", "B x", "")
        val output = listOf("package p", "", "import a.A", "import b.B", "", "B x", "")
        for (eol in listOf("\n", "\r\n", "\r")) {
            assertEquals(output.joinToString(eol), organize(input.joinToString(eol)), eol.replace("\r", "CR"))
        }
    }

    @Test
    fun `removal keeps each line ending style`() {
        val input = listOf("import b.Gone", "import a.A", "", "A a", "")
        val output = listOf("import a.A", "", "A a", "")
        for (eol in listOf("\n", "\r\n", "\r")) {
            assertEquals(output.joinToString(eol), organize(input.joinToString(eol), 0), eol.replace("\r", "CR"))
        }
    }

    @Test
    fun `normalizes the blank lines around the block`() {
        assertEquals(
            "package p\n\nimport a.A\nimport b.B\n\nclass X {}\n",
            organize("package p\nimport b.B\nimport a.A\nclass X {}\n"),
        )
        assertEquals(
            "package p\n\nimport a.A\n\nclass X {}\n",
            organize("package p\n\n\n\nimport a.A\n\n\n\nclass X {}\n"),
        )
    }

    @Test
    fun `handles a file with only imports, with and without a final line break`() {
        assertEquals("import a.A\nimport b.B\n", organize("import b.B\nimport a.A\n"))
        assertEquals("import a.A\nimport b.B", organize("import b.B\nimport a.A"))
    }

    @Test
    fun `keeps a shebang on the first line`() {
        assertEquals(
            "#!/usr/bin/env groovy\n\nimport a.A\nimport b.B\n",
            organize("#!/usr/bin/env groovy\nimport b.B\nimport a.A\n"),
        )
    }

    @Test
    fun `removing every import leaves one blank line between package and code`() {
        assertEquals("package a\n\nclass X {}\n", organize("package a\n\nimport b.C\n\nclass X {}\n", 2))
        assertEquals("package a\n", organize("package a\n\nimport b.C\n", 2))
    }

    @Test
    fun `a file with no imports gets no edit`() {
        assertEquals(emptyList(), edits("package p\n\nclass X {}\n"))
        assertEquals(emptyList(), edits(""))
    }

    @Test
    fun `leaves Jenkins library annotations and the imports after them alone`() {
        assertEquals(emptyList(), edits("@Library('lib') _\nimport b.B\nimport a.A\n"))
    }

    @Test
    fun `stops at an annotated import and leaves it alone`() {
        val text = "import b.B\nimport a.A\n@Grab('x:y:1')\nimport q.Z\n"

        assertEquals("import a.A\nimport b.B\n\n@Grab('x:y:1')\nimport q.Z\n", organize(text))
    }

    @Test
    fun `imports after other statements stay put and are removed only when unused`() {
        val text = "import b.B\nimport a.A\n\nprintln 1\nimport c.Late\nimport a.Gone\n"

        assertEquals("import a.A\nimport b.B\n\nprintln 1\nimport c.Late\n", organize(text, 5))
    }

    @Test
    fun `a statement it cannot read stops the block and is left as it is`() {
        val text = "import b.B\n\nimport a.\nimport a.A\n"

        assertEquals(emptyList(), edits(text))
    }

    @Test
    fun `a block comment above an unused import keeps the import and the comment`() {
        val text = "/* keep: loaded\n   reflectively */\nimport a.Gone\nimport b.B\n"

        assertEquals(text, organize(text, 2))
    }

    @Test
    fun `Map Entry and Map dollar Entry are different bindings, not duplicates`() {
        val text = "import java.util.Map\$Entry\nimport java.util.Map.Entry\n"

        assertEquals(emptyList(), edits(text))
    }

    @Test
    fun `unused duplicates are removed in one pass`() {
        assertEquals("class X {}\n", organize("import a.A\nimport a.A\n\nclass X {}\n", 0, 1))
    }

    @Test
    fun `a duplicate group is judged as one, whichever copy the detector flagged`() {
        assertEquals("class X {}\n", organize("import a.A\nimport a.A\n\nclass X {}\n", 1))
        assertEquals("class X {}\n", organize("import a.A\nimport a.A\n\nclass X {}\n", 0))
    }

    @Test
    fun `imports that only mention each other are removed in one pass`() {
        val text = "import p.Helper\nimport p.Helper.Inner\n\nclass X {}\n"

        assertEquals("class X {}\n", organize(text, 0, 1))
    }

    @Test
    fun `later imports that only the removed block imports mention go in the same pass`() {
        val text = "import p.Helper\n\nprintln 1\nimport p.Helper.Inner\n"

        assertEquals("println 1\n", organize(text, 0, 3))
    }

    @Test
    fun `a file with mixed line endings gets one consistent block and stays stable`() {
        assertEquals(
            "package p\r\rimport a.A\rimport b.B\r\rB b\n",
            organize("package p\rimport b.B\rimport a.A\n\nB b\n"),
        )
        assertEquals("import a.A\rimport b.B\n\n", organize("import b.B\rimport a.A\n\n"))
    }
}
