package com.github.albertocavalcante.groovylsp.engine.impl.core.features

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedNode
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedNodeKind
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedSymbol
import com.github.albertocavalcante.groovylsp.engine.api.DefinitionKind
import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ParserConfiguration
import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.CombinedTypeSolver
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [CoreDefinitionService].
 */
class CoreDefinitionServiceTest {

    private lateinit var parser: GroovyParser
    private lateinit var typeSolver: TypeSolver

    @BeforeEach
    fun setup() {
        parser = GroovyParser(ParserConfiguration())
        typeSolver = CombinedTypeSolver(ReflectionTypeSolver())
    }

    @Test
    fun `findDefinition returns empty list when node is null`(): Unit = runBlocking {
        val service = CoreDefinitionService(typeSolver)
        val parseUnit = createParseUnit("class Foo {}", "file:///test.groovy")

        val result = service.findDefinition(
            node = null,
            context = parseUnit,
            position = Position(0, 6),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findDefinition returns empty list when node has no name`(): Unit = runBlocking {
        val service = CoreDefinitionService(typeSolver)
        val parseUnit = createParseUnit("class Foo {}", "file:///test.groovy")
        val nodeWithoutName = UnifiedNode(
            name = null,
            kind = UnifiedNodeKind.OTHER,
            type = null,
            documentation = null,
            range = null,
            originalNode = null,
        )

        val result = service.findDefinition(
            node = nodeWithoutName,
            context = parseUnit,
            position = Position(0, 6),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findDefinition returns empty list when originalNode is not a core AST Node`(): Unit = runBlocking {
        val service = CoreDefinitionService(typeSolver)
        val parseUnit = createParseUnit("class Foo {}", "file:///test.groovy")
        val nodeWithWrongType = UnifiedNode(
            name = "Foo",
            kind = UnifiedNodeKind.CLASS,
            type = "Foo",
            documentation = null,
            range = null,
            originalNode = "not a Node", // Wrong type
        )

        val result = service.findDefinition(
            node = nodeWithWrongType,
            context = parseUnit,
            position = Position(0, 6),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findDefinition finds method in same class`(): Unit = runBlocking {
        val code = """
            class Foo {
                void greet() { }
            }
        """.trimIndent()

        val service = CoreDefinitionService(typeSolver)
        val parseResult = parser.parse(code)
        val cu = parseResult.result.orElse(null) ?: error("Parse failed")

        // Get the method node directly from the parsed AST
        val classDecl = cu.types.first() as ClassDeclaration
        val methodDecl = classDecl.methods.first()

        val parseUnit = createParseUnit(code, "file:///test.groovy", cu)

        val methodNode = UnifiedNode(
            name = "greet",
            kind = UnifiedNodeKind.METHOD,
            type = "void",
            documentation = null,
            range = Range(Position(1, 4), Position(1, 20)),
            originalNode = methodDecl,
        )

        val result = service.findDefinition(
            node = methodNode,
            context = parseUnit,
            position = Position(1, 10),
        )

        assertEquals(1, result.size)
        assertEquals(DefinitionKind.SOURCE, result[0].kind)
        assertEquals("file:///test.groovy", result[0].uri)
    }

    @Test
    fun `findDefinition finds class type`(): Unit = runBlocking {
        val code = """
            class MyClass { }
        """.trimIndent()

        val service = CoreDefinitionService(typeSolver)
        val parseResult = parser.parse(code)
        val cu = parseResult.result.orElse(null) ?: error("Parse failed")

        val classDecl = cu.types.first() as ClassDeclaration
        val parseUnit = createParseUnit(code, "file:///test.groovy", cu)

        val classNode = UnifiedNode(
            name = "MyClass",
            kind = UnifiedNodeKind.CLASS,
            type = "MyClass",
            documentation = null,
            range = Range(Position(0, 0), Position(0, 17)),
            originalNode = classDecl,
        )

        val result = service.findDefinition(
            node = classNode,
            context = parseUnit,
            position = Position(0, 8),
        )

        assertEquals(1, result.size)
        assertEquals(DefinitionKind.SOURCE, result[0].kind)
    }

    // Helper to create a ParseUnit from code
    private fun createParseUnit(
        code: String,
        uri: String,
        compilationUnit: com.github.albertocavalcante.groovyparser.ast.CompilationUnit? = null,
    ): ParseUnit {
        val cu = compilationUnit ?: parser.parse(code).result.orElse(null)
        return object : ParseUnit {
            override val uri: String = uri
            override val isSuccessful: Boolean = cu != null
            override val diagnostics: List<Diagnostic> = emptyList()
            override fun nodeAt(position: Position): UnifiedNode? = null
            override fun allSymbols(): List<UnifiedSymbol> = emptyList()
        }
    }
}
