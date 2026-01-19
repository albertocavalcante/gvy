package com.github.albertocavalcante.gvy.gls.providers.inlayhints

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.InlayHintsConfiguration
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.nativeapi.ParseRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.syntax.Token
import org.codehaus.groovy.syntax.Types
import org.eclipse.lsp4j.InlayHintKind
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypeInlayHintStrategyTest {

    private lateinit var strategy: TypeInlayHintStrategy
    private lateinit var compilationService: GroovyCompilationService
    private lateinit var semanticResolver: SemanticTypeResolver
    private lateinit var context: HintContext

    private val testUri = URI.create("file:///test/Test.groovy")
    private val logger = KotlinLogging.logger {}

    @BeforeEach
    fun setup() {
        strategy = TypeInlayHintStrategy()
        compilationService = mockk(relaxed = true)
        semanticResolver = spyk(SemanticTypeResolver(ReflectionTypeSolver()))
    }

    @Test
    fun `should handle DeclarationExpression when typeHints is enabled`() {
        // Given: def name = "hello"
        val code = """
            def name = "hello"
        """.trimIndent()

        val parseResult = parseCode(code)
        context = createContext(InlayHintsConfiguration(typeHints = true), parseResult)

        val declExpr = parseResult.astModel.getAllNodes()
            .filterIsInstance<DeclarationExpression>()
            .first()

        // When / Then
        assertTrue(strategy.canHandle(declExpr, context))
    }

    @Test
    fun `should not handle DeclarationExpression when typeHints is disabled`() {
        // Given: def name = "hello" with type hints disabled
        val code = """
            def name = "hello"
        """.trimIndent()

        val parseResult = parseCode(code)
        context = createContext(InlayHintsConfiguration(typeHints = false), parseResult)

        val declExpr = parseResult.astModel.getAllNodes()
            .filterIsInstance<DeclarationExpression>()
            .first()

        // When / Then
        assertFalse(strategy.canHandle(declExpr, context))
    }

    @Test
    fun `should generate type hint for def variable with string literal`() {
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

        val parseResult = parseCode("def name = \"hello\"")
        context = createContext(InlayHintsConfiguration(typeHints = true), parseResult)

        // When
        val hints = strategy.generateHints(declExpr, context)

        // Then
        assertEquals(1, hints.size)
        assertEquals(InlayHintKind.Type, hints[0].kind)
        val label = hints[0].label.left as String
        assertTrue(label.contains("String"), "Expected type hint to contain 'String', got: $label")
    }

    @Test
    fun `should not generate type hint for explicitly typed variable`() {
        // Given: String name = "hello"
        val varExpr = VariableExpression("name").apply {
            lineNumber = 1
            columnNumber = 8
            type = ClassHelper.STRING_TYPE // Explicit type
        }
        val constExpr = ConstantExpression("hello")
        val declExpr = DeclarationExpression(
            varExpr,
            Token.newSymbol(Types.ASSIGN, 1, 15),
            constExpr,
        )

        val parseResult = parseCode("String name = \"hello\"")
        context = createContext(InlayHintsConfiguration(typeHints = true), parseResult)

        // When
        val hints = strategy.generateHints(declExpr, context)

        // Then
        assertTrue(hints.isEmpty(), "Should not show type hint for explicitly typed variable")
    }

    @Test
    fun `should not generate type hint when type resolves to Object`() {
        // Given: def x where type resolver returns Object
        val varExpr = VariableExpression("x").apply {
            lineNumber = 1
            columnNumber = 5
            type = ClassHelper.dynamicType()
        }
        val constExpr = ConstantExpression("test")
        val declExpr = DeclarationExpression(
            varExpr,
            Token.newSymbol(Types.ASSIGN, 1, 10),
            constExpr,
        )

        val parseResult = parseCode("def x = unknownMethod()")
        context = createContext(InlayHintsConfiguration(typeHints = true), parseResult)

        every { semanticResolver.resolveType(any(), any()) } returns SemanticType.Known("java.lang.Object")
        every { semanticResolver.formatSemanticType(any()) } returns "java.lang.Object"

        // When
        val hints = strategy.generateHints(declExpr, context)

        // Then
        assertTrue(hints.isEmpty(), "Should not show type hint for Object type")
    }

    @Test
    fun `should not generate type hint when type resolves to unresolved`() {
        // Given: def x where type resolver returns "unresolved"
        val varExpr = VariableExpression("x").apply {
            lineNumber = 1
            columnNumber = 5
            type = ClassHelper.dynamicType()
        }
        val constExpr = ConstantExpression("test")
        val declExpr = DeclarationExpression(
            varExpr,
            Token.newSymbol(Types.ASSIGN, 1, 10),
            constExpr,
        )

        val parseResult = parseCode("def x = unknownMethod()")
        context = createContext(InlayHintsConfiguration(typeHints = true), parseResult)

        every { semanticResolver.resolveType(any(), any()) } returns SemanticType.Unknown("unresolved")
        every { semanticResolver.formatSemanticType(any()) } returns "unresolved"

        // When
        val hints = strategy.generateHints(declExpr, context)

        // Then
        assertTrue(hints.isEmpty(), "Should not show type hint for unresolved type")
    }

    @Test
    fun `should not generate type hint when type resolution fails`() {
        // Given: def x where type resolver throws
        val varExpr = VariableExpression("x").apply {
            lineNumber = 1
            columnNumber = 5
            type = ClassHelper.dynamicType()
        }
        val constExpr = ConstantExpression("test")
        val declExpr = DeclarationExpression(
            varExpr,
            Token.newSymbol(Types.ASSIGN, 1, 10),
            constExpr,
        )

        val parseResult = parseCode("def x = \"test\"")
        context = createContext(InlayHintsConfiguration(typeHints = true), parseResult)

        every { semanticResolver.resolveType(any(), any()) } throws RuntimeException("boom")

        // When
        val hints = strategy.generateHints(declExpr, context)

        // Then
        assertTrue(hints.isEmpty(), "Should not crash or show hint when type resolution fails")
    }

    @Test
    fun `should format generic types as simple names`() {
        // Given: def items = [1, 2, 3]
        val code = """
            def items = [1, 2, 3]
        """.trimIndent()

        val parseResult = parseCode(code)
        context = createContext(InlayHintsConfiguration(typeHints = true), parseResult)

        val declExpr = parseResult.astModel.getAllNodes()
            .filterIsInstance<DeclarationExpression>()
            .first()

        // When
        val hints = strategy.generateHints(declExpr, context)

        // Then
        if (hints.isNotEmpty()) {
            val label = hints[0].label.left as String
            assertFalse(
                label.contains("java.util") || label.contains("java.lang"),
                "Type should be simplified, got: $label",
            )
        }
    }

    // Helper methods

    private fun parseCode(code: String): com.github.albertocavalcante.nativeapi.ParseResult {
        val parser = GroovyParserFacade()
        return parser.parse(ParseRequest(testUri, code))
    }

    private fun createContext(
        config: InlayHintsConfiguration,
        parseResult: com.github.albertocavalcante.nativeapi.ParseResult,
    ): HintContext {
        every { compilationService.getAstModel(testUri) } returns parseResult.astModel
        every { compilationService.getAst(testUri) } returns parseResult.ast

        return HintContext(
            astModel = parseResult.astModel,
            moduleNode = parseResult.ast as? org.codehaus.groovy.ast.ModuleNode,
            symbolTable = null,
            workspaceSymbols = emptyList(),
            compilationService = compilationService,
            semanticResolver = semanticResolver,
            config = config,
            logger = logger,
        )
    }
}
