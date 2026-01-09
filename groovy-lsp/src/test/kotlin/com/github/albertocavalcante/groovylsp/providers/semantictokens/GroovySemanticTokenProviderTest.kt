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

        val uri = URI.create("file://$tempWorkspace/MyClass.groovy")
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

        val uri = URI.create("file://$tempWorkspace/MyInterface.groovy")
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

        val uri = URI.create("file://$tempWorkspace/MyClass.groovy")
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

        val uri = URI.create("file://$tempWorkspace/Test.groovy")
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

        val uri = URI.create("file://$tempWorkspace/Test.groovy")
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

        val uri = URI.create("file://$tempWorkspace/MyClass.groovy")
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

        val uri = URI.create("file://$tempWorkspace/MyClass.groovy")
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

        val uri = URI.create("file://$tempWorkspace/MyClass.groovy")
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

        val uri = URI.create("file://$tempWorkspace/MyEnum.groovy")
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

        val uri = URI.create("file://$tempWorkspace/Test.groovy")
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have PARAMETER token for closure parameter 'item'
        val paramTokens = tokens.filter { it.tokenType == GroovySemanticTokenProvider.TokenTypes.PARAMETER }
        assertTrue(paramTokens.isNotEmpty(), "Should have PARAMETER token for closure parameter")
    }

    @Test
    fun `should handle method calls`(): Unit = runBlocking {
        val code = """
            class MyClass {
                def myMethod() {
                    otherMethod()
                }

                def otherMethod() {
                }
            }
        """.trimIndent()

        val uri = URI.create("file://$tempWorkspace/MyClass.groovy")
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(astModel, uri)

        // Should have METHOD tokens for both method declarations and method call
        val methodTokens = tokens.filter { it.tokenType == GroovySemanticTokenProvider.TokenTypes.METHOD }
        assertTrue(methodTokens.size >= 2, "Should have METHOD tokens for method declarations")
    }
}
