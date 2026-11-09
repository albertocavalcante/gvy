package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassNode
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Rename edits only the current file, so targets that other files use must be refused, not half-renamed. */
class CrossFileRenameGuardTest {
    @TempDir
    lateinit var tempDir: Path

    private val documents = DocumentProvider()
    private val service = GroovyCompilationService(documentProvider = documents)
    private val provider by lazy { RenameProvider(service, documents) }

    private fun write(name: String, content: String): URI {
        val file = tempDir.resolve("src").resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        return file.toUri()
    }

    /**
     * Writes the files, compiles the workspace, then opens and compiles [open] as the editor would, under the
     * URI spelling [editorSpelling] gives it (editors spell file URIs differently from `Path.toUri()`).
     */
    private fun workspace(files: Map<String, String>, open: String, editorSpelling: (URI) -> URI = { it }): URI =
        runBlocking {
            val uris = files.mapValues { (name, text) -> write(name, text) }
            service.workspaceManager.initializeWorkspace(tempDir)
            service.getWorkspaceCompiler().compileWorkspace()
            val uri = editorSpelling(checkNotNull(uris[open]))
            documents.put(uri, files.getValue(open))
            service.compile(uri, files.getValue(open))
            uri
        }

    private fun positionOf(text: String, needle: String, occurrence: Int = 0): Position {
        var index = -1
        repeat(occurrence + 1) { index = text.indexOf(needle, index + 1) }
        val before = text.substring(0, index)
        return Position(before.count { it == '\n' }, index - (before.lastIndexOf('\n') + 1))
    }

    private fun refusal(block: suspend () -> Unit): String {
        val error = runCatching { runBlocking { block() } }.exceptionOrNull()
        val response = assertNotNull(error as? ResponseErrorException, "expected a refusal, got $error").responseError
        assertEquals(ResponseErrorCode.InvalidRequest.value, response.code)
        return response.message
    }

    private fun assertRefused(uri: URI, text: String, needle: String, vararg mentions: String) {
        val position = positionOf(text, needle)
        val renameMessage = refusal { provider.provideRename(uri.toString(), position, "renamed") }
        val prepareMessage = refusal { provider.providePrepareRename(uri.toString(), position) }
        assertEquals(renameMessage, prepareMessage, "prepareRename gives the same reason")
        mentions.forEach { assertTrue(it in renameMessage, "'$it' missing from: $renameMessage") }
    }

    @Test
    fun `a method called from another file is refused and the file is named`() {
        val a = "class A {\n    def greet() { 'hi' }\n}\n"
        val files = mapOf("A.groovy" to a, "B.groovy" to "class B {\n    def run() { new A().greet() }\n}\n")
        val uri = workspace(files, open = "A.groovy")

        assertRefused(uri, a, "greet", "'greet'", "B.groovy", "not supported yet")
    }

    @Test
    fun `a property read in another file is refused`() {
        val a = "class A {\n    String label = 'x'\n}\n"
        val files = mapOf("A.groovy" to a, "B.groovy" to "class B {\n    def run() { new A().label }\n}\n")
        val uri = workspace(files, open = "A.groovy")

        assertRefused(uri, a, "label", "B.groovy")
    }

    @Test
    fun `a field read in another file is refused`() {
        val a = "class A {\n    public final String code = 'x'\n}\n"
        val files = mapOf("A.groovy" to a, "B.groovy" to "class B {\n    def run() { new A().code }\n}\n")
        val uri = workspace(files, open = "A.groovy")

        assertRefused(uri, a, "code", "B.groovy")
    }

    @Test
    fun `a class used elsewhere only as a field type is refused by the text rule`() {
        val a = "class Foo {\n}\n"
        val files = mapOf("Foo.groovy" to a, "B.groovy" to "class B {\n    Foo f\n}\n")
        val uri = workspace(files, open = "Foo.groovy")

        assertRefused(uri, a, "Foo", "'Foo'", "B.groovy")
    }

    @Test
    fun `an open buffer the index has not analyzed yet is refused as unsaved`() {
        val a = "class A {\n    def greet() { 'hi' }\n}\n"
        val files = mapOf("A.groovy" to a, "B.groovy" to "class B {\n    def run() { 1 }\n}\n")
        val uri = workspace(files, open = "A.groovy")
        val b = uri.resolve("B.groovy")
        documents.put(b, "class B {\n    def run() { new A().greet() }\n}\n")

        assertRefused(uri, a, "greet", "B.groovy", "unsaved changes not analyzed yet")
    }

    @Test
    fun `a method used only in its own file still renames`(): Unit = runBlocking {
        val a = "class A {\n    def greet() { 'hi' }\n    def run() { greet() }\n}\n"
        val files = mapOf("A.groovy" to a, "B.groovy" to "class B {\n    def run() { 1 }\n}\n")
        val uri = workspace(files, open = "A.groovy")

        val edit = provider.provideRename(uri.toString(), positionOf(a, "greet"), "hello")

        assertEquals(2, edit.documentChanges.filter { it.isLeft }.sumOf { it.left.edits.size })
    }

    @Test
    fun `a parameter is not refused because another file has a method of that name`(): Unit = runBlocking {
        val a = "class A {\n    def go(int total) { total + 1 }\n}\n"
        val b = "class B {\n    def total() { 1 }\n    def run() { total() }\n}\n"
        val uri = workspace(mapOf("A.groovy" to a, "B.groovy" to b), open = "A.groovy")

        val edit = provider.provideRename(uri.toString(), positionOf(a, "total"), "sum")

        assertEquals(2, edit.documentChanges.filter { it.isLeft }.sumOf { it.left.edits.size })
    }

    @Test
    fun `a local is not refused because another file has a method of that name`(): Unit = runBlocking {
        val a = "class A {\n    def go() {\n        def total = 1\n        total + 1\n    }\n}\n"
        val b = "class B {\n    def total() { 1 }\n    def run() { total() }\n}\n"
        val uri = workspace(mapOf("A.groovy" to a, "B.groovy" to b), open = "A.groovy")

        val edit = provider.provideRename(uri.toString(), positionOf(a, "total"), "sum")

        assertEquals(2, edit.documentChanges.filter { it.isLeft }.sumOf { it.left.edits.size })
    }

    @Test
    fun `the renamed file under the editor's URI spelling is not another file`(): Unit = runBlocking {
        // VS Code percent-encodes '@' where Path.toUri() does not, so the index holds both spellings.
        val a = "class A {\n    def greet() { 'hi' }\n    def run() { greet() }\n}\n"
        val files = mapOf("@org/A.groovy" to a, "B.groovy" to "class B {\n    def run() { 1 }\n}\n")
        val uri = workspace(files, open = "@org/A.groovy") { URI.create(it.toString().replace("@", "%40")) }
        assertTrue("%40" in uri.toString())

        val edit = provider.provideRename(uri.toString(), positionOf(a, "greet"), "hello")

        assertEquals(2, edit.documentChanges.filter { it.isLeft }.sumOf { it.left.edits.size })
    }

    @Test
    fun `a class used only in its own file still renames under the text rule`(): Unit = runBlocking {
        val a = "class Foo {\n    Foo copy() { new Foo() }\n}\n"
        val files = mapOf("Foo.groovy" to a, "B.groovy" to "class B {\n    def run() { 1 }\n}\n")
        val uri = workspace(files, open = "Foo.groovy")

        val edit = provider.provideRename(uri.toString(), positionOf(a, "Foo"), "Bar")

        assertTrue(edit.documentChanges.isNotEmpty())
    }

    @Test
    fun `symbol id builders produce the ids the index stores`() {
        val a = "class A {\n    String label = 'x'\n    public int code = 1\n    def greet(String s, int n) { s }\n}\n"
        val uri = workspace(mapOf("A.groovy" to a), open = "A.groovy")
        val index = checkNotNull(service.getWorkspaceSymbolIndex())
        val stored = checkNotNull(index.getDocument(uri)).symbols.map { it.symbol }.toSet()
        val module = checkNotNull(runBlocking { service.getValidParseResult(uri) }?.ast)
        val owner: ClassNode = module.classes.single { it.nameWithoutPackage == "A" }

        val built = setOf(
            SemanticDocumentBuilder.createClassSymbolId(owner),
            SemanticDocumentBuilder.createPropertySymbolId(owner, owner.getProperty("label")),
            SemanticDocumentBuilder.createFieldSymbolId(owner, owner.getField("code")),
            SemanticDocumentBuilder.createMethodSymbolId(owner, owner.getMethods("greet").single()),
        )

        assertTrue(stored.containsAll(built), "built=$built, stored=$stored")
    }
}
