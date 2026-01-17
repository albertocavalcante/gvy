package com.github.albertocavalcante.diagnostics.codenarc

import io.mockk.every
import io.mockk.mockk
import org.codenarc.rule.Rule
import org.codenarc.rule.Violation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Comprehensive test suite for RuleRangeCalculator.
 *
 * This test suite ensures that diagnostic ranges (squiggle lines) are positioned
 * correctly for ALL CodeNarc rules enabled in our configuration.
 *
 * Test Pattern:
 * 1. Create a source line with the violation
 * 2. Create a mock violation with rule name and message
 * 3. Call RuleRangeCalculator.calculateRange
 * 4. Assert exact start and end column positions
 */
class RuleRangeCalculatorTest {

    // ==========================================
    // FORMATTING RULES (rulesets/formatting.xml)
    // ==========================================

    @Test
    fun `Indentation - should highlight incorrectly indented line`() {
        val sourceLine = "   def x = 1" // 3 spaces instead of 4
        val violation = createViolation("Indentation", "Incorrect indentation: expected 4, was 3", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight from start of line to first non-whitespace character
        assertEquals(0, start)
        assertEquals(3, end)
    }

    @Test
    fun `SpaceAfterComma - should highlight missing space after comma`() {
        val sourceLine = "def list = [1,2, 3]" // Missing space after first comma
        val violation = createViolation("SpaceAfterComma", "The comma should be followed by a space", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight the comma without space after it
        assertEquals(13, start) // Position of ','
        assertEquals(14, end)
    }

    @Test
    fun `SpaceBeforeOpeningBrace - should highlight missing space before brace`() {
        val sourceLine = "class Test{" // Missing space before {
        val violation =
            createViolation("SpaceBeforeOpeningBrace", "The opening brace should be preceded by a space", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight the opening brace
        assertEquals(10, start) // Position of '{'
        assertEquals(11, end)
    }

    @Test
    fun `SpaceAfterOpeningBrace - should highlight missing space after brace`() {
        val sourceLine = "def closure = {x -> x + 1}" // Missing space after {
        val violation =
            createViolation("SpaceAfterOpeningBrace", "The opening brace should be followed by a space", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight the opening brace
        assertEquals(14, start)
        assertEquals(15, end)
    }

    @Test
    fun `SpaceBeforeClosingBrace - should highlight missing space before closing brace`() {
        val sourceLine = "def closure = { x -> x + 1}" // Missing space before }
        val violation =
            createViolation("SpaceBeforeClosingBrace", "The closing brace should be preceded by a space", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight the closing brace
        assertEquals(26, start)
        assertEquals(27, end)
    }

    @Test
    fun `SpaceAfterClosingBrace - should highlight missing space after closing brace`() {
        val sourceLine = "if (true) {}else { }" // Missing space after }
        val violation =
            createViolation(
                "SpaceAfterClosingBrace",
                "The closing brace for the if statement should be followed by a space",
                sourceLine,
            )

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight the closing brace
        assertEquals(11, start)
        assertEquals(12, end)
    }

    @Test
    fun `SpaceInsideParentheses - should highlight space inside parentheses`() {
        val sourceLine = "def method( x, y )" // Spaces inside parentheses
        val violation =
            createViolation(
                "SpaceInsideParentheses",
                "There should be no space after the opening parenthesis",
                sourceLine,
            )

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight the opening parenthesis with space
        assertEquals(10, start) // Position of '('
        assertEquals(12, end) // Include the space after
    }

    @Test
    fun `SpaceAroundClosureArrow - should highlight missing space around arrow`() {
        val sourceLine = "def closure = {x->x + 1}" // Missing spaces around ->
        val violation =
            createViolation("SpaceAroundClosureArrow", "There should be a space before the closure arrow", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight the arrow operator
        assertEquals(16, start) // Position of '->'
        assertEquals(18, end)
    }

    @Test
    fun `BlockEndsWithBlankLine - should highlight the blank line`() {
        val sourceLine = "" // Empty line at end of block
        val violation = createViolation("BlockEndsWithBlankLine", "Block ends with a blank line", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Empty line should have (0, 0) range
        assertEquals(0, start)
        assertEquals(0, end)
    }

    @Test
    fun `BlockStartsWithBlankLine - should highlight the blank line`() {
        val sourceLine = "" // Empty line at start of block
        val violation = createViolation("BlockStartsWithBlankLine", "Block starts with a blank line", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Empty line should have (0, 0) range
        assertEquals(0, start)
        assertEquals(0, end)
    }

    @Test
    fun `BracesForClass - should highlight class keyword`() {
        val sourceLine = "class Test" // Missing braces
        val violation = createViolation("BracesForClass", "Class should have braces", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "class" keyword
        assertEquals(0, start)
        assertEquals(5, end)
    }

    @Test
    fun `BracesForMethod - should highlight method keyword`() {
        val sourceLine = "    def method()" // Missing braces
        val violation = createViolation("BracesForMethod", "Method should have braces", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "def" keyword
        assertEquals(4, start)
        assertEquals(7, end)
    }

    @Test
    fun `BracesForIfElse - should highlight if keyword`() {
        val sourceLine = "if (true) return" // Missing braces
        val violation = createViolation("BracesForIfElse", "The if statement should have braces", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "if" keyword
        assertEquals(0, start)
        assertEquals(2, end)
    }

    @Test
    fun `BracesForLoop - should highlight loop keyword`() {
        val sourceLine = "for (i in 1..10) println i" // Missing braces
        val violation = createViolation("BracesForLoop", "The for loop should have braces", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "for" keyword
        assertEquals(0, start)
        assertEquals(3, end)
    }

    @Test
    fun `BracesForTryCatchFinally - should highlight try keyword`() {
        val sourceLine = "try { } catch (Exception e) println e" // Missing braces on catch
        val violation = createViolation("BracesForTryCatchFinally", "The catch clause should have braces", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "catch" keyword when catch is missing braces
        assertEquals(8, start)
        assertEquals(13, end)
    }

    @Test
    fun `ClassEndsWithBlankLine - should highlight the blank line`() {
        val sourceLine = "" // Empty line at end of class
        val violation = createViolation("ClassEndsWithBlankLine", "Class ends with a blank line", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        assertEquals(0, start)
        assertEquals(0, end)
    }

    @Test
    fun `ClassStartsWithBlankLine - should highlight the blank line`() {
        val sourceLine = "" // Empty line at start of class
        val violation = createViolation("ClassStartsWithBlankLine", "Class starts with a blank line", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        assertEquals(0, start)
        assertEquals(0, end)
    }

    @Test
    fun `ClosureStatementOnOpeningLineOfMultipleLineClosure - should highlight closure arrow`() {
        val sourceLine = "def closure = { x ->" // Statement on opening line
        val violation = createViolation(
            "ClosureStatementOnOpeningLineOfMultipleLineClosure",
            "Closure has a statement on the opening line",
            sourceLine,
        )

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight the arrow
        assertEquals(18, start)
        assertEquals(20, end)
    }

    @Test
    fun `LineLength - should highlight entire line`() {
        val sourceLine = "def veryLongLineThatExceedsTheMaximumLengthAndShouldBeReported = 'This is too long'"
        val violation = createViolation("LineLength", "Line length is 85 characters", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight the entire line
        assertEquals(0, start)
        assertEquals(sourceLine.length, end)
    }

    @Test
    fun `MissingBlankLineAfterImports - should highlight first line after imports`() {
        val sourceLine = "class Test {" // No blank line after imports
        val violation = createViolation("MissingBlankLineAfterImports", "Missing blank line after imports", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "class" keyword
        assertEquals(0, start)
        assertEquals(5, end)
    }

    @Test
    fun `MissingBlankLineAfterPackage - should highlight first line after package`() {
        val sourceLine = "import java.util.*" // No blank line after package
        val violation = createViolation("MissingBlankLineAfterPackage", "Missing blank line after package", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "import" keyword
        assertEquals(0, start)
        assertEquals(6, end)
    }

    // ==========================================
    // NAMING CONVENTION RULES (rulesets/naming.xml)
    // ==========================================

    @Test
    fun `ClassName - should highlight class name`() {
        val sourceLine = "class myClass {" // Should start with uppercase
        val violation = createViolation("ClassName", "The class name [myClass] does not match the pattern", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight the class name
        assertEquals(6, start) // Position of 'myClass'
        assertEquals(13, end)
    }

    @Test
    fun `MethodName - should highlight method name`() {
        val sourceLine = "    def MyMethod() {" // Should start with lowercase
        val violation =
            createViolation("MethodName", "The method name [MyMethod] does not match the pattern", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight the method name
        assertEquals(8, start) // Position of 'MyMethod'
        assertEquals(16, end)
    }

    @Test
    fun `VariableName - should highlight variable name`() {
        val sourceLine = "def MyVariable = 1" // Should start with lowercase
        val violation =
            createViolation("VariableName", "The variable name [MyVariable] does not match the pattern", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight the variable name
        assertEquals(4, start) // Position of 'MyVariable'
        assertEquals(14, end)
    }

    @Test
    fun `FieldName - should highlight field name`() {
        val sourceLine = "    private String MyField" // Should start with lowercase
        val violation = createViolation("FieldName", "The field name [MyField] does not match the pattern", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight the field name
        assertEquals(19, start) // Position of 'MyField'
        assertEquals(26, end)
    }

    @Test
    fun `ParameterName - should highlight parameter name`() {
        val sourceLine = "def method(String MyParam) {" // Should start with lowercase
        val violation =
            createViolation("ParameterName", "The parameter name [MyParam] does not match the pattern", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight the parameter name
        assertEquals(18, start) // Position of 'MyParam'
        assertEquals(25, end)
    }

    @Test
    fun `PropertyName - should highlight property name`() {
        val sourceLine = "    String MyProperty" // Should start with lowercase
        val violation =
            createViolation("PropertyName", "The property name [MyProperty] does not match the pattern", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight the property name
        assertEquals(11, start) // Position of 'MyProperty'
        assertEquals(21, end)
    }

    @Test
    fun `PackageName - should highlight package name`() {
        val sourceLine = "package com.Example.app" // Should be all lowercase
        val violation =
            createViolation("PackageName", "The package name [com.Example.app] does not match the pattern", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight the package name
        assertEquals(8, start) // Position of 'com.Example.app'
        assertEquals(23, end)
    }

    // ==========================================
    // BASIC RULES (rulesets/basic.xml)
    // ==========================================

    @Test
    fun `EmptyClass - should highlight class keyword`() {
        val sourceLine = "class EmptyClass {}" // Empty class
        val violation = createViolation("EmptyClass", "Class EmptyClass is empty", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "class" keyword
        assertEquals(0, start)
        assertEquals(5, end)
    }

    @Test
    fun `EmptyMethod - should highlight method name`() {
        val sourceLine = "    def emptyMethod() {}" // Empty method
        val violation = createViolation("EmptyMethod", "Method emptyMethod is empty", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "def" keyword
        assertEquals(4, start)
        assertEquals(7, end)
    }

    @Test
    fun `EmptyIfStatement - should highlight if keyword`() {
        val sourceLine = "if (condition) {}" // Empty if
        val violation = createViolation("EmptyIfStatement", "The if statement is empty", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "if" keyword
        assertEquals(0, start)
        assertEquals(2, end)
    }

    @Test
    fun `EmptyElseBlock - should highlight else keyword`() {
        val sourceLine = "} else {}" // Empty else
        val violation = createViolation("EmptyElseBlock", "The else block is empty", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "else" keyword
        assertEquals(2, start)
        assertEquals(6, end)
    }

    @Test
    fun `EmptyTryBlock - should highlight try keyword`() {
        val sourceLine = "try {}" // Empty try
        val violation = createViolation("EmptyTryBlock", "The try block is empty", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "try" keyword
        assertEquals(0, start)
        assertEquals(3, end)
    }

    @Test
    fun `EmptyCatchBlock - should highlight catch keyword`() {
        val sourceLine = "} catch (Exception e) {}" // Empty catch
        val violation = createViolation("EmptyCatchBlock", "The catch block is empty", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "catch" keyword
        assertEquals(2, start)
        assertEquals(7, end)
    }

    @Test
    fun `EmptyFinallyBlock - should highlight finally keyword`() {
        val sourceLine = "} finally {}" // Empty finally
        val violation = createViolation("EmptyFinallyBlock", "The finally block is empty", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "finally" keyword
        assertEquals(2, start)
        assertEquals(9, end)
    }

    @Test
    fun `EmptyForStatement - should highlight for keyword`() {
        val sourceLine = "for (i in 1..10) {}" // Empty for
        val violation = createViolation("EmptyForStatement", "The for statement is empty", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "for" keyword
        assertEquals(0, start)
        assertEquals(3, end)
    }

    @Test
    fun `EmptyWhileStatement - should highlight while keyword`() {
        val sourceLine = "while (condition) {}" // Empty while
        val violation = createViolation("EmptyWhileStatement", "The while statement is empty", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "while" keyword
        assertEquals(0, start)
        assertEquals(5, end)
    }

    @Test
    fun `EmptySwitchStatement - should highlight switch keyword`() {
        val sourceLine = "switch (value) {}" // Empty switch
        val violation = createViolation("EmptySwitchStatement", "The switch statement is empty", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "switch" keyword
        assertEquals(0, start)
        assertEquals(6, end)
    }

    @Test
    fun `EmptySynchronizedStatement - should highlight synchronized keyword`() {
        val sourceLine = "synchronized (lock) {}" // Empty synchronized
        val violation = createViolation("EmptySynchronizedStatement", "The synchronized statement is empty", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "synchronized" keyword
        assertEquals(0, start)
        assertEquals(12, end)
    }

    // ==========================================
    // GROOVYISM RULES (rulesets/groovyism.xml)
    // ==========================================

    @Test
    fun `GStringExpressionWithinString - should highlight the GString`() {
        val sourceLine = "def msg = \"Value is \${value}\"" // GString with expression
        val violation = createViolation(
            "GStringExpressionWithinString",
            "GString expression within string",
            sourceLine,
        )

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight the entire GString
        assertEquals(10, start) // Position of opening quote
        assertEquals(29, end) // Position after closing quote
    }

    @Test
    fun `ExplicitCallToEqualsMethod - should highlight equals call`() {
        val sourceLine = "if (a.equals(b)) {" // Should use ==
        val violation = createViolation(
            "ExplicitCallToEqualsMethod",
            "Use == instead of .equals()",
            sourceLine,
        )

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight ".equals"
        assertEquals(5, start) // Position of '.'
        assertEquals(12, end) // After "equals"
    }

    @Test
    fun `ExplicitCallToCompareToMethod - should highlight compareTo call`() {
        val sourceLine = "if (a.compareTo(b) == 0) {" // Should use ==
        val violation = createViolation(
            "ExplicitCallToCompareToMethod",
            "Use comparison operators instead of .compareTo()",
            sourceLine,
        )

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight ".compareTo"
        assertEquals(5, start) // Position of '.'
        assertEquals(15, end) // After "compareTo"
    }

    @Test
    fun `GetterMethodCouldBeProperty - should highlight method name`() {
        val sourceLine = "    def getName() { return name }" // Could be property
        val violation = createViolation(
            "GetterMethodCouldBeProperty",
            "The method [getName] could be a property",
            sourceLine,
        )

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "getName"
        assertEquals(8, start)
        assertEquals(15, end)
    }

    // ==========================================
    // EXCEPTION RULES (rulesets/exceptions.xml)
    // ==========================================

    @Test
    fun `CatchException - should highlight Exception type`() {
        val sourceLine = "} catch (Exception e) {" // Too broad
        val violation = createViolation("CatchException", "Catching Exception is too broad", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "Exception"
        assertEquals(9, start) // Position of 'Exception'
        assertEquals(18, end)
    }

    @Test
    fun `CatchThrowable - should highlight Throwable type`() {
        val sourceLine = "} catch (Throwable t) {" // Too broad
        val violation = createViolation("CatchThrowable", "Catching Throwable is too broad", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "Throwable"
        assertEquals(9, start) // Position of 'Throwable'
        assertEquals(18, end)
    }

    @Test
    fun `ThrowException - should highlight throw keyword`() {
        val sourceLine = "throw new Exception('error')" // Too generic
        val violation = createViolation("ThrowException", "Throwing Exception is too generic", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "throw" keyword
        assertEquals(0, start)
        assertEquals(5, end)
    }

    @Test
    fun `ThrowRuntimeException - should highlight throw keyword`() {
        val sourceLine = "throw new RuntimeException('error')" // Too generic
        val violation = createViolation("ThrowRuntimeException", "Throwing RuntimeException is too generic", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "throw" keyword
        assertEquals(0, start)
        assertEquals(5, end)
    }

    @Test
    fun `CatchNullPointerException - should highlight NullPointerException`() {
        val sourceLine = "} catch (NullPointerException e) {" // Should prevent, not catch
        val violation =
            createViolation("CatchNullPointerException", "Catching NullPointerException is a bad practice", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight "NullPointerException"
        assertEquals(9, start)
        assertEquals(29, end) // "NullPointerException" is 20 characters, so 9 + 20 = 29
    }

    // ==========================================
    // ROBUSTNESS TESTS - Verify Actual Substrings
    // ==========================================

    @Test
    fun `keyword highlighting should match exact substring - class`() {
        val sourceLine = "class MyBadClass {}"
        val violation = createViolation("EmptyClass", "Class has no methods", sourceLine)

        val range = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Verify the exact keyword is highlighted
        assertRangeHighlights(sourceLine, range, "class")
    }

    @Test
    fun `keyword highlighting should match exact substring - def`() {
        val sourceLine = "def myMethod() {}"
        val violation = createViolation("EmptyMethod", "Method is empty", sourceLine)

        val range = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Verify the exact keyword is highlighted
        assertRangeHighlights(sourceLine, range, "def")
    }

    @Test
    fun `identifier highlighting should match exact substring - variable name`() {
        val sourceLine = "def BadName = 1"
        val violation = createViolation(
            "VariableName",
            "The variable name [BadName] in class X does not match [a-z][a-zA-Z0-9]*",
            sourceLine,
        )

        val range = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Verify the exact identifier is highlighted
        assertRangeHighlights(sourceLine, range, "BadName")
    }

    @Test
    fun `identifier highlighting should match exact substring - method name`() {
        val sourceLine = "def BadMethodName() {}"
        val violation = createViolation(
            "MethodName",
            "The method name [BadMethodName] in class X does not match [a-z][a-zA-Z0-9]*",
            sourceLine,
        )

        val range = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Verify the exact identifier is highlighted
        assertRangeHighlights(sourceLine, range, "BadMethodName")
    }

    @Test
    fun `exception type highlighting should match exact substring`() {
        val sourceLine = "} catch (Exception e) {"
        val violation = createViolation("CatchException", "Catching Exception is too broad", sourceLine)

        val range = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Verify the exact exception type is highlighted
        assertRangeHighlights(sourceLine, range, "Exception")
    }

    @Test
    fun `comma highlighting should match exact character`() {
        val sourceLine = "def list = [1,2, 3]"
        val violation = createViolation("SpaceAfterComma", "Missing space after comma", sourceLine)

        val range = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Verify the exact comma is highlighted
        assertRangeHighlights(sourceLine, range, ",")
    }

    @Test
    fun `indentation highlighting should match whitespace prefix`() {
        val sourceLine = "   def x = 1"
        val violation = createViolation("Indentation", "Incorrect indentation", sourceLine)

        val range = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Verify the exact whitespace is highlighted
        assertRangeHighlights(sourceLine, range, "   ")
    }

    @Test
    fun `range must be within source line bounds`() {
        val sourceLine = "class X {}"
        val violation = createViolation("EmptyClass", "Empty class", sourceLine)

        val (start, end) = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Verify bounds
        assert(start >= 0) { "Start must be non-negative" }
        assert(end <= sourceLine.length) { "End must not exceed line length" }
        assert(start < end) { "Range must be non-empty" }
    }

    @Test
    fun `typed variable declaration should highlight variable name not type`() {
        val sourceLine = "String badName = 'test'"
        val violation = createViolation(
            "VariableName",
            "The variable name [badName] does not match [A-Z][a-zA-Z0-9]*",
            sourceLine,
        )

        val range = RuleRangeCalculator.calculateRange(violation, sourceLine)

        // Should highlight 'badName', not 'String'
        assertRangeHighlights(sourceLine, range, "badName")
    }

    @Test
    fun `try-catch-finally should highlight correct keyword based on message`() {
        // Test try
        val tryLine = "try { } catch (Exception e) { println e }"
        val tryViolation = createViolation("BracesForTryCatchFinally", "The try block should have braces", tryLine)
        val tryRange = RuleRangeCalculator.calculateRange(tryViolation, tryLine)
        assertRangeHighlights(tryLine, tryRange, "try")

        // Test catch
        val catchLine = "try { } catch (Exception e) println e"
        val catchViolation =
            createViolation("BracesForTryCatchFinally", "The catch clause should have braces", catchLine)
        val catchRange = RuleRangeCalculator.calculateRange(catchViolation, catchLine)
        assertRangeHighlights(catchLine, catchRange, "catch")

        // Test finally
        val finallyLine = "try { } catch (Exception e) { } finally println 'done'"
        val finallyViolation =
            createViolation("BracesForTryCatchFinally", "The finally block should have braces", finallyLine)
        val finallyRange = RuleRangeCalculator.calculateRange(finallyViolation, finallyLine)
        assertRangeHighlights(finallyLine, finallyRange, "finally")
    }

    // ==========================================
    // Helper Methods
    // ==========================================

    private fun createViolation(ruleName: String, message: String, sourceLine: String): Violation {
        val rule = mockk<Rule>()
        every { rule.name } returns ruleName
        every { rule.priority } returns 2

        val violation = mockk<Violation>()
        every { violation.rule } returns rule
        every { violation.message } returns message
        every { violation.sourceLine } returns sourceLine
        every { violation.lineNumber } returns 1

        return violation
    }

    /**
     * Assert that the calculated range highlights the expected text.
     * This ensures the squiggle line appears on the correct code.
     *
     * @param sourceLine The source code line
     * @param range The calculated (start, end) range
     * @param expectedText The exact text that should be highlighted
     */
    private fun assertRangeHighlights(sourceLine: String, range: Pair<Int, Int>, expectedText: String) {
        val (start, end) = range

        // Verify range is within bounds
        assert(start >= 0) { "Start position $start is negative" }
        assert(end <= sourceLine.length) { "End position $end exceeds source line length ${sourceLine.length}" }
        assert(start < end) { "Start position $start must be less than end position $end" }

        // Extract and verify the actual highlighted text
        val actualText = sourceLine.substring(start, end)
        assertEquals(
            expectedText,
            actualText,
            "Range ($start, $end) should highlight '$expectedText' but got '$actualText'\nSource: $sourceLine",
        )
    }
}
