package com.github.albertocavalcante.groovylsp.providers.codeaction.handlers

import com.github.albertocavalcante.groovylsp.providers.codeaction.FixContext
import com.github.albertocavalcante.groovylsp.providers.codeaction.FixHandlerRegistry
import com.github.albertocavalcante.groovylsp.providers.codeaction.TestDiagnosticFactory
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

/**
 * Tests for import-related fix handlers.
 * Covers UnusedImport, DuplicateImport, UnnecessaryGroovyImport, and ImportFromSamePackage.
 */
class ImportFixHandlersTest {

    // ========================================================================
    // Property Tests for Import Line Removal
    // ========================================================================

    /**
     * Property test: Import Line Removal
     * **Feature: codenarc-lint-fixes, Property 6: Import Line Removal**
     * **Validates: Requirements 3.1, 3.2, 3.3, 3.4**
     *
     * For any import statement on a single line, applying an import removal fix
     * should result in the complete removal of that line including its newline character.
     */
    @Property(tries = 100)
    fun `property - import line removal removes entire line including newline`(
        @ForAll("contentWithImportLine") contentInfo: ContentWithImportInfo,
    ): Boolean {
        val content = contentInfo.content
        val lines = content.lines()
        val importLineNumber = contentInfo.importLineNumber
        val ruleName = contentInfo.ruleName

        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = ruleName,
            message = "Import issue detected",
            line = importLineNumber,
            startChar = 0,
            endChar = lines[importLineNumber].length,
        )

        val handler = FixHandlerRegistry.getHandler(ruleName) ?: return false

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler(context) ?: return false

        // The fix should remove the entire line including the newline
        // Range should start at beginning of import line and end at beginning of next line
        val expectedStartLine = importLineNumber
        val expectedEndLine = importLineNumber + 1

        return textEdit.newText == "" &&
            textEdit.range.start.line == expectedStartLine &&
            textEdit.range.start.character == 0 &&
            textEdit.range.end.line == expectedEndLine &&
            textEdit.range.end.character == 0
    }

    /**
     * Property test: Import Line Removal for Last Line
     * **Feature: codenarc-lint-fixes, Property 6: Import Line Removal**
     * **Validates: Requirements 3.1, 3.2, 3.3, 3.4**
     *
     * For an import statement on the last line of a file, the fix should remove
     * the entire line content (from start of line to end of line).
     */
    @Property(tries = 100)
    fun `property - import line removal handles last line correctly`(
        @ForAll("contentWithImportOnLastLine") contentInfo: ContentWithImportInfo,
    ): Boolean {
        val content = contentInfo.content
        val lines = content.lines()
        val importLineNumber = contentInfo.importLineNumber
        val ruleName = contentInfo.ruleName

        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = ruleName,
            message = "Import issue detected",
            line = importLineNumber,
            startChar = 0,
            endChar = lines[importLineNumber].length,
        )

        val handler = FixHandlerRegistry.getHandler(ruleName) ?: return false

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler(context) ?: return false

        // For last line, we need to also remove the preceding newline
        // Range should start at end of previous line and end at end of import line
        val expectedStartLine = importLineNumber - 1
        val expectedStartChar = lines[importLineNumber - 1].length
        val expectedEndLine = importLineNumber
        val expectedEndChar = lines[importLineNumber].length

        return textEdit.newText == "" &&
            textEdit.range.start.line == expectedStartLine &&
            textEdit.range.start.character == expectedStartChar &&
            textEdit.range.end.line == expectedEndLine &&
            textEdit.range.end.character == expectedEndChar
    }

    /**
     * Data class to hold content with import line and metadata.
     */
    data class ContentWithImportInfo(val content: String, val importLineNumber: Int, val ruleName: String)

    @Provide
    fun contentWithImportLine(): Arbitrary<ContentWithImportInfo> {
        val packageLine = Arbitraries.of(
            "package com.example",
            "package org.test",
            "package io.github.sample",
        )
        val importStatements = Arbitraries.of(
            "import java.util.List",
            "import java.util.Map",
            "import groovy.transform.CompileStatic",
            "import org.example.SomeClass",
            "import static java.lang.Math.PI",
        )
        val classDeclaration = Arbitraries.of(
            "class Foo {}",
            "class Bar { void test() {} }",
            "interface Baz {}",
        )
        val ruleNames = Arbitraries.of(
            "UnusedImport",
            "DuplicateImport",
            "UnnecessaryGroovyImport",
            "ImportFromSamePackage",
        )

        return Combinators.combine(packageLine, importStatements, classDeclaration, ruleNames)
            .`as` { pkg, imp, cls, rule ->
                val content = "$pkg\n$imp\n$cls"
                ContentWithImportInfo(
                    content = content,
                    importLineNumber = 1, // Import is always on line 1 in this generator
                    ruleName = rule,
                )
            }
    }

    @Provide
    fun contentWithImportOnLastLine(): Arbitrary<ContentWithImportInfo> {
        val packageLine = Arbitraries.of(
            "package com.example",
            "package org.test",
        )
        val importStatements = Arbitraries.of(
            "import java.util.List",
            "import java.util.Map",
            "import groovy.transform.CompileStatic",
        )
        val ruleNames = Arbitraries.of(
            "UnusedImport",
            "DuplicateImport",
            "UnnecessaryGroovyImport",
            "ImportFromSamePackage",
        )

        return Combinators.combine(packageLine, importStatements, ruleNames)
            .`as` { pkg, imp, rule ->
                val content = "$pkg\n$imp"
                ContentWithImportInfo(
                    content = content,
                    importLineNumber = 1, // Import is on last line (line 1)
                    ruleName = rule,
                )
            }
    }

    // ========================================================================
    // Unit Tests for UnusedImport
    // ========================================================================

    @Test
    fun `UnusedImport handler removes entire import line including newline`() {
        val content = "package com.example\nimport java.util.List\nclass Foo {}"
        val lines = content.lines()
        // Lines: ["package com.example", "import java.util.List", "class Foo {}"]
        // Import is at line 1
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnusedImport",
            message = "The import 'java.util.List' is never used",
            line = 1,
            startChar = 0,
            endChar = lines[1].length,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("UnusedImport"),
            "UnusedImport handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        // Should remove entire line including newline
        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(
            Range(Position(1, 0), Position(2, 0)),
            textEdit.range,
            "Range should cover entire import line including newline",
        )
    }

    @Test
    fun `UnusedImport handler removes last line import correctly`() {
        val content = "package com.example\nimport java.util.List"
        val lines = content.lines()
        // Lines: ["package com.example", "import java.util.List"]
        // Import is at line 1 (last line)
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnusedImport",
            message = "The import 'java.util.List' is never used",
            line = 1,
            startChar = 0,
            endChar = lines[1].length,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("UnusedImport"),
            "UnusedImport handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        // For last line, should remove from end of previous line to end of import line
        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(
            Range(Position(0, lines[0].length), Position(1, lines[1].length)),
            textEdit.range,
            "Range should cover newline before import and entire import line",
        )
    }

    @Test
    fun `UnusedImport handler returns null for out of bounds line`() {
        val content = "package com.example"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnusedImport",
            message = "The import is never used",
            line = 5, // Out of bounds
            startChar = 0,
            endChar = 10,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("UnusedImport"),
            "UnusedImport handler should be registered",
        )
        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler(context)

        assertNull(textEdit, "Handler should return null for out of bounds line")
    }

    // ========================================================================
    // Unit Tests for DuplicateImport
    // ========================================================================

    @Test
    fun `DuplicateImport handler removes entire import line`() {
        val content = "package com.example\nimport java.util.List\nimport java.util.List\nclass Foo {}"
        val lines = content.lines()
        // Lines: ["package com.example", "import java.util.List", "import java.util.List", "class Foo {}"]
        // Duplicate import is at line 2
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "DuplicateImport",
            message = "Duplicate import for 'java.util.List'",
            line = 2,
            startChar = 0,
            endChar = lines[2].length,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("DuplicateImport"),
            "DuplicateImport handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(
            Range(Position(2, 0), Position(3, 0)),
            textEdit.range,
            "Range should cover entire duplicate import line including newline",
        )
    }

    // ========================================================================
    // Unit Tests for UnnecessaryGroovyImport
    // ========================================================================

    @Test
    fun `UnnecessaryGroovyImport handler removes entire import line`() {
        val content = "package com.example\nimport java.util.ArrayList\nclass Foo {}"
        val lines = content.lines()
        // Lines: ["package com.example", "import java.util.ArrayList", "class Foo {}"]
        // Unnecessary import is at line 1
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryGroovyImport",
            message = "The import 'java.util.ArrayList' is unnecessary",
            line = 1,
            startChar = 0,
            endChar = lines[1].length,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("UnnecessaryGroovyImport"),
            "UnnecessaryGroovyImport handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(
            Range(Position(1, 0), Position(2, 0)),
            textEdit.range,
            "Range should cover entire unnecessary import line including newline",
        )
    }

    // ========================================================================
    // Unit Tests for ImportFromSamePackage
    // ========================================================================

    @Test
    fun `ImportFromSamePackage handler removes entire import line`() {
        val content = "package com.example\nimport com.example.SomeClass\nclass Foo {}"
        val lines = content.lines()
        // Lines: ["package com.example", "import com.example.SomeClass", "class Foo {}"]
        // Same-package import is at line 1
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "ImportFromSamePackage",
            message = "The import 'com.example.SomeClass' is from the same package",
            line = 1,
            startChar = 0,
            endChar = lines[1].length,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("ImportFromSamePackage"),
            "ImportFromSamePackage handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(
            Range(Position(1, 0), Position(2, 0)),
            textEdit.range,
            "Range should cover entire same-package import line including newline",
        )
    }

    // ========================================================================
    // Edge Case Tests
    // ========================================================================

    @Test
    fun `import handler handles single line file`() {
        val content = "import java.util.List"
        val lines = content.lines()
        // Single line file with just an import
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnusedImport",
            message = "The import is never used",
            line = 0,
            startChar = 0,
            endChar = content.length,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("UnusedImport"),
            "UnusedImport handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        // For single line file, should remove entire content
        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(
            Range(Position(0, 0), Position(0, content.length)),
            textEdit.range,
            "Range should cover entire single line",
        )
    }

    @Test
    fun `import handler handles import at first line with content after`() {
        val content = "import java.util.List\nclass Foo {}"
        val lines = content.lines()
        // Import at line 0, class at line 1
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnusedImport",
            message = "The import is never used",
            line = 0,
            startChar = 0,
            endChar = lines[0].length,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("UnusedImport"),
            "UnusedImport handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        // Should remove first line including newline
        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(
            Range(Position(0, 0), Position(1, 0)),
            textEdit.range,
            "Range should cover first line including newline",
        )
    }

    @Test
    fun `import handler handles multiple imports removing middle one`() {
        val content = "import java.util.List\nimport java.util.Map\nimport java.util.Set"
        val lines = content.lines()
        // Remove middle import at line 1
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnusedImport",
            message = "The import is never used",
            line = 1,
            startChar = 0,
            endChar = lines[1].length,
        )

        val handler = assertNotNull(
            FixHandlerRegistry.getHandler("UnusedImport"),
            "UnusedImport handler should be registered",
        )

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = assertNotNull(handler(context), "Handler should return a TextEdit")

        assertEquals("", textEdit.newText, "newText should be empty for deletion")
        assertEquals(
            Range(Position(1, 0), Position(2, 0)),
            textEdit.range,
            "Range should cover middle import line including newline",
        )
    }
}
