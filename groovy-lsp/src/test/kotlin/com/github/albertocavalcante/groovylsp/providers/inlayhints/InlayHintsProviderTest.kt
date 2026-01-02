package com.github.albertocavalcante.groovylsp.providers.inlayhints

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.InlayHintsConfiguration
import com.github.albertocavalcante.groovylsp.services.ClasspathService
import com.github.albertocavalcante.groovylsp.services.ReflectedMethod
import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.ast.TypeInferencer
import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import com.github.albertocavalcante.groovyparser.ast.symbols.buildFromVisitor
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.syntax.Token
import org.codehaus.groovy.syntax.Types
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive tests for InlayHintsProvider.
 *
 * Tests are organized by hint type and edge cases to ensure
 * full coverage of the inlay hints functionality.
 *
 * TODO(#567): Add End-to-End tests for Inlay Hints.
 *   See: https://github.com/albertocavalcante/gvy/issues/567
 */
class InlayHintsProviderTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var provider: InlayHintsProvider

    private val testUri = URI.create("file:///test/Test.groovy")
    private val testDocId = TextDocumentIdentifier(testUri.toString())

    @BeforeEach
    fun setup() {
        compilationService = mockk(relaxed = true)
    }

    @Nested
    @DisplayName("Type Hints")
    inner class TypeHintsTests {

        @Test
        fun `should show type hint for def variable with string literal`() {
            // Given: def name = "hello"
            val varExpr = VariableExpression("name").apply {
                lineNumber = 1
                columnNumber = 5
                type = ClassHelper.dynamicType()
            }
            val constExpr = ConstantExpression("hello").apply {
                type = ClassHelper.STRING_TYPE
            }
            val declExpr = DeclarationExpression(
                varExpr,
                Token.newSymbol(Types.ASSIGN, 1, 10),
                constExpr,
            ).apply {
                lineNumber = 1
                columnNumber = 1
            }

            setupCompilationWithNodes(listOf(declExpr))
            provider = InlayHintsProvider(compilationService, InlayHintsConfiguration(typeHints = true))

            val params = createParams(0, 0, 10, 100)

            // When
            val hints = provider.provideInlayHints(params)

            // Then - expect type hint for `name`
            val typeHints = hints.filter { it.kind == InlayHintKind.Type }
            assertTrue(typeHints.isNotEmpty(), "Should have at least one type hint")

            // Verify the hint label contains the type
            val label = typeHints.first().label.left as String
            assertTrue(label.contains("String"), "Hint should show inferred String type, got: $label")
        }

        @Test
        fun `should not show type hint when typeHints is disabled`() {
            // Given: def name = "hello" with type hints disabled
            val code = """
                def name = "hello"
            """.trimIndent()

            setupCompilationWithCode(code)
            provider = InlayHintsProvider(compilationService, InlayHintsConfiguration(typeHints = false))

            val params = createParams(0, 0, 10, 100)

            // When
            val hints = provider.provideInlayHints(params)

            // Then
            val typeHints = hints.filter { it.kind == InlayHintKind.Type }
            assertTrue(typeHints.isEmpty(), "Should have no type hints when disabled")
        }

        @Test
        fun `should not show type hint for explicitly typed variable`() {
            // Given: String name = "hello" (explicit type, not def)
            val code = """
                String name = "hello"
            """.trimIndent()

            setupCompilationWithCode(code)
            provider = InlayHintsProvider(compilationService, InlayHintsConfiguration(typeHints = true))

            val params = createParams(0, 0, 10, 100)

            // When
            val hints = provider.provideInlayHints(params)

            // Then - no type hint needed (type already visible)
            val typeHints = hints.filter { it.kind == InlayHintKind.Type }
            assertTrue(typeHints.isEmpty(), "Should not show type hint for explicitly typed variable")
        }

        @Test
        fun `should not show type hint when type resolves to Object`() {
            // Given: def x = someUnknownMethod() where type can't be inferred
            val code = """
                def x = unknownMethod()
            """.trimIndent()

            setupCompilationWithCode(code)
            provider = InlayHintsProvider(compilationService, InlayHintsConfiguration(typeHints = true))

            val params = createParams(0, 0, 10, 100)

            // When
            val hints = provider.provideInlayHints(params)

            // Then - no hint for Object (not useful)
            val typeHints = hints.filter { it.kind == InlayHintKind.Type }
            val objectHints = typeHints.filter {
                (it.label.left as? String)?.contains("Object") == true
            }
            assertTrue(objectHints.isEmpty(), "Should not show type hint for Object")
        }

        @Test
        fun `should format generic types correctly`() {
            // Given: def items = [1, 2, 3]
            val code = """
                def items = [1, 2, 3]
            """.trimIndent()

            setupCompilationWithCode(code)
            provider = InlayHintsProvider(compilationService, InlayHintsConfiguration(typeHints = true))

            val params = createParams(0, 0, 10, 100)

            // When
            val hints = provider.provideInlayHints(params)

            // Then - should show ArrayList<Integer>, not java.util.ArrayList<java.lang.Integer>
            val typeHints = hints.filter { it.kind == InlayHintKind.Type }
            assertTrue(typeHints.isNotEmpty(), "Expected at least one type hint")
            typeHints.forEach { hint ->
                val label = hint.label.left as? String
                assertNotNull(label)
                // Should not contain "java.util" or "java.lang" - should be simplified
                assertTrue(
                    !label.contains("java.util") && !label.contains("java.lang"),
                    "Type should be simplified, got: $label",
                )
            }
        }
    }

    @Nested
    @DisplayName("Parameter Hints")
    inner class ParameterHintsTests {

        @Test
        fun `should show parameter hint for method call with positional arguments`() {
            // Given: processPositional("input.txt", true) with method processPositional(String path, boolean verbose)
            val classNode = ClassNode("Test", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
            classNode.addMethod(
                "processPositional",
                Modifier.PUBLIC,
                ClassHelper.VOID_TYPE,
                arrayOf(Parameter(ClassHelper.STRING_TYPE, "path"), Parameter(ClassHelper.boolean_TYPE, "verbose")),
                emptyArray(),
                null,
            )

            val callExpr = MethodCallExpression(
                VariableExpression("this"),
                "processPositional",
                ArgumentListExpression(
                    ConstantExpression("input.txt").apply {
                        lineNumber = 5
                        columnNumber = 10
                    },
                    ConstantExpression(true).apply {
                        lineNumber = 5
                        columnNumber = 25
                    },
                ),
            ).apply {
                lineNumber = 5
                columnNumber = 5
            }

            setupCompilationWithNodes(listOf(callExpr), listOf(classNode))
            provider = InlayHintsProvider(compilationService, InlayHintsConfiguration(parameterHints = true))

            val params = createParams(0, 0, 10, 100)

            // When
            val hints = provider.provideInlayHints(params)

            // Then - expect parameter hints
            val paramHints = hints.filter { it.kind == InlayHintKind.Parameter }
            assertEquals(2, paramHints.size, "Should have 2 parameter hints")
            assertEquals("path:", paramHints[0].label.left as String)
            assertEquals("verbose:", paramHints[1].label.left as String)
        }

        @Test
        fun `should not show parameter hint when disabled`() {
            val code = """
                class Test {
                    void process(String path) {}
                    void run() {
                        process("input.txt")
                    }
                }
            """.trimIndent()

            setupCompilationWithCode(code)
            provider = InlayHintsProvider(compilationService, InlayHintsConfiguration(parameterHints = false))

            val params = createParams(0, 0, 10, 100)

            // When
            val hints = provider.provideInlayHints(params)

            // Then
            val paramHints = hints.filter { it.kind == InlayHintKind.Parameter }
            assertTrue(paramHints.isEmpty(), "Should have no parameter hints when disabled")
        }

        @Test
        fun `should not show parameter hint when names match`() {
            // Given: processMatch(path) where method is processMatch(String path)
            val classNode = ClassNode("Test", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
            classNode.addMethod(
                "processMatch",
                Modifier.PUBLIC,
                ClassHelper.VOID_TYPE,
                arrayOf(Parameter(ClassHelper.STRING_TYPE, "path")),
                emptyArray(),
                null,
            )

            val callExpr = MethodCallExpression(
                VariableExpression("this"),
                "processMatch",
                ArgumentListExpression(
                    VariableExpression("path").apply {
                        lineNumber = 5
                        columnNumber = 10
                    },
                ),
            ).apply {
                lineNumber = 5
                columnNumber = 5
            }

            setupCompilationWithNodes(listOf(callExpr), listOf(classNode))
            provider = InlayHintsProvider(compilationService, InlayHintsConfiguration(parameterHints = true))

            val params = createParams(0, 0, 10, 100)

            // When
            val hints = provider.provideInlayHints(params)

            // Then - no hint when names match (redundant)
            val paramHints = hints.filter { it.kind == InlayHintKind.Parameter }
            assertTrue(paramHints.isEmpty(), "Should not show parameter hint when names match")
        }

        @Test
        fun `should not show parameter hint for closure arguments`() {
            // Given: process({ ... }) where method accepts a closure
            val code = """
                class Test {
                    void process(Closure action) {}
                    void run() {
                        process {
                            println "hi"
                        }
                    }
                }
            """.trimIndent()

            setupCompilationWithCode(code)
            provider = InlayHintsProvider(compilationService, InlayHintsConfiguration(parameterHints = true))

            val params = createParams(0, 0, 10, 100)

            // When
            val hints = provider.provideInlayHints(params)

            // Then - closures should not have parameter hints (they provide their own context)
            // This is a documented behavior expectation
            val paramHints = hints.filter { it.kind == InlayHintKind.Parameter }
            assertTrue(paramHints.isEmpty(), "Should not show parameter hints for closure arguments")
        }

        @Test
        fun `should resolve parameter hints from workspace symbols`() {
            val fooUri = URI.create("file:///test/Foo.groovy")
            val fooCode = """
                class Foo {
                    void greet(String name, int times) {}
                }
            """.trimIndent()
            val callCode = """
                new Foo().greet("hi", 2)
            """.trimIndent()

            val parser = GroovyParserFacade()
            val fooResult = parser.parse(ParseRequest(fooUri, fooCode))
            val callResult = parser.parse(ParseRequest(testUri, callCode))
            val workspaceIndex = SymbolIndex().buildFromVisitor(fooResult.astModel)

            every { compilationService.getAstModel(testUri) } returns callResult.astModel
            every { compilationService.getAllSymbolStorages() } returns mapOf(fooUri to workspaceIndex)

            provider = InlayHintsProvider(compilationService, InlayHintsConfiguration(parameterHints = true))

            val params = createParams(0, 0, 10, 100)
            val hints = provider.provideInlayHints(params)

            val paramHints = hints.filter { it.kind == InlayHintKind.Parameter }
            assertEquals(2, paramHints.size, "Should have 2 parameter hints from workspace symbols")
            assertEquals("name:", paramHints[0].label.left as String)
            assertEquals("times:", paramHints[1].label.left as String)
        }

        @Test
        fun `should not use unrelated same-file classes for method hints`() {
            val fooUri = URI.create("file:///test/Target.groovy")
            val fooCode = """
                class Target {
                    void greet(String name) {}
                }
            """.trimIndent()
            val callCode = """
                class Other {
                    void greet(int count) {}
                }
                new Target().greet("hi")
            """.trimIndent()

            val parser = GroovyParserFacade()
            val fooResult = parser.parse(ParseRequest(fooUri, fooCode))
            val callResult = parser.parse(ParseRequest(testUri, callCode))
            val workspaceIndex = SymbolIndex().buildFromVisitor(fooResult.astModel)

            every { compilationService.getAstModel(testUri) } returns callResult.astModel
            every { compilationService.getAllSymbolStorages() } returns mapOf(fooUri to workspaceIndex)

            provider = InlayHintsProvider(compilationService, InlayHintsConfiguration(parameterHints = true))

            val params = createParams(0, 0, 10, 100)
            val hints = provider.provideInlayHints(params)

            val paramHints = hints.filter { it.kind == InlayHintKind.Parameter }
            assertEquals(1, paramHints.size, "Should have 1 parameter hint from workspace symbols")
            assertEquals("name:", paramHints[0].label.left as String)
        }

        @Test
        fun `should not use unrelated same-file classes for constructor hints`() {
            val fooUri = URI.create("file:///test/Target.groovy")
            val fooCode = """
                class Target {
                    Target(String name) {}
                }
            """.trimIndent()
            val callCode = """
                class Other {
                    Other(int count) {}
                }
                new Target("hi")
            """.trimIndent()

            val parser = GroovyParserFacade()
            val fooResult = parser.parse(ParseRequest(fooUri, fooCode))
            val callResult = parser.parse(ParseRequest(testUri, callCode))
            val workspaceIndex = SymbolIndex().buildFromVisitor(fooResult.astModel)

            every { compilationService.getAstModel(testUri) } returns callResult.astModel
            every { compilationService.getAllSymbolStorages() } returns mapOf(fooUri to workspaceIndex)

            provider = InlayHintsProvider(compilationService, InlayHintsConfiguration(parameterHints = true))

            val params = createParams(0, 0, 10, 100)
            val hints = provider.provideInlayHints(params)

            val paramHints = hints.filter { it.kind == InlayHintKind.Parameter }
            assertEquals(1, paramHints.size, "Should have 1 constructor hint from workspace symbols")
            assertEquals("name:", paramHints[0].label.left as String)
        }

        @Test
        fun `should allow primitive arguments for supertype parameters`() {
            val code = """
                class Foo {
                    void work(Number value) {}
                    void run() {
                        work(1)
                    }
                }
            """.trimIndent()

            setupCompilationWithCode(code)
            provider = InlayHintsProvider(compilationService, InlayHintsConfiguration(parameterHints = true))

            val params = createParams(0, 0, 10, 100)
            val hints = provider.provideInlayHints(params)

            val paramHints = hints.filter { it.kind == InlayHintKind.Parameter }
            assertEquals(1, paramHints.size, "Should have 1 parameter hint for Number parameter")
            assertEquals("value:", paramHints[0].label.left as String)
        }

        @Test
        fun `should resolve classpath constructors with qualified parameter types`() {
            val code = """
                new java.util.ArrayList(new java.util.ArrayList())
            """.trimIndent()
            val parser = GroovyParserFacade()
            val callResult = parser.parse(ParseRequest(testUri, code))
            val classpathService = mockk<ClasspathService>()

            every { compilationService.getAstModel(testUri) } returns callResult.astModel
            every { compilationService.getAllSymbolStorages() } returns emptyMap()
            every { compilationService.classpathService } returns classpathService
            every { classpathService.loadClass("java.util.ArrayList") } returns java.util.ArrayList::class.java
            every { classpathService.loadClass("java.util.Collection") } returns java.util.Collection::class.java
            every { classpathService.loadClass("java.lang.Integer") } returns java.lang.Integer::class.java

            provider = InlayHintsProvider(compilationService, InlayHintsConfiguration(parameterHints = true))

            val params = createParams(0, 0, 10, 100)
            val hints = provider.provideInlayHints(params)

            val paramHints = hints.filter { it.kind == InlayHintKind.Parameter }
            assertEquals(1, paramHints.size, "Should have 1 constructor hint from classpath")
        }

        @Test
        fun `should select overload by argument types when multiple matches exist`() {
            val fooUri = URI.create("file:///test/Foo.groovy")
            val fooCode = """
                class Foo {
                    void work(int count, String label) {}
                    void work(String label, int count) {}
                }
            """.trimIndent()
            val callCode = """
                new Foo().work(1, "alpha")
            """.trimIndent()

            val parser = GroovyParserFacade()
            val fooResult = parser.parse(ParseRequest(fooUri, fooCode))
            val callResult = parser.parse(ParseRequest(testUri, callCode))
            val workspaceIndex = SymbolIndex().buildFromVisitor(fooResult.astModel)

            every { compilationService.getAstModel(testUri) } returns callResult.astModel
            every { compilationService.getAllSymbolStorages() } returns mapOf(fooUri to workspaceIndex)

            provider = InlayHintsProvider(compilationService, InlayHintsConfiguration(parameterHints = true))

            val params = createParams(0, 0, 10, 100)
            val hints = provider.provideInlayHints(params)

            val paramHints = hints.filter { it.kind == InlayHintKind.Parameter }
            assertEquals(2, paramHints.size, "Should have 2 parameter hints after overload selection")
            assertEquals("count:", paramHints[0].label.left as String)
            assertEquals("label:", paramHints[1].label.left as String)
        }

        @Test
        fun `should resolve parameter hints from classpath methods`() {
            val code = """
                new ArrayList().add("value")
            """.trimIndent()
            val parser = GroovyParserFacade()
            val callResult = parser.parse(ParseRequest(testUri, code))
            val classpathService = mockk<ClasspathService>()

            every { compilationService.getAstModel(testUri) } returns callResult.astModel
            every { compilationService.getAllSymbolStorages() } returns emptyMap()
            every { compilationService.classpathService } returns classpathService
            every { classpathService.getMethods("java.util.ArrayList") } returns listOf(
                ReflectedMethod(
                    name = "add",
                    returnType = "boolean",
                    parameters = listOf("java.lang.Object"),
                    parameterNames = listOf("element"),
                    isStatic = false,
                    isPublic = true,
                    doc = "classpath",
                ),
            )

            provider = InlayHintsProvider(compilationService, InlayHintsConfiguration(parameterHints = true))

            val params = createParams(0, 0, 10, 100)
            val hints = provider.provideInlayHints(params)

            val paramHints = hints.filter { it.kind == InlayHintKind.Parameter }
            assertEquals(1, paramHints.size, "Should have 1 parameter hint from classpath methods")
            assertEquals("element:", paramHints[0].label.left as String)
        }
    }

    @Nested
    @DisplayName("Range Filtering")
    inner class RangeFilteringTests {

        @Test
        fun `should only return hints within requested range`() {
            val code = """
                def a = 1
                def b = 2
                def c = 3
                def d = 4
                def e = 5
            """.trimIndent()

            setupCompilationWithCode(code)
            provider = InlayHintsProvider(compilationService, InlayHintsConfiguration(typeHints = true))

            // Request only lines 1-2 (0-indexed)
            val params = createParams(1, 0, 2, 100)

            // When
            val hints = provider.provideInlayHints(params)

            // Then - should only include hints from lines 1-2
            assertTrue(hints.isNotEmpty(), "Expected hints within the requested range")
            hints.forEach { hint ->
                assertTrue(
                    hint.position.line in 1..2,
                    "Hint at line ${hint.position.line} is outside requested range",
                )
            }
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `should handle empty file gracefully`() {
            setupCompilationWithCode("")
            provider = InlayHintsProvider(compilationService)

            val params = createParams(0, 0, 10, 100)

            // When
            val hints = provider.provideInlayHints(params)

            // Then
            assertTrue(hints.isEmpty())
        }

        @Test
        fun `should handle file with syntax errors gracefully`() {
            val code = """
                def x = 
                // incomplete declaration
            """.trimIndent()

            setupCompilationWithCode(code)
            provider = InlayHintsProvider(compilationService)

            val params = createParams(0, 0, 10, 100)

            // When
            val hints = provider.provideInlayHints(params)

            // Then - should not crash, may return empty or partial hints
            assertNotNull(hints)
        }

        @Test
        fun `should ignore type hints when inference fails`() {
            val code = """
                def name = "hello"
            """.trimIndent()

            mockkObject(TypeInferencer)
            try {
                every { TypeInferencer.inferType(any()) } throws RuntimeException("boom")
                setupCompilationWithCode(code)
                provider = InlayHintsProvider(compilationService, InlayHintsConfiguration(typeHints = true))

                val params = createParams(0, 0, 10, 100)

                val hints = provider.provideInlayHints(params)

                val typeHints = hints.filter { it.kind == InlayHintKind.Type }
                assertTrue(typeHints.isEmpty(), "Should not emit type hints when inference fails")
            } finally {
                unmockkObject(TypeInferencer)
            }
        }

        @Test
        fun `should return empty list when AST model is not available`() {
            every { compilationService.getAstModel(any()) } returns null
            provider = InlayHintsProvider(compilationService)

            val params = createParams(0, 0, 10, 100)

            // When
            val hints = provider.provideInlayHints(params)

            // Then
            assertTrue(hints.isEmpty())
        }
    }

    // Helper methods

    private fun createParams(startLine: Int, startChar: Int, endLine: Int, endChar: Int): InlayHintParams =
        InlayHintParams(
            testDocId,
            Range(Position(startLine, startChar), Position(endLine, endChar)),
        )

    private fun setupCompilationWithCode(code: String) {
        val parser = GroovyParserFacade()
        val parseResult = parser.parse(ParseRequest(testUri, code))

        every { compilationService.getAstModel(testUri) } returns parseResult.astModel
    }

    private fun setupCompilationWithNodes(
        nodes: List<org.codehaus.groovy.ast.ASTNode>,
        classNodes: List<ClassNode> = emptyList(),
    ) {
        val astModel = mockk<com.github.albertocavalcante.groovyparser.ast.GroovyAstModel>(relaxed = true)

        every { compilationService.getAstModel(testUri) } returns astModel
        every { astModel.getAllNodes() } returns nodes
        every { astModel.getAllClassNodes() } returns classNodes
    }
}
