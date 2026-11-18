// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.imports

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnusedImportRemovalTest {

    /** The detector's verdict for the imports on [lines], read from the text so tests stay short. */
    private fun unusedOn(text: String, vararg lines: Int): List<UnusedImport> {
        val textLines = text.split("\r\n", "\n", "\r")
        return lines.map { line -> UnusedImport(checkNotNull(ImportLineParser.parse(textLines[line])).target, line) }
    }

    private fun remove(text: String, vararg lines: Int): String {
        val edits = UnusedImportRemoval.plan(text, unusedOn(text, *lines))
        assertTrue(edits.zipWithNext().all { (a, b) -> a.end <= b.start }, "edits overlap or are unordered: $edits")
        return edits.sortedByDescending { it.start }.fold(text) { acc, edit ->
            acc.substring(0, edit.start) + edit.newText + acc.substring(edit.end)
        }
    }

    @Test
    fun `removes an unused import line and nothing else`() {
        val text = "import java.util.List\nimport java.util.Map\n\nMap m = null\n"

        assertEquals("import java.util.Map\n\nMap m = null\n", remove(text, 0))
    }

    @Test
    fun `removal keeps each line ending style`() {
        val body = listOf("package a", "", "import x.Gone", "import x.Kept", "", "Kept k")
        for (eol in listOf("\n", "\r\n", "\r")) {
            val text = body.joinToString(eol)

            val expected = listOf("package a", "", "import x.Kept", "", "Kept k").joinToString(eol)
            assertEquals(expected, remove(text, 2), "eol=${eol.replace("\r", "CR").replace("\n", "LF")}")
        }
    }

    @Test
    fun `removes the semicolon with the statement`() {
        assertEquals("import x.Kept;\nKept k", remove("import x.Gone;\nimport x.Kept;\nKept k", 0))
    }

    @Test
    fun `removes an unused alias and an unused static member`() {
        val text = "import java.util.ArrayList as AL\nimport static java.lang.Math.PI\nint x = 1\n"

        assertEquals("int x = 1\n", remove(text, 0, 1))
    }

    @Test
    fun `an alias equal to the simple name is removable`() {
        assertEquals("int x = 1\n", remove("import a.B as B\nint x = 1\n", 0))
    }

    @Test
    fun `never removes star imports even when told they are unused`() {
        val text = "import java.util.*\nimport static java.lang.Math.*\nint x = 1\n"

        assertEquals(emptyList(), UnusedImportRemoval.plan(text, unusedOn(text, 0, 1)))
    }

    @Test
    fun `keeps an import with a comment on its line`() {
        val text = "import a.B // needed by the build\nimport a.C /* c */\nint x = 1\n"

        assertEquals(emptyList(), UnusedImportRemoval.plan(text, unusedOn(text, 0, 1)))
    }

    @Test
    fun `keeps an import with a comment directly above it, and the comment`() {
        val line = "// reason\nimport a.B\n"
        val block = "/*\n * reason\n */\nimport a.B\n"

        assertEquals(emptyList(), UnusedImportRemoval.plan(line, unusedOn(line, 1)))
        assertEquals(emptyList(), UnusedImportRemoval.plan(block, unusedOn(block, 3)))
    }

    @Test
    fun `a comment separated by a blank line does not attach and is never removed`() {
        val text = "// header\n\nimport a.B\nint x = 1\n"

        assertEquals("// header\n\nint x = 1\n", remove(text, 2))
    }

    @Test
    fun `keeps an import whose name appears elsewhere, such as Groovydoc, strings or comments`() {
        val doc = "import a.B\n/** see {@link B} */\nclass X {}\n"
        val string = "import a.B\nString s = 'B'\n"
        val dynamic = "import a.B\ndef c = Class.forName('a.B')\n"
        val member = "import static a.M.run\nobj.run()\n"

        listOf(doc, string, dynamic, member).forEach { text ->
            assertEquals(emptyList(), UnusedImportRemoval.plan(text, unusedOn(text, 0)), text)
        }
    }

    @Test
    fun `an alias keeps the import when the target simple name is mentioned`() {
        val text = "import a.B as C\nB.foo()\n"

        assertEquals(emptyList(), UnusedImportRemoval.plan(text, unusedOn(text, 0)))
    }

    @Test
    fun `a longer identifier containing the name is not a mention`() {
        assertEquals("def Listing = 1\n", remove("import java.util.List\ndef Listing = 1\n", 0))
    }

    @Test
    fun `the name inside its own import line is not a mention`() {
        assertEquals("int x = 1\n", remove("import a.B.B\nint x = 1\n", 0))
    }

    @Test
    fun `drops the blank line that would be left at the top or doubled`() {
        assertEquals("class X {}\n", remove("import a.B\n\nclass X {}\n", 0))
        assertEquals("package p\n\nclass X {}\n", remove("package p\n\nimport a.B\n\nclass X {}\n", 2))
    }

    @Test
    fun `keeps the blank line when the neighbour is an import or code`() {
        assertEquals("package p\nimport a.C\n", remove("package p\nimport a.B\nimport a.C\n", 1))
        assertEquals("package p\n\nclass X {}\n", remove("package p\n\nimport a.B\nclass X {}\n", 2))
    }

    @Test
    fun `removes the last line of a file without a trailing line break`() {
        assertEquals("package p\n", remove("package p\nimport a.B", 1))
    }

    @Test
    fun `adjacent and separated removals do not overlap`() {
        val text = "package p\n\nimport a.A\n\nimport a.B\n\nimport a.C\nC c\n"

        assertEquals("package p\n\nimport a.C\nC c\n", remove(text, 2, 4))
    }

    @Test
    fun `refuses a statement split over lines or a stale line`() {
        fun planFor(text: String, qualifiedName: String, line: Int) =
            UnusedImportRemoval.plan(text, listOf(UnusedImport(ImportTarget.Type.of(qualifiedName, null), line)))

        val split = "import a.\n    B\nint x = 1\n"
        val text = "int x = 1\nimport a.B\n"

        assertEquals(emptyList(), planFor(split, "a.B", 0))
        assertEquals(emptyList(), planFor(text, "a.B", 0))
        assertEquals(emptyList(), planFor(text, "a.Z", 1))
        assertEquals(emptyList(), planFor(text, "a.B", 99))
    }

    @Test
    fun `removing again after applying is a no-op`() {
        val text = "import a.B\nimport a.C\nC c\n"
        val once = remove(text, 0)

        assertEquals("import a.C\nC c\n", once)
        assertEquals(emptyList(), UnusedImportRemoval.plan(once, emptyList()))
    }

    @Test
    fun `removes an import after other statements in a script`() {
        val text = "println 'hi'\nimport a.B\nprintln 'bye'\n"

        assertEquals("println 'hi'\nprintln 'bye'\n", remove(text, 1))
    }
}
