package com.github.albertocavalcante.gvy.gls.types

import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.semantics.SemanticType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.control.SourceUnit
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.writeText

/**
 * Part of #1525: a method call on a class declared in another workspace file is typed from the workspace index,
 * and only when that type is certain (a wrong type is worse than an unknown one).
 */
class CrossFileMethodReturnTypeTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var root: Path
    private lateinit var resolver: SemanticTypeResolver

    @BeforeEach
    fun setUp() {
        root = createTempDirectory("cross-file-method-types")
        // Source roots are discovered when the workspace is initialised, so the layout must exist first.
        (root / SOURCE_ROOT).createDirectories()
        compilationService = GroovyCompilationService()
        compilationService.workspaceManager.initializeWorkspace(root)
        resolver = SemanticTypeResolver(ReflectionTypeSolver(), compilationService.getWorkspaceSymbolIndex())
    }

    @AfterEach
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun tearDown() {
        compilationService.clearCaches()
        root.deleteRecursively()
    }

    @Test
    fun `method call on class from another file has the declared return type`() = runBlocking<Unit> {
        compile(
            "demo/money/Money.groovy",
            """
            package demo.money
            class Money {
                BigDecimal amount
                String currency() { 'BRL' }
                Money plus(Money other) { this }
            }
            """.trimIndent(),
        )
        val order = compile(
            "demo/order/Order.groovy",
            """
            package demo.order
            import demo.money.Money
            class Order {
                void run(Money money) {
                    def c = money.currency()
                    def p = money.plus(money)
                }
            }
            """.trimIndent(),
        )

        val calls = collectCalls(order)

        assertThat(resolver.resolveType(calls.getValue("currency"), order))
            .isEqualTo(SemanticType.Known("java.lang.String"))
        assertThat(resolver.resolveType(calls.getValue("plus"), order))
            .isEqualTo(SemanticType.Known("demo.money.Money"))
    }

    @Test
    fun `overloads that disagree on the return type give no type`() = runBlocking<Unit> {
        compile(
            "demo/lib/Lib.groovy",
            """
            package demo.lib
            class Lib {
                String fmt(int n) { '' }
                Integer fmt(String s) { 1 }
                String same(int n) { '' }
                String same(String s) { '' }
                Integer dflt(String a) { 1 }
                String dflt(String a, int b, boolean c = true) { '' }
            }
            """.trimIndent(),
        )
        val app = compile(
            "demo/app/App.groovy",
            """
            package demo.app
            import demo.lib.Lib
            class App {
                void run(Lib lib) {
                    lib.fmt('x')
                    lib.same('x')
                    lib.dflt('x', 1)
                }
            }
            """.trimIndent(),
        )

        val calls = collectCalls(app)

        assertThat(resolver.resolveType(calls.getValue("fmt"), app)).isInstanceOf(SemanticType.Unknown::class.java)
        assertThat(resolver.resolveType(calls.getValue("same"), app))
            .isEqualTo(SemanticType.Known("java.lang.String"))
        // The index holds dflt(String) and dflt(String,int,boolean); a two-argument call matches neither by arity.
        assertThat(resolver.resolveType(calls.getValue("dflt"), app)).isInstanceOf(SemanticType.Unknown::class.java)
    }

    @Test
    fun `overloads in the same file that disagree on the return type give no type`() = runBlocking<Unit> {
        val app = compile(
            "demo/app/Loc.groovy",
            """
            package demo.app
            class Loc {
                String fmt(int n) { '' }
                Integer fmt(String s) { 1 }
                String same(int n) { '' }
                String same(String s) { '' }
                String one(String s) { '' }
                void run(Loc loc, List<String> xs) {
                    loc.fmt('x')
                    loc.same('x')
                    loc.one(*xs)
                }
            }
            """.trimIndent(),
        )

        val calls = collectCalls(app)

        assertThat(resolver.resolveType(calls.getValue("fmt"), app)).isInstanceOf(SemanticType.Unknown::class.java)
        assertThat(resolver.resolveType(calls.getValue("same"), app))
            .isEqualTo(SemanticType.Known("java.lang.String"))
        // A spread argument has no fixed arity, so the call is not matched by arity.
        assertThat(resolver.resolveType(calls.getValue("one"), app)).isInstanceOf(SemanticType.Unknown::class.java)
    }

    @Test
    fun `a method on a type variable is not typed from its bound`() = runBlocking<Unit> {
        compile("demo/lib/NBox.groovy", "package demo.lib\nclass NBox<T extends Number> { T get() { null } }")
        val app = compile(
            "demo/app/App.groovy",
            "package demo.app\nimport demo.lib.NBox\nclass App { void run(NBox<Integer> box) { box.get() } }",
        )

        assertThat(resolver.resolveType(collectCalls(app).getValue("get"), app))
            .isNotEqualTo(SemanticType.Known("java.lang.Number"))
    }

    @Test
    fun `editing the dependency updates a local typed from it`() = runBlocking<Unit> {
        val lib = "package demo.lib\nclass Lib { %s currency() { null } }"
        compile("demo/lib/Lib.groovy", lib.format("String"))
        val app = compile(
            "demo/app/App.groovy",
            """
            package demo.app
            import demo.lib.Lib
            class App {
                void run(Lib lib) {
                    def cur = lib.currency()
                    cur.toString()
                }
            }
            """.trimIndent(),
        )
        val calls = collectCalls(app)
        val local = calls.getValue("toString").objectExpression
        val call = calls.getValue("currency")
        assertThat(resolver.resolveType(local, app)).isEqualTo(SemanticType.Known("java.lang.String"))

        // Only the dependency is recompiled; the same App module and the same resolver are used again.
        compile("demo/lib/Lib.groovy", lib.format("Integer"))

        assertThat(resolver.resolveType(call, app)).isEqualTo(SemanticType.Known("java.lang.Integer"))
        assertThat(resolver.resolveType(local, app)).isEqualTo(SemanticType.Known("java.lang.Integer"))
    }

    @Test
    fun `an inlay hint on a def initialised from another file shows the declared type`() = runBlocking<Unit> {
        compile(
            "demo/lib/Lib.groovy",
            """
            package demo.lib
            class Lib {
                static class Inner { String name() { '' } }
                String currency() { 'BRL' }
                Inner mk() { new Inner() }
            }
            """.trimIndent(),
        )
        val source = """
            package demo.app
            import demo.lib.Lib
            class App {
                void run(Lib lib) {
                    def cur = lib.currency()
                    def inner = lib.mk()
                }
            }
        """.trimIndent()
        val appUri = write("demo/app/App.groovy", source)
        val documents = DocumentProvider().also { it.put(appUri, source, 1) }
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val service = GroovyTextDocumentService(
                scope,
                compilationService,
                GroovyTextDocumentServiceOptions(documentProvider = documents),
            )
            val hints = service.inlayHint(
                InlayHintParams(
                    TextDocumentIdentifier(appUri.toString()),
                    Range(Position(0, 0), Position(source.lines().size, 0)),
                ),
            ).await().filter { it.kind == InlayHintKind.Type }

            val labels = hints.associate { it.position.line to it.label.left.trim().removePrefix(":").trim() }
            assertThat(labels).containsEntry(4, "String").containsEntry(5, "Lib.Inner")
        } finally {
            scope.cancel()
        }
    }

    private suspend fun compile(relativePath: String, content: String): ModuleNode {
        val uri = write(relativePath, content)
        val result = compilationService.compile(uri, content)
        assertThat(result.isSuccess).describedAs("$relativePath compiles: ${result.diagnostics}").isTrue()
        return checkNotNull(compilationService.getAst(uri) as? ModuleNode)
    }

    private fun write(relativePath: String, content: String): URI {
        val file = root / SOURCE_ROOT / relativePath
        file.parent.createDirectories()
        file.writeText(content)
        return file.toUri()
    }

    private fun collectCalls(module: ModuleNode): Map<String, MethodCallExpression> {
        val found = mutableMapOf<String, MethodCallExpression>()
        val visitor = object : ClassCodeVisitorSupport() {
            override fun getSourceUnit(): SourceUnit? = null
            override fun visitMethodCallExpression(call: MethodCallExpression) {
                found[call.methodAsString.orEmpty()] = call
                super.visitMethodCallExpression(call)
            }
        }
        module.classes.forEach { visitor.visitClass(it) }
        return found
    }

    private companion object {
        const val SOURCE_ROOT = "src/main/groovy"
    }
}
