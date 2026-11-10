package com.github.albertocavalcante.gvy.gls.providers.inlayhints

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.InlayHintsConfiguration
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/** Parameter hints resolved from workspace classes, for closed files and unsaved buffers (#1312). */
class WorkspaceParameterHintsTest {
    @TempDir
    lateinit var tempDir: Path

    private val compilation = GroovyCompilationService()

    @AfterEach
    fun close() = compilation.clearCaches()

    @Test
    fun `workspace overloads, constructors and default-package classes give the same hints`(): Unit = runBlocking {
        index(
            "Greeter.groovy",
            "package w\nclass Greeter {\n  Greeter(String name) {}\n" +
                "  String greet(String who, int times) { '' }\n  String greet(int count) { '' }\n}\n",
        )
        index("Plain.groovy", "class Plain {\n  Plain(int size) {}\n  void put(String key, Object value) {}\n}\n")
        val draft = tempDir.resolve("Draft.groovy").toUri() // unsaved: never written to disk
        compilation.compile(draft, "package u\nclass Draft {\n  Draft(boolean flag) {}\n}\n")

        assertEquals(listOf("name:", "who:", "times:"), hintsFor("new w.Greeter('a').greet('x', 2)"))
        assertEquals(listOf("name:", "count:"), hintsFor("new w.Greeter('a').greet(3)"), "overload by arity")
        assertEquals(listOf("size:", "key:", "value:"), hintsFor("new Plain(1).put('k', 2)"), "default package")
        assertEquals(listOf("flag:"), hintsFor("new u.Draft(true)"), "unsaved buffer")
    }

    private var targets = 0

    private suspend fun hintsFor(call: String, imports: String = ""): List<String> {
        val name = "T${targets++}"
        val target = tempDir.resolve("$name.groovy")
        val source = "${imports}class $name {\n  void run() {\n    $call\n  }\n}\n"
        target.writeText(source)
        compilation.compile(target.toUri(), source)
        return InlayHintsProvider(
            compilation,
            SemanticTypeResolver(compilation.classpathService.getTypeSolver()),
            InlayHintsConfiguration(typeHints = false, parameterHints = true),
        ).provideInlayHints(
            InlayHintParams(TextDocumentIdentifier(target.toUri().toString()), Range(Position(0, 0), Position(20, 0))),
        ).sortedBy { it.position.character }.map { it.label.left } // source order; traversal order varies
    }

    @Test
    fun `the exact owner class wins over a same-named class in another package`(): Unit = runBlocking {
        index("A.groovy", "package a\nclass Util {\n  Util(String first) {}\n  def send(String first) {}\n}\n")
        index("B.groovy", "package b\nclass Util {\n  Util(String other) {}\n  def send(String other) {}\n}\n")

        assertEquals(listOf("first:", "first:"), hintsFor("new a.Util('x').send('y')"))
        assertEquals(listOf("other:", "other:"), hintsFor("new b.Util('x').send('y')"))
        assertEquals(
            listOf("other:", "other:"),
            hintsFor("def u = new Util('x')\n    u.send('y')", imports = "import b.Util\n"),
            "an imported simple name keeps its package",
        )
    }

    @Test
    fun `a bare name prefers the default-package class only in default-package code`(): Unit = runBlocking {
        index("Plain.groovy", "class Util {\n  Util(int size) {}\n  def send(int size) {}\n}\n")
        index("C.groovy", "package c\nclass Util {\n  Util(int count) {}\n  def send(int count) {}\n}\n")

        assertEquals(listOf("size:", "size:"), hintsFor("new Util(1).send(2)"))
        // Packaged code cannot use a default-package class. The compiler qualifies `new Util` with the
        // file's package, and a variable receiver keeps its initializer's package, so both pick c.Util.
        assertEquals(listOf("count:", "count:"), hintsFor("new Util(1).send(2)", imports = "package c\n"))
        assertEquals(
            listOf("count:", "count:"),
            hintsFor("def u = new Util(1)\n    u.send(2)", imports = "package c\n"),
        )
    }

    @Test
    fun `a variable receiver keeps the package of its initializer`(): Unit = runBlocking {
        index("A.groovy", "package a\nclass Util {\n  Util(String first) {}\n  def send(String first) {}\n}\n")
        index("B.groovy", "package b\nclass Util {\n  Util(String other) {}\n  def send(String other) {}\n}\n")

        assertEquals(listOf("first:", "first:"), hintsFor("def u = new a.Util('x')\n    u.send('y')"))
        assertEquals(listOf("other:", "other:"), hintsFor("def u = new b.Util('x')\n    u.send('y')"))
    }

    @Test
    fun `without an exact owner the same-named classes are still considered`(): Unit = runBlocking {
        index("A.groovy", "package a\nclass Util {\n  Util(String first) {}\n}\n")

        // `c.Util` does not exist: the simple-name candidates stay, as before.
        assertEquals(listOf("first:"), hintsFor("new c.Util('x')"))
    }

    @Test
    fun `workspace symbols are scanned once per request, not once per call`(): Unit = runBlocking {
        index("C0.groovy", "package p0\nclass C0 {\n  def m0(int a, String b) { a }\n  def m1(int c) { c }\n}\n")
        val calls = (0 until 5).joinToString("\n") { "    new p0.C0().m0(1, 'x')\n    new p0.C0().m1(2)" }
        val target = tempDir.resolve("Many.groovy")
        val source = "class Many {\n  void run() {\n$calls\n  }\n}\n"
        target.writeText(source)
        val uri = target.toUri()
        compilation.compile(uri, source)
        val astModel = checkNotNull(compilation.getAstModel(uri))
        val symbols = CountingList(compilation.getAllSymbolStorages().values.flatMap { it.symbols.values.flatten() })
        val context = HintContext(
            astModel = astModel,
            moduleNode = compilation.getAst(uri) as? org.codehaus.groovy.ast.ModuleNode,
            symbolTable = null,
            workspaceSymbols = symbols,
            compilationService = compilation,
            semanticResolver = SemanticTypeResolver(compilation.classpathService.getTypeSolver()),
            config = InlayHintsConfiguration(typeHints = false, parameterHints = true),
            logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {},
        )
        val strategy = ParameterInlayHintStrategy()

        val hints = astModel.getAllNodes().filter { strategy.canHandle(it, context) }
            .flatMap { strategy.generateHints(it, context) }

        assertEquals(15, hints.size, "five m0 calls with two hints and five m1 calls with one")
        assertEquals(1, symbols.iterations, "the request's symbols must be grouped once")
    }

    /** Counts full passes over the workspace symbols. */
    private class CountingList<T>(private val inner: List<T>) : AbstractList<T>() {
        var iterations = 0
        override val size: Int get() = inner.size
        override fun get(index: Int): T = inner[index]
        override fun iterator(): Iterator<T> = inner.iterator().also { iterations++ }
    }

    private fun index(name: String, content: String) = runBlocking {
        compilation.symbolIndexingService.indexFile(tempDir.resolve(name).also { it.writeText(content) }.toUri())
    }
}
