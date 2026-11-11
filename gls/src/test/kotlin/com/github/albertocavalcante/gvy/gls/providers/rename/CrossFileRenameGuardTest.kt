package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassNode
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Rename edits only the current file, so targets that other files use must be refused, not half-renamed. */
class CrossFileRenameGuardTest {
    @TempDir
    lateinit var tempDir: Path

    @TempDir
    lateinit var otherDir: Path

    private val documents = DocumentProvider()
    private val service = GroovyCompilationService(documentProvider = documents)
    private var extraRoots = emptyList<Path>()

    // A clock a minute ahead keeps freshly written directories out of the cache's "modified just now" distrust.
    private val walks = WorkspaceWalkCache(clock = { System.currentTimeMillis() + 60_000 })
    private val provider by lazy {
        RenameProvider(service, documents, extraRoots = { extraRoots }, walks = walks)
    }

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
    private fun workspace(
        files: Map<String, String>,
        open: String,
        indexWorkspace: Boolean = true,
        editorSpelling: (URI) -> URI = { it },
    ): URI = runBlocking {
        val uris = files.mapValues { (name, text) -> write(name, text) }
        service.workspaceManager.initializeWorkspace(tempDir)
        if (indexWorkspace) service.getWorkspaceCompiler().compileWorkspace()
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
        assertEquals(ResponseErrorCode.RequestFailed.value, response.code, response.message)
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

    private fun assertRenameRefused(files: Map<String, String>, open: String, needle: String, indexed: Boolean = true) {
        val uri = workspace(files, open, indexWorkspace = indexed)
        assertRefused(uri, files.getValue(open), needle, "'$needle'", "not supported yet")
    }

    private fun assertRenames(uri: URI, text: String, needle: String) = runBlocking {
        val edit = provider.provideRename(uri.toString(), positionOf(text, needle), "renamedX")
        assertTrue(edit.documentChanges.isNotEmpty())
    }

    private val a = "class A {\n    def greet() { 'hi' }\n}\n"
    private val label = "class A {\n    String label = 'x'\n}\n"

    @Test
    fun `a closed file the index never saw is refused (P9)`() = assertRenameRefused(
        mapOf("A.groovy" to a, "B.groovy" to "class B {\n    def run() { new A().greet() }\n}\n"),
        "A.groovy",
        "greet",
        indexed = false,
    )

    @Test
    fun `an untyped receiver in another file is refused (P2)`() = assertRenameRefused(
        mapOf("A.groovy" to a, "B.groovy" to "class B {\n    def run(x) { x.greet() }\n}\n"),
        "A.groovy",
        "greet",
    )

    @Test
    fun `a getter call in another file is refused (P3)`() = assertRenameRefused(
        mapOf("A.groovy" to label, "B.groovy" to "class B {\n    def run() { new A().getLabel() }\n}\n"),
        "A.groovy",
        "label",
    )

    @Test
    fun `a setter call in another file is refused (P4)`() = assertRenameRefused(
        mapOf("A.groovy" to label, "B.groovy" to "class B {\n    def run() { new A().setLabel('y') }\n}\n"),
        "A.groovy",
        "label",
    )

    @Test
    fun `a boolean is-accessor in another file is refused`() = assertRenameRefused(
        mapOf(
            "A.groovy" to "class A {\n    boolean flag = true\n}\n",
            "B.groovy" to "class B {\n    def run() { new A().isFlag() }\n}\n",
        ),
        "A.groovy",
        "flag",
    )

    @Test
    fun `a map constructor key in another file is refused (P5)`() = assertRenameRefused(
        mapOf("A.groovy" to label, "B.groovy" to "class B {\n    def run() { new A(label: 'y') }\n}\n"),
        "A.groovy",
        "label",
    )

    @Test
    fun `a static import in another file is refused (P8)`() = assertRenameRefused(
        mapOf(
            "A.groovy" to "class A {\n    static String helper() { 'h' }\n}\n",
            "B.groovy" to "import static A.helper\nclass B {\n    def run() { helper() }\n}\n",
        ),
        "A.groovy",
        "helper",
    )

    @Test
    fun `another file that does not compile but mentions the name is refused (P11)`() = assertRenameRefused(
        mapOf("A.groovy" to a, "B.groovy" to "class B {\n    def run() { new A().greet() }\n    def broken( {\n}\n"),
        "A.groovy",
        "greet",
    )

    @Test
    fun `a subclass override in another file is refused (P6)`() = assertRenameRefused(
        mapOf("A.groovy" to a, "B.groovy" to "class B extends A {\n    def greet() { 'b' }\n}\n"),
        "A.groovy",
        "greet",
    )

    @Test
    fun `an interface implementation in another file is refused (P7)`() = assertRenameRefused(
        mapOf(
            "I.groovy" to "interface I {\n    String greet()\n}\n",
            "C.groovy" to "class C implements I {\n    String greet() { 'c' }\n}\n",
        ),
        "I.groovy",
        "greet",
    )

    @Test
    fun `renaming an override whose base is in another file is refused (P10)`() = assertRenameRefused(
        mapOf(
            "B.groovy" to "class B extends A {\n    def greet() { 'b' }\n}\n",
            "A.groovy" to a,
            "C.groovy" to "class C {\n    def run(A a) { a.greet() }\n}\n",
        ),
        "B.groovy",
        "greet",
    )

    @Test
    fun `a private member is not exempt from the text rule`() = assertRenameRefused(
        mapOf(
            "A.groovy" to "class A {\n    private def total() { 1 }\n    def go() { total() }\n}\n",
            "B.groovy" to "class B {\n    def total() { 2 }\n}\n",
        ),
        "A.groovy",
        "total",
    )

    @Test
    fun `the word match is whole-word so a longer name does not refuse`(): Unit = runBlocking {
        val files = mapOf("A.groovy" to a, "B.groovy" to "class B {\n    def greeting() { 'greetings' }\n}\n")
        assertRenames(workspace(files, open = "A.groovy"), a, "greet")
    }

    @Test
    fun `untitled and jar buffers and a Jenkinsfile outside the workspace do not refuse`(): Unit = runBlocking {
        val files = mapOf("A.groovy" to a, "B.groovy" to "class B {\n    def run() { 1 }\n}\n")
        val uri = workspace(files, open = "A.groovy")
        val outside = Files.createDirectories(otherDir.resolve("outside-workspace"))
        documents.put(URI.create("untitled:Untitled-1"), "println 'greet'")
        documents.put(URI.create("jar:file:///x/lib.jar!/Y.groovy"), "class Y { def greet() {} }")
        documents.put(outside.resolve("Jenkinsfile").toUri(), "node { echo 'greet' }")

        assertRenames(uri, a, "greet")
    }

    @Test
    fun `a Jenkinsfile or gradle or java file in the workspace that mentions the name refuses`() {
        val files = mapOf("A.groovy" to a)
        val uri = workspace(files, open = "A.groovy")
        listOf(
            "Jenkinsfile" to "node { new A().greet() }",
            "build.gradle" to "println new A().greet()",
            "B.java" to "class B { Object f(A a) { return a.greet(); } }",
            "ci.Jenkinsfile" to "greet()",
        ).forEach { (name, text) ->
            val file = tempDir.resolve(name)
            Files.writeString(file, text)
            walks.invalidate() // files created outside the editor are seen once a watcher event arrives
            assertRefused(uri, a, "greet", name)
            Files.delete(file)
        }
    }

    @Test
    fun `a file created after startup is refused without a restart`() {
        val uri = workspace(mapOf("A.groovy" to a), open = "A.groovy")
        write("Late.groovy", "class Late {\n    def run() { new A().greet() }\n}\n")

        assertRefused(uri, a, "greet", "Late.groovy")
    }

    @Test
    fun `a flat repository with no source root is refused`() {
        Files.writeString(tempDir.resolve("A.groovy"), a)
        Files.writeString(tempDir.resolve("B.groovy"), "class B {\n    def run() { new A().greet() }\n}\n")
        runBlocking {
            service.workspaceManager.initializeWorkspace(tempDir)
            val uri = tempDir.resolve("A.groovy").toUri()
            documents.put(uri, a)
            service.compile(uri, a)
            assertRefused(uri, a, "greet", "B.groovy")
        }
    }

    @Test
    fun `a dollar name used in another file is refused`() = assertRenameRefused(
        mapOf(
            "A.groovy" to "class A {\n    def \$foo() { 1 }\n}\n",
            "B.groovy" to "class B {\n    def run(x) { x.\$foo() }\n}\n",
        ),
        "A.groovy",
        "\$foo",
    )

    @Test
    fun `a non-ASCII name used in another file is refused`() = assertRenameRefused(
        mapOf(
            "A.groovy" to "class A {\n    def café() { 1 }\n}\n",
            "B.groovy" to "class B {\n    def run(x) { x.café() }\n}\n",
        ),
        "A.groovy",
        "café",
    )

    @Test
    fun `a getter method used as a property in another file is refused`() = assertRenameRefused(
        mapOf(
            "A.groovy" to "class A {\n    def getLabel() { 'x' }\n}\n",
            "B.groovy" to "class B {\n    def run(A a) { a.label }\n}\n",
        ),
        "A.groovy",
        "getLabel",
    )

    @Test
    fun `a new unsaved buffer in the source root that calls the target is refused`() {
        val uri = workspace(mapOf("A.groovy" to a), open = "A.groovy")
        documents.put(tempDir.resolve("src/New.groovy").toUri(), "class New {\n    def run(x) { x.greet() }\n}\n")

        assertRefused(uri, a, "greet", "New.groovy")
    }

    @Test
    fun `a java caller and a Jenkins vars step are refused`() {
        val uri = workspace(mapOf("A.groovy" to a), open = "A.groovy")
        val java = tempDir.resolve("src/main/java/B.java")
        Files.createDirectories(java.parent)
        Files.writeString(java, "class B { Object run() { return new A().greet(); } }\n")
        walks.invalidate()
        assertRefused(uri, a, "greet", "B.java")
        Files.delete(java)

        val step = tempDir.resolve("vars/step.groovy")
        Files.createDirectories(step.parent)
        Files.writeString(step, "def call(x) { x.greet() }\n")
        walks.invalidate()
        assertRefused(uri, a, "greet", "step.groovy")
    }

    @Test
    fun `a getter whose property keeps an uppercase second letter is refused through either spelling`() {
        val getter = "class A {\n    def getURL() { 'u' }\n}\n"
        val uri = workspace(
            mapOf("A.groovy" to getter, "B.groovy" to "class B {\n    def run(x) { x.uRL }\n}\n"),
            open = "A.groovy",
        )

        assertRefused(uri, getter, "getURL", "B.groovy")
    }

    @Test
    fun `a property whose second letter is uppercase is refused through its Groovy-capitalized accessor`() =
        assertRenameRefused(
            mapOf(
                "A.groovy" to "class A {\n    String aB = 'x'\n}\n",
                "B.groovy" to "class B {\n    def run(A a) { a.getaB() }\n}\n",
            ),
            "A.groovy",
            "aB",
        )

    @Test
    fun `a longer non-ASCII identifier containing the name does not refuse`(): Unit = runBlocking {
        val u = "class A {\n    def café() { 1 }\n}\n"
        val files = mapOf("A.groovy" to u, "B.groovy" to "class B {\n    def cafés() { 'x' }\n}\n")
        assertRenames(workspace(files, open = "A.groovy"), u, "café")
    }

    @Test
    fun `a file that is not valid UTF-8 and does not mention the name does not block the rename`(): Unit = runBlocking {
        val uri = workspace(mapOf("A.groovy" to a), open = "A.groovy")
        Files.write(tempDir.resolve("src/L.groovy"), "// café\nclass L {}\n".toByteArray(Charsets.ISO_8859_1))

        assertRenames(uri, a, "greet")
    }

    @Test
    fun `many mentioning files stop the scan and say at least`() {
        val files = mapOf("A.groovy" to a) + ('B'..'F').associate {
            "$it.groovy" to "class $it {\n    def run(x) { x.greet() }\n}\n"
        }
        val uri = workspace(files, open = "A.groovy")
        val message = refusal { provider.providePrepareRename(uri.toString(), positionOf(a, "greet")) }

        assertTrue("at least 3 other files" in message, message)
    }

    @Test
    fun `a package named build inside the source root is not skipped`() {
        val uri = workspace(mapOf("A.groovy" to a), open = "A.groovy")
        val pkg = tempDir.resolve("src/main/groovy/com/acme/build/B.groovy")
        Files.createDirectories(pkg.parent)
        Files.writeString(pkg, "package com.acme.build\nclass B {\n    def run(x) { x.greet() }\n}\n")

        assertRefused(uri, a, "greet", "B.groovy")
    }

    @Test
    fun `build output at the workspace root or a module root is skipped`(): Unit = runBlocking {
        val uri = workspace(mapOf("A.groovy" to a), open = "A.groovy")
        listOf("build/tmp/Gen.groovy", "mod/out/tmp/Gen2.groovy").forEach {
            val file = tempDir.resolve(it)
            Files.createDirectories(file.parent)
            Files.writeString(file, "class Gen { def run(x) { x.greet() } }\n")
        }
        Files.writeString(tempDir.resolve("mod/build.gradle"), "// module marker\n")

        assertRenames(uri, a, "greet")
    }

    @Test
    fun `a second workspace folder is checked too`() {
        val uri = workspace(mapOf("A.groovy" to a), open = "A.groovy")
        val folder = Files.createDirectories(otherDir.resolve("second-folder"))
        Files.writeString(folder.resolve("B.groovy"), "class B {\n    def run(x) { x.greet() }\n}\n")
        extraRoots = listOf(folder)

        assertRefused(uri, a, "greet", "B.groovy")
    }

    @Test
    fun `the directory walk is reused until a directory changes, with no watcher event`() {
        Files.setLastModifiedTime(tempDir, FileTime.from(Instant.now().minusSeconds(3_600)))
        val uri = workspace(mapOf("A.groovy" to a), open = "A.groovy")
        Files.setLastModifiedTime(tempDir, FileTime.from(Instant.now().minusSeconds(3_600)))
        val position = positionOf(a, "greet")
        runBlocking {
            provider.providePrepareRename(uri.toString(), position)
            provider.providePrepareRename(uri.toString(), position)
        }
        assertEquals(1, walks.walks, "the second prepareRename does not re-walk")

        // Nothing tells the server about this file: no watcher covers a Jenkinsfile.
        Files.writeString(tempDir.resolve("Jenkinsfile"), "node { new A().greet() }\n")
        Files.setLastModifiedTime(tempDir, FileTime.from(Instant.now().minusSeconds(1_800))) // timestamps can tie
        assertRefused(uri, a, "greet", "Jenkinsfile")
        assertEquals(2, walks.walks, "the changed workspace root directory forced one new walk")
    }

    @Test
    fun `a GString shorthand reference is refused`() = assertRenameRefused(
        mapOf(
            "A.groovy" to a,
            "B.groovy" to "println \"\$greet\"\nprintln \"\${1}-\$greet.length\"\n",
        ),
        "A.groovy",
        "greet",
    )

    @Test
    fun `a package named build beside the build file is not skipped as build output`() {
        val uri = workspace(mapOf("A.groovy" to a), open = "A.groovy")
        Files.writeString(tempDir.resolve("build.gradle"), "// marker\n")
        val pkg = Files.createDirectories(tempDir.resolve("build"))
        Files.writeString(pkg.resolve("B.groovy"), "package build\nclass B { def run(x) { x.greet() } }\n")
        walks.invalidate()

        assertRefused(uri, a, "greet", "B.groovy")
    }

    @Test
    fun `a directory that cannot be read refuses instead of being skipped`() {
        val uri = workspace(mapOf("A.groovy" to a), open = "A.groovy")
        val locked = Files.createDirectories(tempDir.resolve("locked"))
        Files.writeString(locked.resolve("B.groovy"), "class B { def run(x) { x.greet() } }\n")
        withUnreadable(locked) {
            val message = refusal { provider.providePrepareRename(uri.toString(), positionOf(a, "greet")) }
            assertTrue("cannot be read" in message && "locked" in message, message)
        }
    }

    /** Runs [block] with [dirs] unreadable, or skips the test where permissions cannot make them so. */
    private fun withUnreadable(vararg dirs: Path, block: () -> Unit) {
        val locked = dirs.map { it to runCatching { Files.setPosixFilePermissions(it, emptySet()) } }
        try {
            val stayReadable = locked.filter { (dir, set) -> set.isFailure || Files.isReadable(dir) }
            Assumptions.assumeTrue(stayReadable.isEmpty(), "directories stay readable (Windows or root)")
            block()
        } finally {
            dirs.forEach {
                runCatching { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxr-xr-x")) }
            }
        }
    }

    @Test
    fun `an unreadable node_modules directory is skipped and does not refuse`() {
        val uri = workspace(mapOf("A.groovy" to a), open = "A.groovy")
        Files.writeString(tempDir.resolve("build.gradle"), "// module marker\n")
        val modules = Files.createDirectories(tempDir.resolve("web/node_modules"))
        walks.invalidate()

        withUnreadable(modules) { assertRenames(uri, a, "greet") }
    }

    @Test
    fun `an unreadable build output directory at a module root refuses and is named`() {
        // A readable build/ may be a source package (see the test above), so one that cannot be read may hide a caller.
        val uri = workspace(mapOf("A.groovy" to a), open = "A.groovy")
        Files.writeString(tempDir.resolve("build.gradle"), "// module marker\n")
        val outputs = listOf("build", "out", "target").map { Files.createDirectories(tempDir.resolve(it)) }
        walks.invalidate()

        withUnreadable(*outputs.toTypedArray()) {
            val message = refusal { provider.providePrepareRename(uri.toString(), positionOf(a, "greet")) }
            assertTrue("cannot be read" in message, message)
            outputs.forEach { assertTrue(it.fileName.toString() in message, "$it is named in: $message") }
        }
    }

    @Test
    fun `a different file with the same name as the renamed document still refuses`() {
        val files = mapOf(
            "A.groovy" to a,
            "other/A.groovy" to "class Other {\n    def run(x) { x.greet() }\n}\n",
        )
        val uri = workspace(files, open = "A.groovy")

        assertRefused(uri, a, "greet", "other")
    }

    @Test
    fun `an unreadable directory named like build output where it is not build output still refuses`() {
        val uri = workspace(mapOf("A.groovy" to a), open = "A.groovy")
        val nested = Files.createDirectories(tempDir.resolve("docs/build"))
        walks.invalidate()

        withUnreadable(nested) {
            val message = refusal { provider.providePrepareRename(uri.toString(), positionOf(a, "greet")) }
            assertTrue("cannot be read" in message && "build" in message, message)
        }
    }

    @Test
    fun `a document opened through a symlinked directory in the workspace is not another file`(): Unit = runBlocking {
        val own = "class A {\n    def greet() { 'hi' }\n    def run() { greet() }\n}\n"
        // The real root (macOS temp dirs are links): under a root a path's key is its normalized form.
        val root = tempDir.toRealPath()
        val real = Files.createDirectories(root.resolve("src/real"))
        Files.writeString(real.resolve("A.groovy"), own)
        val link = runCatching { Files.createSymbolicLink(root.resolve("src/link"), real) }
        Assumptions.assumeTrue(link.isSuccess, "symbolic links are not supported here")
        service.workspaceManager.initializeWorkspace(root)
        service.getWorkspaceCompiler().compileWorkspace()
        val uri = root.resolve("src/link/A.groovy").toUri()
        documents.put(uri, own)
        service.compile(uri, own)

        val edit = provider.provideRename(uri.toString(), positionOf(own, "greet"), "hello")

        assertEquals(2, edit.documentChanges.filter { it.isLeft }.sumOf { it.left.edits.size })
    }

    @Test
    fun `indexed uses in an untitled buffer or a file outside the workspace do not refuse`(): Unit = runBlocking {
        val uri = workspace(mapOf("A.groovy" to a, "B.groovy" to "class B {\n}\n"), open = "A.groovy")
        val untitled = URI.create("untitled:Untitled-1")
        val outside = Files.createDirectories(otherDir.resolve("outside-workspace")).resolve("C.groovy").toUri()
        val caller = "new A().greet()\n"
        listOf(untitled, outside).forEach {
            documents.put(it, caller)
            service.compile(it, caller)
        }
        val index = checkNotNull(service.getWorkspaceSymbolIndex())
        val module = checkNotNull(service.getValidParseResult(uri)?.ast)
        val owner = module.classes.single { it.nameWithoutPackage == "A" }
        val greet = SemanticDocumentBuilder.createMethodSymbolId(owner, owner.getMethods("greet").single())
        val proven = index.findReferenceDocuments(greet).keys
        assertTrue(untitled in proven && outside in proven, "the index proves both uses: $proven")

        assertRenames(uri, a, "greet")
    }

    @Test
    fun `an external edit to a closed indexed file is seen without a watcher event`() {
        val files = mapOf("A.groovy" to a, "B.groovy" to "class B {\n    def run() { 1 }\n}\n")
        val uri = workspace(files, open = "A.groovy")
        val edited = "class B {\n    def run() { new A().greet() }\n}\n"
        Files.writeString(tempDir.resolve("src").resolve("B.groovy"), edited)

        assertRefused(uri, a, "greet", "B.groovy")
    }

    @Test
    fun `a member the closed index never saw but only its own file uses still renames`(): Unit = runBlocking {
        val own = "class A {\n    def greet() { 'hi' }\n    def run() { greet() }\n}\n"
        val files = mapOf("A.groovy" to own, "B.groovy" to "class B {\n    def run() { 1 }\n}\n")
        assertRenames(workspace(files, open = "A.groovy", indexWorkspace = false), own, "greet")
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
