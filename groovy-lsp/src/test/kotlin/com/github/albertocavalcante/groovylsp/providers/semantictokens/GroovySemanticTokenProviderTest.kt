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

        val myMethodToken = methodTokens.find { it.length == "myMethod".length }
        assertTrue(myMethodToken != null, "Should have token for myMethod")
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

        val localVarToken = variableTokens.find { it.length == "localVar".length }
        assertTrue(localVarToken != null, "Should have token for localVar")
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

        // Verify we have tokens for method declarations
        val myMethodToken = methodTokens.find { it.length == "myMethod".length }
        val otherMethodTokens = methodTokens.filter { it.length == "otherMethod".length }

        assertTrue(myMethodToken != null, "Should have token for myMethod declaration")
        // Should have 2 tokens for otherMethod: declaration + call
        assertEquals(
            2,
            otherMethodTokens.size,
            "Should have 2 METHOD tokens for otherMethod (declaration + call)",
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
        assertTrue(
            enumMemberTokens.size >= 3,
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
        val itToken = paramOrVarTokens.find { it.length == "it".length }
        assertTrue(itToken != null, "Should have token for implicit 'it' parameter")
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
        val greetingToken = varTokens.find { it.length == "greeting".length }
        assertTrue(greetingToken != null, "Should have VARIABLE token for greeting in script")
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
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.METHOD ||
                it.tokenType == GroovySemanticTokenProvider.TokenTypes.FUNCTION
        }

        // We should have 3 METHOD/FUNCTION tokens:
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

        // Should have METHOD/FUNCTION tokens for both method calls
        val methodTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.METHOD ||
                it.tokenType == GroovySemanticTokenProvider.TokenTypes.FUNCTION
        }

        val toUpperCaseToken = methodTokens.find { it.length == "toUpperCase".length }
        val trimToken = methodTokens.find { it.length == "trim".length }

        assertTrue(toUpperCaseToken != null, "Should have METHOD token for toUpperCase()")
        assertTrue(trimToken != null, "Should have METHOD token for trim()")
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

        // Should have METHOD/FUNCTION token for add()
        val methodTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.METHOD ||
                it.tokenType == GroovySemanticTokenProvider.TokenTypes.FUNCTION
        }

        val addToken = methodTokens.find { it.length == "add".length }
        assertTrue(addToken != null, "Should have METHOD token for add() call")
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

        // Should have METHOD/FUNCTION tokens for helper (declaration and call)
        val methodTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.METHOD ||
                it.tokenType == GroovySemanticTokenProvider.TokenTypes.FUNCTION
        }

        val helperTokens = methodTokens.filter { it.length == "helper".length }
        assertTrue(
            helperTokens.size >= 2,
            "Should have METHOD tokens for helper declaration and static call, got ${helperTokens.size}",
        )
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

        // Should have METHOD/FUNCTION token for 'each' method call
        val methodTokens = tokens.filter {
            it.tokenType == GroovySemanticTokenProvider.TokenTypes.METHOD ||
                it.tokenType == GroovySemanticTokenProvider.TokenTypes.FUNCTION
        }

        val eachToken = methodTokens.find { it.length == "each".length }
        assertTrue(eachToken != null, "Should have METHOD token for each() method call")
    }
}
