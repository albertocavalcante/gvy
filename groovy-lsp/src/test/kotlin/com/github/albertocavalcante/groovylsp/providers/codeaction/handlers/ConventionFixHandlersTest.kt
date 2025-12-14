package com.github.albertocavalcante.groovylsp.providers.codeaction.handlers

import com.github.albertocavalcante.groovylsp.providers.codeaction.FixContext
import com.github.albertocavalcante.groovylsp.providers.codeaction.FixHandlerRegistry
import com.github.albertocavalcante.groovylsp.providers.codeaction.TestDiagnosticFactory
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tests for convention-related fix handlers.
 * Covers UnnecessaryPublicModifier, UnnecessaryDef, UnnecessaryGetter, UnnecessarySetter, UnnecessaryDotClass.
 */
class ConventionFixHandlersTest {

    companion object {
        /** Length of "public " keyword including trailing space */
        private const val PUBLIC_KEYWORD_LENGTH = 7
    }

    // ========================================================================
    // Property Tests for UnnecessaryPublicModifier
    // ========================================================================

    /**
     * Property test: Public Modifier Removal
     * **Feature: codenarc-lint-fixes, Property 7: Public Modifier Removal**
     * **Validates: Requirements 4.1**
     *
     * For any declaration containing the `public` modifier, applying the UnnecessaryPublicModifier fix
     * should result in a declaration without the `public ` substring.
     */
    @Property(tries = 100)
    fun `property - public modifier removal produces correct result`(
        @ForAll("declarationsWithPublicModifier") declaration: String,
    ): Boolean {
        val content = declaration
        val lines = content.lines()

        // Find the position of "public " in the declaration
        val publicIndex = declaration.indexOf("public ")
        if (publicIndex == -1) return true // Skip if no public modifier found

        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryPublicModifier",
            message = "The public keyword is unnecessary for method declarations",
            line = 0,
            startChar = publicIndex,
            endChar = publicIndex + PUBLIC_KEYWORD_LENGTH,
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryPublicModifier")
        assertNotNull(handler, "UnnecessaryPublicModifier handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        return if (textEdit != null) {
            // The fix should remove "public " using the diagnostic range
            textEdit.newText == "" &&
                textEdit.range.start.character == publicIndex &&
                textEdit.range.end.character == publicIndex + PUBLIC_KEYWORD_LENGTH
        } else {
            // Handler should produce a fix for valid declarations with public modifier
            false
        }
    }

    @Provide
    fun declarationsWithPublicModifier(): Arbitrary<String> {
        // Generate various Groovy declarations with public modifier
        return Arbitraries.of(
            "public class Foo {}",
            "public void method() {}",
            "public String getName() { return name }",
            "public int getValue() { return value }",
            "public static void main(String[] args) {}",
            "public final class Bar {}",
            "public abstract class Base {}",
            "public interface Service {}",
            "public enum Status { ACTIVE, INACTIVE }",
        )
    }

    // ========================================================================
    // Unit Tests for UnnecessaryPublicModifier
    // ========================================================================

    @Test
    fun `public modifier handler removes public from class declaration`() {
        val content = "public class Foo {}"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryPublicModifier",
            message = "The public keyword is unnecessary for class declarations",
            line = 0,
            startChar = 0,
            endChar = PUBLIC_KEYWORD_LENGTH,
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryPublicModifier")
        assertNotNull(handler, "UnnecessaryPublicModifier handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNotNull(textEdit, "Handler should produce a TextEdit")
        assertEquals("", textEdit!!.newText, "Should remove public keyword")
        assertEquals(0, textEdit.range.start.line)
        assertEquals(0, textEdit.range.start.character)
        assertEquals(0, textEdit.range.end.line)
        assertEquals(PUBLIC_KEYWORD_LENGTH, textEdit.range.end.character)
    }

    @Test
    fun `public modifier handler removes public from method declaration`() {
        val content = "public void method() {}"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryPublicModifier",
            message = "The public keyword is unnecessary for method declarations",
            line = 0,
            startChar = 0,
            endChar = PUBLIC_KEYWORD_LENGTH,
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryPublicModifier")
        assertNotNull(handler, "UnnecessaryPublicModifier handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNotNull(textEdit, "Handler should produce a TextEdit")
        assertEquals("", textEdit!!.newText, "Should remove public keyword")
        assertEquals(0, textEdit.range.start.line)
        assertEquals(0, textEdit.range.start.character)
        assertEquals(0, textEdit.range.end.line)
        assertEquals(PUBLIC_KEYWORD_LENGTH, textEdit.range.end.character)
    }

    @Test
    fun `public modifier handler handles indented declarations`() {
        val content = "    public void method() {}"
        val lines = content.lines()
        val publicIndex = content.indexOf("public ")
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryPublicModifier",
            message = "The public keyword is unnecessary for method declarations",
            line = 0,
            startChar = publicIndex,
            endChar = publicIndex + PUBLIC_KEYWORD_LENGTH,
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryPublicModifier")
        assertNotNull(handler, "UnnecessaryPublicModifier handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNotNull(textEdit, "Handler should produce a TextEdit")
        assertEquals("", textEdit!!.newText, "Should remove public keyword")
        assertEquals(0, textEdit.range.start.line)
        assertEquals(publicIndex, textEdit.range.start.character)
        assertEquals(0, textEdit.range.end.line)
        assertEquals(publicIndex + PUBLIC_KEYWORD_LENGTH, textEdit.range.end.character)
    }

    @Test
    fun `public modifier handler returns null for out of bounds line`() {
        val content = "public class Foo {}"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryPublicModifier",
            message = "The public keyword is unnecessary",
            line = 5, // Out of bounds
            startChar = 0,
            endChar = PUBLIC_KEYWORD_LENGTH,
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryPublicModifier")
        assertNotNull(handler, "UnnecessaryPublicModifier handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNull(textEdit, "Handler should return null for out of bounds line")
    }

    @Test
    fun `public modifier handler returns null for invalid range`() {
        val content = "class Foo {}" // No public modifier, range exceeds line length
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryPublicModifier",
            message = "The public keyword is unnecessary",
            line = 0,
            startChar = 0,
            endChar = PUBLIC_KEYWORD_LENGTH + content.length, // Range exceeds line length
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryPublicModifier")
        assertNotNull(handler, "UnnecessaryPublicModifier handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNull(textEdit, "Handler should return null for invalid range")
    }
}
