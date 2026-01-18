package com.github.albertocavalcante.groovylsp.providers.inlayhints

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.InlayHintsConfiguration
import com.github.albertocavalcante.groovylsp.services.ClasspathService
import com.github.albertocavalcante.groovylsp.services.ReflectedMethod
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import com.github.albertocavalcante.groovyparser.ast.symbols.buildFromVisitor
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.nativeapi.ParseRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.control.SourceUnit
import org.eclipse.lsp4j.InlayHintKind
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParameterInlayHintStrategyTest {

    private lateinit var strategy: ParameterInlayHintStrategy
    private lateinit var compilationService: GroovyCompilationService
    private lateinit var semanticResolver: SemanticTypeResolver
    private lateinit var context: HintContext

    private val testUri = URI.create("file:///test/Test.groovy")
    private val logger = KotlinLogging.logger {}

    @BeforeEach
    fun setup() {
        strategy = ParameterInlayHintStrategy()
        compilationService = mockk(relaxed = true)
        semanticResolver = spyk(SemanticTypeResolver(ReflectionTypeSolver()))
    }

    @Test
    fun `should handle MethodCallExpression when parameterHints is enabled`() {
        // Given
        val classNode = ClassNode("Test", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        classNode.addMethod(
            "process",
            Modifier.PUBLIC,
            ClassHelper.VOID_TYPE,
            arrayOf(Parameter(ClassHelper.STRING_TYPE, "path")),
            emptyArray(),
            null,
        )

        val callExpr = createMethodCall("process", "input.txt")
        val astModel = createMockAstModel(listOf(callExpr), listOf(classNode))
        context = createContext(InlayHintsConfiguration(parameterHints = true), astModel)

        // When / Then
        assertTrue(strategy.canHandle(callExpr, context))
    }

    @Test
    fun `should not handle MethodCallExpression when parameterHints is disabled`() {
        // Given
        val callExpr = createMethodCall("process", "input.txt")
        val astModel = createMockAstModel(listOf(callExpr), emptyList())
        context = createContext(InlayHintsConfiguration(parameterHints = false), astModel)

        // When / Then
        assertFalse(strategy.canHandle(callExpr, context))
    }

    @Test
    fun `should handle ConstructorCallExpression when parameterHints is enabled`() {
        // Given: new ArrayList(10)
        val code = """
            new ArrayList(10)
        """.trimIndent()

        val parseResult = parseCode(code)
        context = createContext(InlayHintsConfiguration(parameterHints = true), parseResult)

        val ctorExpr = parseResult.astModel.getAllNodes()
            .filterIsInstance<ConstructorCallExpression>()
            .first()

        // When / Then
        assertTrue(strategy.canHandle(ctorExpr, context))
    }

    @Test
    fun `should generate parameter hints for method call`() {
        // Given: processPositional("input.txt", true)
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

        val astModel = createMockAstModel(listOf(callExpr), listOf(classNode))
        context = createContext(InlayHintsConfiguration(parameterHints = true), astModel)

        // When
        val hints = strategy.generateHints(callExpr, context)

        // Then
        assertEquals(2, hints.size)
        assertEquals(InlayHintKind.Parameter, hints[0].kind)
        assertEquals("path:", hints[0].label.left as String)
        assertEquals("verbose:", hints[1].label.left as String)
    }

    @Test
    fun `should not generate hints for closure arguments`() {
        // Given: process { println "hi" }
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

        val parseResult = parseCode(code)
        context = createContext(InlayHintsConfiguration(parameterHints = true), parseResult)

        val methodCalls = parseResult.astModel.getAllNodes()
            .filterIsInstance<MethodCallExpression>()
            .filter { it.methodAsString == "process" }

        // When
        val allHints = methodCalls.flatMap { strategy.generateHints(it, context) }

        // Then
        assertTrue(allHints.isEmpty(), "Should not show parameter hints for closure arguments")
    }

    @Test
    fun `should generate hints for constructor call`() {
        // Given: new ArrayList(10)
        val fooUri = URI.create("file:///test/Foo.groovy")
        val fooCode = """
            class Foo {
                Foo(int capacity) {}
            }
        """.trimIndent()
        val callCode = """
            new Foo(10)
        """.trimIndent()

        val parser = GroovyParserFacade()
        val fooResult = parser.parse(ParseRequest(fooUri, fooCode))
        val callResult = parser.parse(ParseRequest(testUri, callCode))
        val workspaceIndex = SymbolIndex().buildFromVisitor(fooResult.astModel)

        every { compilationService.getAstModel(testUri) } returns callResult.astModel
        every { compilationService.getAst(testUri) } returns callResult.ast
        every { compilationService.getAllSymbolStorages() } returns mapOf(fooUri to workspaceIndex)

        context = createContext(
            InlayHintsConfiguration(parameterHints = true),
            callResult,
            workspaceIndex = workspaceIndex,
        )

        val ctorExpr = callResult.astModel.getAllNodes()
            .filterIsInstance<ConstructorCallExpression>()
            .first()

        // When
        val hints = strategy.generateHints(ctorExpr, context)

        // Then
        assertEquals(1, hints.size)
        assertEquals("capacity:", hints[0].label.left as String)
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
        every { compilationService.getAst(testUri) } returns callResult.ast
        every { compilationService.getAllSymbolStorages() } returns mapOf(fooUri to workspaceIndex)

        mockResolverTypeForConstructor("Foo")

        context = createContext(
            InlayHintsConfiguration(parameterHints = true),
            callResult,
            workspaceIndex = workspaceIndex,
        )

        val methodCall = callResult.astModel.getAllNodes()
            .filterIsInstance<MethodCallExpression>()
            .first { it.methodAsString == "greet" }

        // When
        val hints = strategy.generateHints(methodCall, context)

        // Then
        assertEquals(2, hints.size)
        assertEquals("name:", hints[0].label.left as String)
        assertEquals("times:", hints[1].label.left as String)
    }

    @Test
    fun `should resolve parameter hints from classpath methods`() {
        val code = """
            new ArrayList().add("value")
        """.trimIndent()
        val parser = GroovyParserFacade()
        val callResult = parser.parse(ParseRequest(testUri, code))
        val classpathService = mockk<ClasspathService>(relaxed = true)

        every { compilationService.getAstModel(testUri) } returns callResult.astModel
        every { compilationService.getAst(testUri) } returns callResult.ast
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
                declaringClass = "java.util.ArrayList",
            ),
        )

        // Mock type resolution for the constructor call receiver
        every {
            semanticResolver.resolveType(match<Expression> { it is ConstructorCallExpression }, any<ModuleNode>())
        } returns SemanticType.Known("java.util.ArrayList")
        every { semanticResolver.formatSemanticType(SemanticType.Known("java.util.ArrayList")) } returns
            "java.util.ArrayList"

        context = createContext(InlayHintsConfiguration(parameterHints = true), callResult)

        val methodCall = callResult.astModel.getAllNodes()
            .filterIsInstance<MethodCallExpression>()
            .first { it.methodAsString == "add" }

        // When
        val hints = strategy.generateHints(methodCall, context)

        // Then
        assertEquals(1, hints.size)
        assertEquals("element:", hints[0].label.left as String)
    }

    @Test
    fun `should not crash when argument type resolution fails`() {
        // Given: a method call where type resolution throws for arguments
        val classNode = ClassNode("Test", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        classNode.addMethod(
            "process",
            Modifier.PUBLIC,
            ClassHelper.VOID_TYPE,
            arrayOf(Parameter(ClassHelper.STRING_TYPE, "path")),
            emptyArray(),
            null,
        )

        val callExpr = createMethodCall("process", "input.txt")
        val astModel = createMockAstModel(listOf(callExpr), listOf(classNode))
        context = createContext(InlayHintsConfiguration(parameterHints = true), astModel)

        // Mock type resolution to throw for arguments
        every { semanticResolver.resolveType(any(), any()) } throws RuntimeException("Type resolution failed")

        // When / Then - should not crash
        val hints = strategy.generateHints(callExpr, context)

        // May return partial hints or empty, but should not crash
        assertNotNull(hints, "Should not crash when argument type resolution fails")
    }

    // Helper methods

    private fun createMethodCall(methodName: String, argValue: String): MethodCallExpression = MethodCallExpression(
        VariableExpression("this"),
        methodName,
        ArgumentListExpression(
            ConstantExpression(argValue).apply {
                lineNumber = 5
                columnNumber = 10
            },
        ),
    ).apply {
        lineNumber = 5
        columnNumber = 5
    }

    private fun createMockAstModel(
        nodes: List<org.codehaus.groovy.ast.ASTNode>,
        classNodes: List<ClassNode>,
    ): GroovyAstModel {
        val astModel = mockk<GroovyAstModel>(relaxed = true)

        @Suppress("USELESS_CAST")
        val moduleNode = ModuleNode(null as SourceUnit?)
        classNodes.forEach { moduleNode.addClass(it) }

        every { compilationService.getAst(testUri) } returns moduleNode
        every { astModel.getAllNodes() } returns nodes
        every { astModel.getAllClassNodes() } returns classNodes

        return astModel
    }

    private fun parseCode(code: String): com.github.albertocavalcante.nativeapi.ParseResult {
        val parser = GroovyParserFacade()
        return parser.parse(ParseRequest(testUri, code))
    }

    private fun createContext(
        config: InlayHintsConfiguration,
        parseResult: com.github.albertocavalcante.nativeapi.ParseResult,
        workspaceIndex: SymbolIndex? = null,
    ): HintContext {
        every { compilationService.getAstModel(testUri) } returns parseResult.astModel
        every { compilationService.getAst(testUri) } returns parseResult.ast

        val workspaceSymbols = workspaceIndex?.symbols?.values?.flatten() ?: emptyList()

        return HintContext(
            astModel = parseResult.astModel,
            moduleNode = parseResult.ast as? ModuleNode,
            symbolTable = null,
            workspaceSymbols = workspaceSymbols,
            compilationService = compilationService,
            semanticResolver = semanticResolver,
            config = config,
            logger = logger,
        )
    }

    private fun createContext(config: InlayHintsConfiguration, astModel: GroovyAstModel): HintContext {
        val moduleNode = compilationService.getAst(testUri) as? ModuleNode

        return HintContext(
            astModel = astModel,
            moduleNode = moduleNode,
            symbolTable = null,
            workspaceSymbols = emptyList(),
            compilationService = compilationService,
            semanticResolver = semanticResolver,
            config = config,
            logger = logger,
        )
    }

    private fun mockResolverTypeForConstructor(typeName: String) {
        every {
            semanticResolver.resolveType(match { it is ConstructorCallExpression && it.type.name == typeName }, any())
        } returns SemanticType.Known(typeName)
    }
}
