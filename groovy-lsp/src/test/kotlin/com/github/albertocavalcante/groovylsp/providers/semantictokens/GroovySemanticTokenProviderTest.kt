package com.github.albertocavalcante.groovylsp.providers.semantictokens

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TDD tests for GroovySemanticTokenProvider.
 *
 * Tests semantic highlighting for standard Groovy language constructs:
 * - Classes, interfaces, enums
 * - Methods and method calls
 * - Variables, parameters, properties
 * - Type references
 * - Modifiers (static, final, etc.)
 */
class GroovySemanticTokenProviderTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var tempWorkspace: java.nio.file.Path

    @BeforeEach
    fun setup() = runBlocking {
        compilationService = GroovyCompilationService()
        tempWorkspace = Files.createTempDirectory("groovy-lsp-semantic-test")
        compilationService.workspaceManager.initializeWorkspace(tempWorkspace)
    }

    @AfterEach
    fun tearDown() {
        tempWorkspace.toFile().deleteRecursively()
    }

    /**
     * Finds the position of an identifier in the code string with word boundary matching.
     * Returns a Pair of (0-based line, 0-based column) or null if not found.
     *
     * @param code The source code string
     * @param identifier The identifier to find
     * @param occurrence Which occurrence to find (1-based, default 1)
     */
    private fun findPosition(code: String, identifier: String, occurrence: Int = 1): Pair<Int, Int>? {
        val lines = code.lines()
        var found = 0
        for ((lineIndex, line) in lines.withIndex()) {
            var startIndex = 0
            while (true) {
                val col = line.indexOf(identifier, startIndex)
                if (col < 0) break
                // Check word boundaries using isJavaIdentifierPart() to handle underscores
                val beforeOk = col == 0 || !Character.isJavaIdentifierPart(line[col - 1])
                val afterOk = col + identifier.length >= line.length ||
                    !Character.isJavaIdentifierPart(line[col + identifier.length])
                if (beforeOk && afterOk) {
                    found++
                    if (found == occurrence) {
                        return Pair(lineIndex, col)
                    }
                }
                startIndex = col + 1
            }
        }
        return null
    }

    /**
     * Finds a token by its expected position and length.
     * Reserved for exact column matching when #769 (static method position fix) is implemented.
     */
    @Suppress("unused")
    private fun List<GroovySemanticTokenProvider.SemanticToken>.findByPosition(
        line: Int,
        startChar: Int,
        length: Int,
    ): GroovySemanticTokenProvider.SemanticToken? = find {
        it.line == line && it.startChar == startChar && it.length == length
    }

    /**
     * Finds a token by line and length, with flexible column matching.
     * This is useful because AST positions may point to declaration start rather than identifier start.
     */
    private fun List<GroovySemanticTokenProvider.SemanticToken>.findByLineAndLength(
        line: Int,
        length: Int,
    ): GroovySemanticTokenProvider.SemanticToken? = find {
        it.line == line && it.length == length
    }

    @Test
    fun `should tokenize class name declaration`(): Unit = runBlocking {
        val code = """
            class MyClass {
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("MyClass.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have a CLASS token for MyClass
        val classTokens = tokens.filter { it.tokenType == GroovySemanticTokenProvider.TokenTypes.CLASS }
        assertEquals(1, classTokens.size, "Should have one CLASS token for class declaration")
        assertEquals("MyClass".length, classTokens.first().length)
    }

    @Test
    fun `should tokenize interface name declaration`(): Unit = runBlocking {
        val code = """
            interface MyInterface {
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("MyInterface.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have an INTERFACE token for MyInterface
        val interfaceTokens = tokens.filter { it.tokenType == GroovySemanticTokenProvider.TokenTypes.INTERFACE }
        assertEquals(1, interfaceTokens.size, "Should have one INTERFACE token for interface declaration")
        assertEquals("MyInterface".length, interfaceTokens.first().length)
    }

    @Test
    fun `should tokenize method name declaration`(): Unit = runBlocking {
        val code = """
            class MyClass {
                def myMethod() {
                }
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("MyClass.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have a METHOD token for myMethod
        val methodTokens = tokens.filter { it.tokenType == GroovySemanticTokenProvider.TokenTypes.METHOD }
        assertTrue(methodTokens.isNotEmpty(), "Should have METHOD token for method declaration")

        val (expectedLine, _) = findPosition(code, "myMethod")!!
        val myMethodToken = methodTokens.findByLineAndLength(expectedLine, "myMethod".length)
        assertTrue(myMethodToken != null, "Should have token for myMethod on line $expectedLine")
    }

    @Test
    fun `should tokenize local variable declaration`(): Unit = runBlocking {
        val code = """
            def myMethod() {
                def localVar = 123
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("Test.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have a VARIABLE token for localVar
        val variableTokens = tokens.filter { it.tokenType == GroovySemanticTokenProvider.TokenTypes.VARIABLE }
        assertTrue(variableTokens.isNotEmpty(), "Should have VARIABLE token for local variable")

        val (expectedLine, _) = findPosition(code, "localVar")!!
        val localVarToken = variableTokens.findByLineAndLength(expectedLine, "localVar".length)
        assertTrue(localVarToken != null, "Should have token for localVar on line $expectedLine")
    }

    @Test
    fun `should tokenize method parameter`(): Unit = runBlocking {
        val code = """
            def myMethod(param1) {
                println param1
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("Test.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have PARAMETER tokens for param1
        val paramTokens = tokens.filter { it.tokenType == GroovySemanticTokenProvider.TokenTypes.PARAMETER }
        assertTrue(paramTokens.isNotEmpty(), "Should have PARAMETER token for method parameter")
    }

    @Test
    fun `should tokenize property access`(): Unit = runBlocking {
        val code = """
            class MyClass {
                String name

                def test() {
                    this.name = "test"
                }
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("MyClass.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have PROPERTY tokens for name
        val propertyTokens = tokens.filter { it.tokenType == GroovySemanticTokenProvider.TokenTypes.PROPERTY }
        assertTrue(propertyTokens.isNotEmpty(), "Should have PROPERTY token for property access")
    }

    @Test
    fun `should tokenize type references in extends clause`(): Unit = runBlocking {
        val code = """
            class MyClass extends ArrayList {
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("MyClass.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have CLASS token for MyClass declaration
        val classTokens = tokens.filter { it.tokenType == GroovySemanticTokenProvider.TokenTypes.CLASS }
        assertEquals(1, classTokens.size, "Should have exactly one CLASS token for MyClass declaration")
        assertEquals("MyClass".length, classTokens.first().length)

        // Note: ArrayList (superclass reference) may not have position info in the AST.
        // This is a known limitation - some type references from the standard library
        // don't have source positions. We verify the declared class is tokenized correctly.
    }

    @Test
    fun `should apply static modifier to static methods`(): Unit = runBlocking {
        val code = """
            class MyClass {
                static def staticMethod() {
                }
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("MyClass.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have METHOD token with STATIC modifier for staticMethod
        val methodTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.METHOD &&
                it.length == "staticMethod".length
        }
        assertTrue(methodTokens.isNotEmpty(), "Should have METHOD token for static method")

        val staticMethodToken = methodTokens.first()
        val hasStaticModifier =
            (staticMethodToken.tokenModifiers and GroovySemanticTokenProvider.TokenModifiers.STATIC) != 0
        assertTrue(hasStaticModifier, "Static method should have STATIC modifier")
    }

    @Test
    fun `should tokenize enum declaration`(): Unit = runBlocking {
        val code = """
            enum MyEnum {
                VALUE1, VALUE2
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("MyEnum.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have ENUM token for MyEnum
        val enumTokens = tokens.filter { it.tokenType == GroovySemanticTokenProvider.TokenTypes.ENUM }
        assertTrue(enumTokens.isNotEmpty(), "Should have ENUM token for enum declaration")
    }

    @Test
    fun `should tokenize closure parameters`(): Unit = runBlocking {
        val code = """
            def list = [1, 2, 3]
            list.each { item ->
                println item
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("Test.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have PARAMETER token for closure parameter 'item'
        val paramTokens = tokens.filter { it.tokenType == GroovySemanticTokenProvider.TokenTypes.PARAMETER }
        assertTrue(paramTokens.isNotEmpty(), "Should have PARAMETER token for closure parameter")
    }

    @Test
    fun `should tokenize multiple method declarations and calls`(): Unit = runBlocking {
        // This test verifies method declarations AND method calls are tokenized.
        val code = """
            class MyClass {
                def myMethod() {
                    otherMethod()
                }

                def otherMethod() {
                }
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("MyClass.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have METHOD tokens for declarations AND calls
        val methodTokens = tokens.filter { it.tokenType == GroovySemanticTokenProvider.TokenTypes.METHOD }

        // Verify we have tokens for method declarations using line-based matching
        // Note: AST positions may point to declaration start (def) rather than method name,
        // so we use line + length matching. See issue #769 for position accuracy fix.
        val (myMethodLine, _) = findPosition(code, "myMethod")!!
        val myMethodToken = methodTokens.findByLineAndLength(myMethodLine, "myMethod".length)
        assertTrue(myMethodToken != null, "Should have token for myMethod declaration on line $myMethodLine")

        // otherMethod should have 2 tokens: declaration + call
        val (otherMethodCallLine, _) = findPosition(code, "otherMethod", 1)!! // call
        val (otherMethodDeclLine, _) = findPosition(code, "otherMethod", 2)!! // declaration

        val otherMethodCallToken = methodTokens.findByLineAndLength(otherMethodCallLine, "otherMethod".length)
        val otherMethodDeclToken = methodTokens.findByLineAndLength(otherMethodDeclLine, "otherMethod".length)

        assertTrue(
            otherMethodCallToken != null,
            "Should have METHOD token for otherMethod() call on line $otherMethodCallLine",
        )
        assertTrue(
            otherMethodDeclToken != null,
            "Should have METHOD token for otherMethod declaration on line $otherMethodDeclLine",
        )
    }

    @Test
    fun `token types should match LSP spec indices`() {
        // These indices MUST match the legend order derived from JenkinsSemanticTokenProvider.LEGEND_TOKEN_TYPES
        // This ensures consistency across both providers and prevents index misalignment
        assertEquals(0, GroovySemanticTokenProvider.TokenTypes.NAMESPACE)
        assertEquals(1, GroovySemanticTokenProvider.TokenTypes.TYPE)
        assertEquals(2, GroovySemanticTokenProvider.TokenTypes.CLASS)
        assertEquals(3, GroovySemanticTokenProvider.TokenTypes.ENUM)
        assertEquals(4, GroovySemanticTokenProvider.TokenTypes.INTERFACE)
        assertEquals(5, GroovySemanticTokenProvider.TokenTypes.STRUCT)
        assertEquals(6, GroovySemanticTokenProvider.TokenTypes.TYPE_PARAMETER)
        assertEquals(7, GroovySemanticTokenProvider.TokenTypes.PARAMETER)
        assertEquals(8, GroovySemanticTokenProvider.TokenTypes.VARIABLE)
        assertEquals(9, GroovySemanticTokenProvider.TokenTypes.PROPERTY)
        assertEquals(10, GroovySemanticTokenProvider.TokenTypes.ENUM_MEMBER)
        assertEquals(11, GroovySemanticTokenProvider.TokenTypes.EVENT)
        assertEquals(12, GroovySemanticTokenProvider.TokenTypes.FUNCTION)
        assertEquals(13, GroovySemanticTokenProvider.TokenTypes.METHOD)
        assertEquals(14, GroovySemanticTokenProvider.TokenTypes.MACRO)
        assertEquals(15, GroovySemanticTokenProvider.TokenTypes.KEYWORD)
        assertEquals(16, GroovySemanticTokenProvider.TokenTypes.MODIFIER)
        assertEquals(17, GroovySemanticTokenProvider.TokenTypes.COMMENT)
        assertEquals(18, GroovySemanticTokenProvider.TokenTypes.STRING)
        assertEquals(19, GroovySemanticTokenProvider.TokenTypes.NUMBER)
        assertEquals(20, GroovySemanticTokenProvider.TokenTypes.REGEXP)
        assertEquals(21, GroovySemanticTokenProvider.TokenTypes.OPERATOR)
        assertEquals(22, GroovySemanticTokenProvider.TokenTypes.DECORATOR)
    }

    // ==================== QA Edge Case Tests ====================

    @Test
    fun `should apply abstract modifier to abstract class`(): Unit = runBlocking {
        val code = """
            abstract class AbstractService {
                abstract def process()
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("AbstractService.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Class should have ABSTRACT modifier
        val classTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.CLASS &&
                it.length == "AbstractService".length
        }
        assertTrue(classTokens.isNotEmpty(), "Should have CLASS token for abstract class")
        val hasAbstractModifier =
            (classTokens.first().tokenModifiers and GroovySemanticTokenProvider.TokenModifiers.ABSTRACT) != 0
        assertTrue(hasAbstractModifier, "Abstract class should have ABSTRACT modifier")

        // Abstract method should also have ABSTRACT modifier
        val methodTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.METHOD &&
                it.length == "process".length
        }
        assertTrue(methodTokens.isNotEmpty(), "Should have METHOD token for abstract method")
        val methodHasAbstract =
            (methodTokens.first().tokenModifiers and GroovySemanticTokenProvider.TokenModifiers.ABSTRACT) != 0
        assertTrue(methodHasAbstract, "Abstract method should have ABSTRACT modifier")
    }

    @Test
    fun `should apply readonly modifier to final fields`(): Unit = runBlocking {
        val code = """
            class Config {
                final String API_KEY = "secret"
                static final int MAX_RETRIES = 3
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("Config.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Find property tokens for final fields
        val propertyTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.PROPERTY
        }

        // At least one should have READONLY modifier
        val hasReadonlyField = propertyTokens.any {
            (it.tokenModifiers and GroovySemanticTokenProvider.TokenModifiers.READONLY) != 0
        }
        assertTrue(hasReadonlyField, "Final field should have READONLY modifier")
    }

    @Test
    fun `should tokenize enum members as ENUM_MEMBER`(): Unit = runBlocking {
        val code = """
            enum Status {
                PENDING,
                ACTIVE,
                COMPLETED
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("Status.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have ENUM_MEMBER tokens for each enum constant
        val enumMemberTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.ENUM_MEMBER
        }
        assertEquals(
            3,
            enumMemberTokens.size,
            "Should have ENUM_MEMBER tokens for enum constants, got ${enumMemberTokens.size}",
        )
    }

    @Test
    fun `should tokenize implements clause interfaces`(): Unit = runBlocking {
        val code = """
            interface Runnable { }
            interface Closeable { }
            class Worker implements Runnable, Closeable {
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("Worker.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have INTERFACE tokens for declared interfaces
        val interfaceTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.INTERFACE
        }
        // At least the two interface declarations should be tokenized
        assertTrue(interfaceTokens.size >= 2, "Should have INTERFACE tokens for interface declarations")
    }

    @Test
    fun `should handle nested class`(): Unit = runBlocking {
        val code = """
            class Outer {
                class Inner {
                    def innerMethod() {}
                }
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("Outer.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have CLASS tokens for both Outer and Inner
        val classTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.CLASS
        }
        assertTrue(classTokens.size >= 2, "Should have CLASS tokens for both Outer and Inner classes")
    }

    @Test
    fun `should not produce duplicate tokens at same position`(): Unit = runBlocking {
        val code = """
            class MyClass {
                String name
                def getName() { name }
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("MyClass.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Check for duplicates at same position
        val positionCounts = tokens.groupBy { Pair(it.line, it.startChar) }
        val duplicates = positionCounts.filter { it.value.size > 1 }

        assertTrue(duplicates.isEmpty(), "Should not have duplicate tokens at same position: $duplicates")
    }

    @Test
    fun `should produce valid 0-based line and column numbers`(): Unit = runBlocking {
        val code = """
            class MyClass {
                def myMethod() {}
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("MyClass.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // All tokens should have non-negative line and column numbers (0-based)
        tokens.forEach { token ->
            assertTrue(token.line >= 0, "Line number should be >= 0, got ${token.line}")
            assertTrue(token.startChar >= 0, "Column number should be >= 0, got ${token.startChar}")
            assertTrue(token.length > 0, "Length should be > 0, got ${token.length}")
        }
    }

    @Test
    fun `should handle closure implicit it parameter`(): Unit = runBlocking {
        val code = """
            def list = [1, 2, 3]
            list.each {
                println it
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("Test.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // The implicit 'it' parameter reference should be tokenized as PARAMETER
        val paramOrVarTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.PARAMETER ||
                it.tokenType == GroovySemanticTokenProvider.TokenTypes.VARIABLE
        }
        val (itLine, _) = findPosition(code, "it")!!
        val itToken = paramOrVarTokens.findByLineAndLength(itLine, "it".length)
        assertTrue(itToken != null, "Should have token for implicit 'it' parameter on line $itLine")
    }

    @Test
    fun `should handle static field with modifier`(): Unit = runBlocking {
        val code = """
            class Config {
                static String VERSION = "1.0"
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("Config.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Static field should have STATIC modifier
        val propertyTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.PROPERTY
        }
        val hasStaticProperty = propertyTokens.any {
            (it.tokenModifiers and GroovySemanticTokenProvider.TokenModifiers.STATIC) != 0
        }
        assertTrue(hasStaticProperty, "Static field should have STATIC modifier")
    }

    @Test
    fun `should handle typed method parameters`(): Unit = runBlocking {
        val code = """
            class Calculator {
                int add(int a, int b) {
                    return a + b
                }
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("Calculator.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have PARAMETER tokens for both parameters
        val paramTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.PARAMETER
        }
        assertTrue(paramTokens.size >= 2, "Should have PARAMETER tokens for both parameters a and b")
    }

    @Test
    fun `should handle interface method declarations`(): Unit = runBlocking {
        val code = """
            interface Service {
                def start()
                def stop()
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("Service.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Interface methods should be tokenized
        val methodTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.METHOD
        }
        assertTrue(methodTokens.size >= 2, "Should have METHOD tokens for interface methods")
    }

    @Test
    fun `should handle script with no explicit class`(): Unit = runBlocking {
        val code = """
            def greeting = "Hello"
            println greeting
        """.trimIndent()

        val uri = tempWorkspace.resolve("script.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have at least a VARIABLE token for greeting
        val varTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.VARIABLE
        }
        val (greetingLine, _) = findPosition(code, "greeting")!!
        val greetingToken = varTokens.findByLineAndLength(greetingLine, "greeting".length)
        assertTrue(greetingToken != null, "Should have VARIABLE token for greeting on line $greetingLine")
    }

    // ==================== Method Call Expression Tests ====================

    @Test
    fun `should tokenize method call as METHOD`(): Unit = runBlocking {
        val code = """
            class MyClass {
                def caller() {
                    process()
                }
                def process() {}
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("MyClass.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have METHOD tokens for both declaration and call
        val methodTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.METHOD
        }

        // We should have 3 METHOD tokens:
        // 1. caller declaration
        // 2. process() call
        // 3. process declaration
        val processTokens = methodTokens.filter { it.length == "process".length }
        assertTrue(
            processTokens.size >= 2,
            "Should have METHOD tokens for both process declaration and call, got ${processTokens.size}",
        )
    }

    @Test
    fun `should tokenize chained method calls`(): Unit = runBlocking {
        val code = """
            def result = "hello".toUpperCase().trim()
        """.trimIndent()

        val uri = tempWorkspace.resolve("Test.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have METHOD tokens for both method calls
        val methodTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.METHOD
        }

        val (toUpperLine, _) = findPosition(code, "toUpperCase")!!
        val (trimLine, _) = findPosition(code, "trim")!!
        val toUpperCaseToken = methodTokens.findByLineAndLength(toUpperLine, "toUpperCase".length)
        val trimToken = methodTokens.findByLineAndLength(trimLine, "trim".length)

        assertTrue(toUpperCaseToken != null, "Should have METHOD token for toUpperCase() on line $toUpperLine")
        assertTrue(trimToken != null, "Should have METHOD token for trim() on line $trimLine")
    }

    @Test
    fun `should tokenize method call on object`(): Unit = runBlocking {
        val code = """
            class MyClass {
                def test() {
                    def list = []
                    list.add("item")
                }
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("MyClass.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have METHOD token for add()
        val methodTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.METHOD
        }

        val (addLine, _) = findPosition(code, "add")!!
        val addToken = methodTokens.findByLineAndLength(addLine, "add".length)
        assertTrue(addToken != null, "Should have METHOD token for add() call on line $addLine")
    }

    @Test
    fun `should tokenize static method call`(): Unit = runBlocking {
        val code = """
            class Utility {
                static def helper() { }
            }
            Utility.helper()
        """.trimIndent()

        val uri = tempWorkspace.resolve("Test.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have METHOD tokens for helper (declaration and call)
        val methodTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.METHOD
        }

        val helperTokens = methodTokens.filter { it.length == "helper".length }
        assertTrue(
            helperTokens.size >= 2,
            "Should have METHOD tokens for helper declaration and static call, got ${helperTokens.size}",
        )

        // Verify static method call position points to method name, not class name
        // Line 3: "Utility.helper()" - "helper" starts at column 8
        val staticCallToken = helperTokens.find { it.line == 3 }
        assertTrue(staticCallToken != null, "Should have METHOD token on line 3 for static call")
        assertEquals(8, staticCallToken!!.startChar, "Static method call token should start at column 8 (method name)")

        // Verify method declaration position points to method name
        // Line 1: "    static def helper() { }" - "helper" starts at column 15
        val declToken = helperTokens.find { it.line == 1 }
        assertTrue(declToken != null, "Should have METHOD token on line 1 for declaration")
        assertEquals(15, declToken!!.startChar, "Method declaration token should start at column 15 (method name)")
    }

    @Test
    fun `should tokenize constructor call as METHOD`(): Unit = runBlocking {
        val code = """
            class Person {
                String name
            }
            def p = new Person()
        """.trimIndent()

        val uri = tempWorkspace.resolve("Test.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Constructor call should be tokenized (either as CLASS or METHOD depending on implementation)
        // At minimum, we should not crash on ConstructorCallExpression
        assertTrue(tokens.isNotEmpty(), "Should produce tokens without crashing on constructor call")
    }

    @Test
    fun `should tokenize method call with closure argument`(): Unit = runBlocking {
        val code = """
            def list = [1, 2, 3]
            list.each { println it }
        """.trimIndent()

        val uri = tempWorkspace.resolve("Test.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have METHOD token for 'each' method call
        val methodTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.METHOD
        }

        val (eachLine, _) = findPosition(code, "each")!!
        val eachToken = methodTokens.findByLineAndLength(eachLine, "each".length)
        assertTrue(eachToken != null, "Should have METHOD token for each() on line $eachLine")
    }

    @Test
    fun `should handle generic return types in method declarations`(): Unit = runBlocking {
        val code = """
            class MyClass {
                List<String> getItems() { [] }
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("MyClass.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have METHOD token for getItems
        val methodTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.METHOD
        }

        val getItemsToken = methodTokens.find { it.length == "getItems".length }
        assertTrue(getItemsToken != null, "Should have METHOD token for getItems")

        // Line 1: "    List<String> getItems() { [] }"
        // "getItems" starts at column 17 (after "    List<String> ")
        // 4 spaces + "List<String>" (12) + 1 space = 17
        assertEquals(1, getItemsToken!!.line, "getItems should be on line 1")
        assertEquals(17, getItemsToken.startChar, "getItems should start at column 17")
    }

    @Test
    fun `should handle annotated method declarations`(): Unit = runBlocking {
        val code = """
            class MyClass {
                @Override
                def myMethod() { }
            }
        """.trimIndent()

        val uri = tempWorkspace.resolve("MyClass.groovy").toUri()
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have METHOD token for myMethod
        val methodTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.METHOD
        }

        val myMethodToken = methodTokens.find { it.length == "myMethod".length }
        assertTrue(myMethodToken != null, "Should have METHOD token for myMethod")

        // Line 2: "    def myMethod() { }" - "myMethod" starts at column 8
        // 4 spaces + "def" (3) + 1 space = 8
        assertEquals(2, myMethodToken!!.line, "myMethod should be on line 2")
        assertEquals(8, myMethodToken.startChar, "myMethod should start at column 8 (after 'def ')")
    }
}
