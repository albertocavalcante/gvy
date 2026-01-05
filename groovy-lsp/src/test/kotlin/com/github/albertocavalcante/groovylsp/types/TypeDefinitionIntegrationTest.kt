package com.github.albertocavalcante.groovylsp.types

import com.github.albertocavalcante.groovylsp.compilation.CompilationContext
import com.github.albertocavalcante.groovylsp.converters.toGroovyPosition
import com.github.albertocavalcante.groovyparser.ast.NodeRelationshipTracker
import com.github.albertocavalcante.groovyparser.ast.visitor.RecursiveAstVisitor
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import com.github.albertocavalcante.gvy.semantics.SemanticType
import groovy.lang.GroovyClassLoader
import kotlinx.coroutines.test.runTest
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.io.StringReaderSource
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for Type Definition using pragmatic inline test strings.
 * Follows patterns from rust-analyzer and kotlin-language-server.
 */
class TypeDefinitionIntegrationTest {

    private val logger = LoggerFactory.getLogger(TypeDefinitionIntegrationTest::class.java)
    private lateinit var typeResolver: SemanticTypeResolver

    @BeforeEach
    fun setUp() {
        typeResolver = SemanticTypeResolver(ReflectionTypeSolver())
    }

    @Test
    fun `test variable type definition`() = runTest {
        val code = """
            class Person {
                String name
            }
            def person = new Person()
            person.name = "test"
                  //^ cursor here
        """.trimIndent()

        // Diagnostic version with enhanced debugging
        val (cleanCode, position) = extractCursorPosition(code, "//^")
        val context = compileGroovy(cleanCode)
        val node = context.astModel.getNodeAt(context.uri, position.toGroovyPosition())

        assertNotNull(node, "Should find AST node at position $position")

        val type = typeResolver.resolveType(node, context.moduleNode)
        logger.debug("Resolved type: {}", type)

        // For property access like "person.name", we expect to resolve to String type
        assertNotNull(type, "Should resolve to a type")
        assertTrue(type is SemanticType.Known, "Should be Known type")
        assertEquals("java.lang.String", (type as SemanticType.Known).fqn)
    }

    @Test
    fun `test primitive types return correct type`() = runTest {
        val code = """
            int count = 42
            count + 1
           //^ cursor here
        """.trimIndent()

        val (cleanCode, position) = extractCursorPosition(code, "//^")
        val context = compileGroovy(cleanCode)
        val node = context.astModel.getNodeAt(context.uri, position.toGroovyPosition())

        assertNotNull(node, "Should find AST node at position $position")

        typeResolver.semantics.inject(context.moduleNode)
        val type = typeResolver.resolveType(node, context.moduleNode)
        logger.debug("Resolved type: {}", type)

        assertTrue(type is SemanticType.Primitive || (type is SemanticType.Known && type.fqn == "java.lang.Integer"))
    }

    @Test
    @Disabled("TODO(#615): FieldNode type resolution not yet implemented in SemanticTypeResolver")
    fun `test field type definition`() = runTest {
        val code = """
            class Person {
                String name
                      //^ cursor here
                int age
            }
        """.trimIndent()

        val (cleanCode, position) = extractCursorPosition(code, "//^")
        val context = compileGroovy(cleanCode)
        val node = context.astModel.getNodeAt(context.uri, position.toGroovyPosition())

        assertNotNull(node, "Should find AST node at position $position")

        val type = typeResolver.resolveType(node, context.moduleNode)
        logger.debug("Resolved type: {}", type)

        assertTrue(type is SemanticType.Known)
        assertEquals("java.lang.String", (type as SemanticType.Known).fqn)
    }

    @Test
    @Disabled("TODO(#615): Return type resolution not yet implemented in SemanticTypeResolver")
    fun `test method return type`() = runTest {
        val code = """
            class Calculator {
                String getName() {
                      //^ cursor here
                    return "calc"
                }
            }
        """.trimIndent()

        assertTypeDefinition(
            code = code,
            cursorMarker = "//^",
            expectedType = "java.lang.String",
        )
    }

    @Test
    @Disabled("TODO(#615): Parameter type resolution not yet implemented in SemanticTypeResolver")
    fun `test parameter type`() = runTest {
        val code = """
            class Service {
                void process(String input) {
                            //^ cursor here
                    logger.debug input
                }
            }
        """.trimIndent()

        assertTypeDefinition(
            code = code,
            cursorMarker = "//^",
            expectedType = "java.lang.String",
        )
    }

    @Test
    fun `test def keyword inference`() = runTest {
        val code = """
            def message = "Hello"
               //^ cursor here
        """.trimIndent()

        assertTypeDefinition(
            code = code,
            cursorMarker = "//^",
            expectedType = "java.lang.String",
        )
    }

    @Test
    fun `test collection literal inference`() = runTest {
        val code = """
            def numbers = [1, 2, 3]
               //^ cursor here
        """.trimIndent()

        val (cleanCode, position) = extractCursorPosition(code, "//^")
        val context = compileGroovy(cleanCode)
        val node = context.astModel.getNodeAt(context.uri, position.toGroovyPosition())

        if (node != null) {
            val type = typeResolver.resolveType(node, context.moduleNode)
            assertNotNull(type, "Should resolve collection type")
            assertTrue(type is SemanticType.Known, "Should be Known type")
            val known = type as SemanticType.Known
            assertTrue(
                known.fqn.contains("List") || known.fqn.contains("ArrayList"),
                "Should resolve to List type, got: ${known.fqn}",
            )
        }
    }

    // Test helper methods

    /**
     * Main assertion helper for type definition tests.
     */
    private suspend fun assertTypeDefinition(
        code: String,
        cursorMarker: String = "//^",
        expectedType: String? = null,
        expectedLocation: String? = null,
    ) {
        val (cleanCode, position) = extractCursorPosition(code, cursorMarker)
        val context = compileGroovy(cleanCode)
        val node = context.astModel.getNodeAt(context.uri, position.toGroovyPosition())

        assertNotNull(node, "Should find AST node at position $position")

        context.moduleNode.let { typeResolver.semantics.inject(it) }
        val type = typeResolver.resolveType(node, context.moduleNode)
        expectedType?.let {
            assertNotNull(type, "Should resolve to a type")
            assertTrue(type is SemanticType.Known, "Should be Known type for $it")
            assertEquals(it, (type as SemanticType.Known).fqn, "Type name mismatch")
        }
    }

    /**
     * Extract cursor position from test code with marker.
     * The marker format is: "//^ cursor here" where ^ points to the column above.
     */
    private fun extractCursorPosition(code: String, marker: String): Pair<String, Position> {
        val lines = code.lines()
        var markerLine = -1
        var caretColumn = -1

        for (lineIndex in lines.indices) {
            val line = lines[lineIndex]
            val markerIndex = line.indexOf(marker)
            if (markerIndex != -1) {
                markerLine = lineIndex
                // Find the position of "^" within the marker comment
                val caretIndex = line.indexOf("^", markerIndex)
                require(caretIndex != -1) { "Caret character '^' not found in marker comment" }
                caretColumn = caretIndex
                break
            }
        }

        require(markerLine != -1) { "Cursor marker '$marker' not found in code" }

        // The caret points to the line ABOVE the marker line
        val targetLine = markerLine - 1
        require(targetLine >= 0) { "Cursor marker cannot be on the first line (no line above to point to)" }

        // Remove the marker line completely
        val cleanLines = lines.toMutableList()
        cleanLines.removeAt(markerLine)

        val cleanCode = cleanLines.joinToString("\n")
        val position = Position(targetLine, caretColumn)

        return cleanCode to position
    }

    /**
     * Compile Groovy code and return CompilationContext.
     */
    private fun compileGroovy(code: String): CompilationContext {
        val config = CompilerConfiguration()
        val classLoader = GroovyClassLoader()
        val compilationUnit = CompilationUnit(config, null, classLoader)

        val source = StringReaderSource(code, config)
        val sourceUnit = SourceUnit("test.groovy", source, config, classLoader, compilationUnit.errorCollector)
        compilationUnit.addSource(sourceUnit)

        val tracker = NodeRelationshipTracker()
        val astModel = RecursiveAstVisitor(tracker)
        val uri = URI.create("file:///test.groovy")

        try {
            // Compile to get AST
            compilationUnit.compile(Phases.CANONICALIZATION)

            // Get the module and visit with our AST visitor
            val module = sourceUnit.ast
            astModel.visitModule(module, uri)

            return CompilationContext(
                uri = uri,
                moduleNode = module,
                astModel = astModel,
                workspaceRoot = null,
            )
        } catch (e: Exception) {
            // Log the compilation error but continue with partial AST
            logger.warn("Compilation error, proceeding with partial AST: {}", e.message, e)
            // Even with compilation errors, we might have partial AST
            val module = sourceUnit.ast ?: ModuleNode(sourceUnit)
            astModel.visitModule(module, uri)

            return CompilationContext(
                uri = uri,
                moduleNode = module,
                astModel = astModel,
                workspaceRoot = null,
            )
        }
    }
}
