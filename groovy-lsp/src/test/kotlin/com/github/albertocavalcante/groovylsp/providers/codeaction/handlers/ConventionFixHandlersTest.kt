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
import org.junit.jupiter.api.Test

/**
 * Tests for convention-related fix handlers.
 * Covers UnnecessaryPublicModifier, UnnecessaryDef, UnnecessaryGetter, UnnecessarySetter, UnnecessaryDotClass.
 */
class ConventionFixHandlersTest {

    companion object {
        /** Length of "public " keyword including trailing space */
        private const val PUBLIC_KEYWORD_LENGTH = 7

        /** Length of "def " keyword including trailing space */
        private const val DEF_KEYWORD_LENGTH = 4
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

    // Note: Range validation tests (out of bounds, invalid range) are now handled
    // by RangeValidationTest.kt and RangeValidationPropertyTest.kt since validation
    // is centralized in LintFixAction.isValidRange()

    // ========================================================================
    // Property Tests for UnnecessaryDefInVariableDeclaration
    // ========================================================================

    /**
     * Property test: Def Keyword Removal
     * **Feature: codenarc-lint-fixes, Property 8: Def Keyword Removal**
     * **Validates: Requirements 4.2**
     *
     * For any variable declaration containing both a type and `def`, applying the
     * UnnecessaryDefInVariableDeclaration fix should result in a declaration without `def `.
     */
    @Property(tries = 100)
    fun `property - def keyword removal produces correct result`(
        @ForAll("declarationsWithUnnecessaryDef") declaration: String,
    ): Boolean {
        val content = declaration
        val lines = content.lines()

        // Find the position of "def " in the declaration
        val defIndex = declaration.indexOf("def ")
        if (defIndex == -1) return true // Skip if no def found

        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryDefInVariableDeclaration",
            message = "The def keyword is unnecessary when a type is specified",
            line = 0,
            startChar = defIndex,
            endChar = defIndex + DEF_KEYWORD_LENGTH,
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryDefInVariableDeclaration")
        assertNotNull(handler, "UnnecessaryDefInVariableDeclaration handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        return if (textEdit != null) {
            // The fix should remove "def " using the diagnostic range
            textEdit.newText == "" &&
                textEdit.range.start.character == defIndex &&
                textEdit.range.end.character == defIndex + DEF_KEYWORD_LENGTH
        } else {
            // Handler should produce a fix for valid declarations with def keyword
            false
        }
    }

    @Provide
    fun declarationsWithUnnecessaryDef(): Arbitrary<String> {
        // Generate various Groovy declarations with unnecessary def keyword
        return Arbitraries.of(
            "def String x",
            "def String name = 'test'",
            "def Integer count = 0",
            "def List items = []",
            "def Map config = [:]",
            "def Boolean flag = true",
            "def Object obj = null",
            "def ArrayList list = new ArrayList()",
            "def HashMap map = new HashMap()",
        )
    }

    // ========================================================================
    // Unit Tests for UnnecessaryDefInVariableDeclaration
    // ========================================================================

    @Test
    fun `def handler removes def from typed variable declaration`() {
        val content = "def String x"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryDefInVariableDeclaration",
            message = "The def keyword is unnecessary when a type is specified",
            line = 0,
            startChar = 0,
            endChar = DEF_KEYWORD_LENGTH,
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryDefInVariableDeclaration")
        assertNotNull(handler, "UnnecessaryDefInVariableDeclaration handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNotNull(textEdit, "Handler should produce a TextEdit")
        assertEquals("", textEdit!!.newText, "Should remove def keyword")
        assertEquals(0, textEdit.range.start.line)
        assertEquals(0, textEdit.range.start.character)
        assertEquals(0, textEdit.range.end.line)
        assertEquals(DEF_KEYWORD_LENGTH, textEdit.range.end.character)
    }

    @Test
    fun `def handler removes def from typed variable with initialization`() {
        val content = "def String name = 'test'"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryDefInVariableDeclaration",
            message = "The def keyword is unnecessary when a type is specified",
            line = 0,
            startChar = 0,
            endChar = DEF_KEYWORD_LENGTH,
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryDefInVariableDeclaration")
        assertNotNull(handler, "UnnecessaryDefInVariableDeclaration handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNotNull(textEdit, "Handler should produce a TextEdit")
        assertEquals("", textEdit!!.newText, "Should remove def keyword")
        assertEquals(0, textEdit.range.start.line)
        assertEquals(0, textEdit.range.start.character)
        assertEquals(0, textEdit.range.end.line)
        assertEquals(DEF_KEYWORD_LENGTH, textEdit.range.end.character)
    }

    @Test
    fun `def handler handles indented declarations`() {
        val content = "    def String x"
        val lines = content.lines()
        val defIndex = content.indexOf("def ")
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryDefInVariableDeclaration",
            message = "The def keyword is unnecessary when a type is specified",
            line = 0,
            startChar = defIndex,
            endChar = defIndex + DEF_KEYWORD_LENGTH,
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryDefInVariableDeclaration")
        assertNotNull(handler, "UnnecessaryDefInVariableDeclaration handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNotNull(textEdit, "Handler should produce a TextEdit")
        assertEquals("", textEdit!!.newText, "Should remove def keyword")
        assertEquals(0, textEdit.range.start.line)
        assertEquals(defIndex, textEdit.range.start.character)
        assertEquals(0, textEdit.range.end.line)
        assertEquals(defIndex + DEF_KEYWORD_LENGTH, textEdit.range.end.character)
    }

    // Note: Range validation tests (out of bounds, invalid range) are now handled
    // by RangeValidationTest.kt and RangeValidationPropertyTest.kt since validation
    // is centralized in LintFixAction.isValidRange()

    // ========================================================================
    // Property Tests for UnnecessaryGetter
    // ========================================================================

    /**
     * Property test: Getter to Property Access
     * **Feature: codenarc-lint-fixes, Property 9: Getter to Property Access**
     * **Validates: Requirements 4.3**
     *
     * For any method call of the form `obj.getXxx()` where Xxx starts with uppercase,
     * applying the UnnecessaryGetter fix should result in `obj.xxx` (property access
     * with lowercase first letter), unless the property name starts with an uppercase
     * letter after "get" (like getURL -> URL).
     */
    @Property(tries = 100)
    fun `property - getter to property access produces correct result`(
        @ForAll("getterCalls") getterCall: GetterCallInfo,
    ): Boolean {
        val content = getterCall.code
        val lines = content.lines()

        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryGetter",
            message = "Unnecessary getter call. Use property access instead.",
            line = 0,
            startChar = getterCall.getterStartIndex,
            endChar = getterCall.getterEndIndex,
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryGetter")
        assertNotNull(handler, "UnnecessaryGetter handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        return if (textEdit != null) {
            // The fix should replace the getter call with property access
            textEdit.newText == getterCall.expectedPropertyAccess &&
                textEdit.range.start.character == getterCall.getterStartIndex &&
                textEdit.range.end.character == getterCall.getterEndIndex
        } else {
            // Handler should produce a fix for valid getter calls
            false
        }
    }

    /**
     * Data class to hold getter call test information.
     */
    data class GetterCallInfo(
        val code: String,
        val getterStartIndex: Int,
        val getterEndIndex: Int,
        val expectedPropertyAccess: String,
    )

    @Provide
    fun getterCalls(): Arbitrary<GetterCallInfo> = Arbitraries.of(
        // Standard getter calls - lowercase first letter of property
        GetterCallInfo("obj.getName()", 4, 13, "name"),
        GetterCallInfo("obj.getValue()", 4, 14, "value"),
        GetterCallInfo("obj.getCount()", 4, 14, "count"),
        GetterCallInfo("obj.getItems()", 4, 14, "items"),
        GetterCallInfo("obj.getEnabled()", 4, 16, "enabled"),
        GetterCallInfo("obj.getDescription()", 4, 20, "description"),
        // Acronym getters - preserve uppercase (URL, ID, etc.)
        GetterCallInfo("obj.getURL()", 4, 12, "URL"),
        GetterCallInfo("obj.getID()", 4, 11, "ID"),
        GetterCallInfo("obj.getURI()", 4, 12, "URI"),
        GetterCallInfo("obj.getXML()", 4, 12, "XML"),
        // Mixed case after acronym
        GetterCallInfo("obj.getURLString()", 4, 18, "URLString"),
    )

    // ========================================================================
    // Unit Tests for UnnecessaryGetter
    // ========================================================================

    @Test
    fun `getter handler converts getName() to name`() {
        val content = "obj.getName()"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryGetter",
            message = "Unnecessary getter call. Use property access instead.",
            line = 0,
            startChar = 4, // Start of "getName()"
            endChar = 13, // End of "getName()"
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryGetter")
        assertNotNull(handler, "UnnecessaryGetter handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNotNull(textEdit, "Handler should produce a TextEdit")
        assertEquals("name", textEdit!!.newText, "Should convert to property access")
        assertEquals(0, textEdit.range.start.line)
        assertEquals(4, textEdit.range.start.character)
        assertEquals(0, textEdit.range.end.line)
        assertEquals(13, textEdit.range.end.character)
    }

    @Test
    fun `getter handler preserves case for acronyms like getURL()`() {
        val content = "obj.getURL()"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryGetter",
            message = "Unnecessary getter call. Use property access instead.",
            line = 0,
            startChar = 4, // Start of "getURL()"
            endChar = 12, // End of "getURL()"
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryGetter")
        assertNotNull(handler, "UnnecessaryGetter handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNotNull(textEdit, "Handler should produce a TextEdit")
        assertEquals("URL", textEdit!!.newText, "Should preserve uppercase for acronyms")
        assertEquals(0, textEdit.range.start.line)
        assertEquals(4, textEdit.range.start.character)
        assertEquals(0, textEdit.range.end.line)
        assertEquals(12, textEdit.range.end.character)
    }

    @Test
    fun `getter handler handles indented getter calls`() {
        val content = "    def result = obj.getName()"
        val lines = content.lines()
        val getterStart = content.indexOf("getName()")
        val getterEnd = getterStart + "getName()".length
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryGetter",
            message = "Unnecessary getter call. Use property access instead.",
            line = 0,
            startChar = getterStart,
            endChar = getterEnd,
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryGetter")
        assertNotNull(handler, "UnnecessaryGetter handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNotNull(textEdit, "Handler should produce a TextEdit")
        assertEquals("name", textEdit!!.newText, "Should convert to property access")
        assertEquals(0, textEdit.range.start.line)
        assertEquals(getterStart, textEdit.range.start.character)
        assertEquals(0, textEdit.range.end.line)
        assertEquals(getterEnd, textEdit.range.end.character)
    }

    @Test
    fun `getter handler handles getID() correctly`() {
        val content = "user.getID()"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryGetter",
            message = "Unnecessary getter call. Use property access instead.",
            line = 0,
            startChar = 5, // Start of "getID()"
            endChar = 12, // End of "getID()"
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryGetter")
        assertNotNull(handler, "UnnecessaryGetter handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNotNull(textEdit, "Handler should produce a TextEdit")
        assertEquals("ID", textEdit!!.newText, "Should preserve uppercase for ID")
    }

    // ========================================================================
    // Property Tests for UnnecessarySetter
    // ========================================================================

    /**
     * Property test: Setter to Property Assignment
     * **Feature: codenarc-lint-fixes, Property 10: Setter to Property Assignment**
     * **Validates: Requirements 4.4**
     *
     * For any method call of the form `obj.setXxx(value)` where Xxx starts with uppercase,
     * applying the UnnecessarySetter fix should result in `obj.xxx = value`.
     */
    @Property(tries = 100)
    fun `property - setter to property assignment produces correct result`(
        @ForAll("setterCalls") setterCall: SetterCallInfo,
    ): Boolean {
        val content = setterCall.code
        val lines = content.lines()

        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessarySetter",
            message = "Unnecessary setter call. Use property assignment instead.",
            line = 0,
            startChar = setterCall.setterStartIndex,
            endChar = setterCall.setterEndIndex,
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessarySetter")
        assertNotNull(handler, "UnnecessarySetter handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        return if (textEdit != null) {
            // The fix should replace the setter call with property assignment
            textEdit.newText == setterCall.expectedPropertyAssignment &&
                textEdit.range.start.character == setterCall.setterStartIndex &&
                textEdit.range.end.character == setterCall.setterEndIndex
        } else {
            // Handler should produce a fix for valid setter calls
            false
        }
    }

    /**
     * Data class to hold setter call test information.
     */
    data class SetterCallInfo(
        val code: String,
        val setterStartIndex: Int,
        val setterEndIndex: Int,
        val expectedPropertyAssignment: String,
    )

    @Provide
    fun setterCalls(): Arbitrary<SetterCallInfo> = Arbitraries.of(
        // Standard setter calls - lowercase first letter of property
        SetterCallInfo("obj.setName(value)", 4, 18, "name = value"),
        SetterCallInfo("obj.setValue(x)", 4, 15, "value = x"),
        SetterCallInfo("obj.setCount(10)", 4, 16, "count = 10"),
        SetterCallInfo("obj.setEnabled(true)", 4, 20, "enabled = true"),
        SetterCallInfo("obj.setDescription('test')", 4, 26, "description = 'test'"),
        // Acronym setters - preserve uppercase (URL, ID, etc.)
        SetterCallInfo("obj.setURL(url)", 4, 15, "URL = url"),
        SetterCallInfo("obj.setID(id)", 4, 13, "ID = id"),
        SetterCallInfo("obj.setURI(uri)", 4, 15, "URI = uri"),
        // Mixed case after acronym
        SetterCallInfo("obj.setURLString(str)", 4, 21, "URLString = str"),
    )

    // ========================================================================
    // Unit Tests for UnnecessarySetter
    // ========================================================================

    @Test
    fun `setter handler converts setName(value) to name = value`() {
        val content = "obj.setName(value)"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessarySetter",
            message = "Unnecessary setter call. Use property assignment instead.",
            line = 0,
            startChar = 4, // Start of "setName(value)"
            endChar = 18, // End of "setName(value)"
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessarySetter")
        assertNotNull(handler, "UnnecessarySetter handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNotNull(textEdit, "Handler should produce a TextEdit")
        assertEquals("name = value", textEdit!!.newText, "Should convert to property assignment")
        assertEquals(0, textEdit.range.start.line)
        assertEquals(4, textEdit.range.start.character)
        assertEquals(0, textEdit.range.end.line)
        assertEquals(18, textEdit.range.end.character)
    }

    @Test
    fun `setter handler preserves case for acronyms like setURL()`() {
        val content = "obj.setURL(url)"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessarySetter",
            message = "Unnecessary setter call. Use property assignment instead.",
            line = 0,
            startChar = 4, // Start of "setURL(url)"
            endChar = 15, // End of "setURL(url)"
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessarySetter")
        assertNotNull(handler, "UnnecessarySetter handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNotNull(textEdit, "Handler should produce a TextEdit")
        assertEquals("URL = url", textEdit!!.newText, "Should preserve uppercase for acronyms")
        assertEquals(0, textEdit.range.start.line)
        assertEquals(4, textEdit.range.start.character)
        assertEquals(0, textEdit.range.end.line)
        assertEquals(15, textEdit.range.end.character)
    }

    @Test
    fun `setter handler handles indented setter calls`() {
        val content = "    obj.setName(value)"
        val lines = content.lines()
        val setterStart = content.indexOf("setName(value)")
        val setterEnd = setterStart + "setName(value)".length
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessarySetter",
            message = "Unnecessary setter call. Use property assignment instead.",
            line = 0,
            startChar = setterStart,
            endChar = setterEnd,
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessarySetter")
        assertNotNull(handler, "UnnecessarySetter handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNotNull(textEdit, "Handler should produce a TextEdit")
        assertEquals("name = value", textEdit!!.newText, "Should convert to property assignment")
        assertEquals(0, textEdit.range.start.line)
        assertEquals(setterStart, textEdit.range.start.character)
        assertEquals(0, textEdit.range.end.line)
        assertEquals(setterEnd, textEdit.range.end.character)
    }

    @Test
    fun `setter handler handles setID() correctly`() {
        val content = "user.setID(id)"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessarySetter",
            message = "Unnecessary setter call. Use property assignment instead.",
            line = 0,
            startChar = 5, // Start of "setID(id)"
            endChar = 14, // End of "setID(id)"
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessarySetter")
        assertNotNull(handler, "UnnecessarySetter handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNotNull(textEdit, "Handler should produce a TextEdit")
        assertEquals("ID = id", textEdit!!.newText, "Should preserve uppercase for ID")
    }

    // ========================================================================
    // Unit Tests for UnnecessaryDotClass
    // ========================================================================

    @Test
    fun `dotClass handler removes dot class from String class`() {
        val content = "String.class"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryDotClass",
            message = "String.class can be simplified to String",
            line = 0,
            startChar = 0,
            endChar = 12, // "String.class".length
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryDotClass")
        assertNotNull(handler, "UnnecessaryDotClass handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNotNull(textEdit, "Handler should produce a TextEdit")
        assertEquals("String", textEdit!!.newText, "Should remove .class suffix")
        assertEquals(0, textEdit.range.start.line)
        assertEquals(0, textEdit.range.start.character)
        assertEquals(0, textEdit.range.end.line)
        assertEquals(12, textEdit.range.end.character)
    }

    @Test
    fun `dotClass handler removes dot class from Integer class`() {
        val content = "Integer.class"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryDotClass",
            message = "Integer.class can be simplified to Integer",
            line = 0,
            startChar = 0,
            endChar = 13, // "Integer.class".length
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryDotClass")
        assertNotNull(handler, "UnnecessaryDotClass handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNotNull(textEdit, "Handler should produce a TextEdit")
        assertEquals("Integer", textEdit!!.newText, "Should remove .class suffix")
    }

    @Test
    fun `dotClass handler handles fully qualified class names`() {
        val content = "java.lang.String.class"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryDotClass",
            message = "java.lang.String.class can be simplified to java.lang.String",
            line = 0,
            startChar = 0,
            endChar = 22, // "java.lang.String.class".length
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryDotClass")
        assertNotNull(handler, "UnnecessaryDotClass handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNotNull(textEdit, "Handler should produce a TextEdit")
        assertEquals("java.lang.String", textEdit!!.newText, "Should remove .class suffix from fully qualified name")
    }

    @Test
    fun `dotClass handler handles indented class literals`() {
        val content = "    def type = String.class"
        val lines = content.lines()
        val classLiteralStart = content.indexOf("String.class")
        val classLiteralEnd = classLiteralStart + "String.class".length
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryDotClass",
            message = "String.class can be simplified to String",
            line = 0,
            startChar = classLiteralStart,
            endChar = classLiteralEnd,
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryDotClass")
        assertNotNull(handler, "UnnecessaryDotClass handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNotNull(textEdit, "Handler should produce a TextEdit")
        assertEquals("String", textEdit!!.newText, "Should remove .class suffix")
        assertEquals(0, textEdit.range.start.line)
        assertEquals(classLiteralStart, textEdit.range.start.character)
        assertEquals(0, textEdit.range.end.line)
        assertEquals(classLiteralEnd, textEdit.range.end.character)
    }

    @Test
    fun `dotClass handler handles inner class references`() {
        val content = "Map.Entry.class"
        val lines = content.lines()
        val diagnostic = TestDiagnosticFactory.createCodeNarcDiagnostic(
            code = "UnnecessaryDotClass",
            message = "Map.Entry.class can be simplified to Map.Entry",
            line = 0,
            startChar = 0,
            endChar = 15, // "Map.Entry.class".length
        )

        val handler = FixHandlerRegistry.getHandler("UnnecessaryDotClass")
        assertNotNull(handler, "UnnecessaryDotClass handler should be registered")

        val context = FixContext(diagnostic, content, lines, "file:///test.groovy")
        val textEdit = handler!!(context)

        assertNotNull(textEdit, "Handler should produce a TextEdit")
        assertEquals("Map.Entry", textEdit!!.newText, "Should remove .class suffix from inner class")
    }
}
