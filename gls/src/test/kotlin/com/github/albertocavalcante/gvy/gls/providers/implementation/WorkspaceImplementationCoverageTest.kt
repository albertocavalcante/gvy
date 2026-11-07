package com.github.albertocavalcante.gvy.gls.providers.implementation

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceImplementationCoverageTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `workspace compilation finds unopened implementing classes`(): Unit = runBlocking {
        val fixture = createWorkspace()
        fixture.compileWorkspace()
        assertNull(fixture.service.getCompiledContent(fixture.implementation.toUri()))
        assertNotNull(fixture.service.getWorkspaceSymbolIndex()?.getDocument(fixture.implementation.toUri()))

        val locations = fixture.provider.provideImplementations(fixture.contract.toUri().toString(), Position(0, 11))
            .toList()

        assertEquals(listOf(fixture.implementation.toUri().toString()), locations.map { it.uri })
    }

    @Test
    fun `workspace compilation finds unopened implementing methods`(): Unit = runBlocking {
        val fixture = createWorkspace()
        fixture.compileWorkspace()

        val locations = fixture.provider.provideImplementations(fixture.contract.toUri().toString(), Position(1, 12))
            .toList()

        assertEquals(listOf(fixture.implementation.toUri().toString()), locations.map { it.uri })
        assertEquals(listOf(1), locations.map { it.range.start.line })
    }

    @Test
    fun `evicting every query AST preserves implementation results`(): Unit = runBlocking {
        val fixture = createWorkspace(cacheLimit = 1)
        fixture.service.compile(fixture.contract.toUri(), Files.readString(fixture.contract))
        fixture.service.compile(fixture.implementation.toUri(), Files.readString(fixture.implementation))
        fixture.service.compile(workspace.resolve("Other.groovy").toUri(), "class Other {}")
        assertNull(fixture.service.getCompiledContent(fixture.contract.toUri()))
        assertNull(fixture.service.getCompiledContent(fixture.implementation.toUri()))

        val locations = fixture.provider.provideImplementations(fixture.contract.toUri().toString(), Position(0, 11))
            .toList()

        assertEquals(listOf(fixture.implementation.toUri().toString()), locations.map { it.uri })
    }

    @Test
    fun `captured implementation query survives subsequent cache invalidation`(): Unit = runBlocking {
        val fixture = createWorkspace()
        fixture.service.compile(fixture.contract.toUri(), Files.readString(fixture.contract))
        fixture.service.compile(fixture.implementation.toUri(), Files.readString(fixture.implementation))
        val query = fixture.provider.provideImplementations(fixture.contract.toUri().toString(), Position(0, 11))

        fixture.service.clearCaches()
        assertNull(fixture.service.getWorkspaceSymbolIndex()?.getDocument(fixture.contract.toUri()))

        assertEquals(listOf(fixture.implementation.toUri().toString()), query.toList().map { it.uri })
    }

    @Test
    fun `qualified interfaces with the same simple name remain distinct`(): Unit = runBlocking {
        val fixture = createWorkspace()
        Files.writeString(fixture.contract, "package first\ninterface Contract { String greet() }\n")
        Files.writeString(
            fixture.implementation,
            "package first\nclass Impl implements Contract { String greet() { 'hi' } }\n",
        )
        val otherContract =
            writeSource("other/Contract.groovy", "package other\ninterface Contract { String greet() }\n")
        val otherImpl =
            writeSource(
                "other/OtherImpl.groovy",
                "package other\nclass OtherImpl implements Contract { String greet() { 'no' } }\n",
            )
        listOf(fixture.contract, fixture.implementation, otherContract, otherImpl).forEach { file ->
            fixture.service.compile(file.toUri(), Files.readString(file))
        }

        val locations = fixture.provider.provideImplementations(fixture.contract.toUri().toString(), Position(1, 11))
            .toList()

        assertEquals(listOf(fixture.implementation.toUri().toString()), locations.map { it.uri })
    }

    @Test
    fun `implementation locations follow unsaved buffers and remove deleted files`(): Unit = runBlocking {
        val fixture = createWorkspace()
        val buffer = "\n\nclass Impl implements Contract {\n    String greet() { 'unsaved' }\n}\n"
        fixture.documents.put(fixture.implementation.toUri(), buffer)
        fixture.service.compile(fixture.implementation.toUri(), buffer)
        fixture.compileWorkspace()

        val locations = fixture.provider.provideImplementations(fixture.contract.toUri().toString(), Position(1, 12))
            .toList()
        assertEquals(listOf(fixture.implementation.toUri().toString()), locations.map { it.uri })
        assertEquals(listOf(3), locations.map { it.range.start.line })

        fixture.documents.remove(fixture.implementation.toUri())
        Files.delete(fixture.implementation)
        fixture.service.refreshSemanticFromDisk(listOf(fixture.implementation.toUri()))
        assertEquals(
            emptyList(),
            fixture.provider.provideImplementations(fixture.contract.toUri().toString(), Position(0, 11)).toList(),
        )
    }

    @Test
    fun `method overloads distinguish qualified parameter types with the same simple name`(): Unit = runBlocking {
        val fixture = createWorkspace()
        writeSource("first/Value.groovy", "package first\nclass Value {}\n")
        writeSource("other/Value.groovy", "package other\nclass Value {}\n")
        Files.writeString(fixture.contract, "interface Contract {\n    String greet(first.Value value)\n}\n")
        Files.writeString(
            fixture.implementation,
            "class Impl implements Contract {\n    String greet(other.Value value) { 'wrong' }\n" +
                "    String greet(first.Value value) { 'right' }\n}\n",
        )
        fixture.compileWorkspace()

        val locations = fixture.provider.provideImplementations(fixture.contract.toUri().toString(), Position(1, 12))
            .toList()

        assertEquals(listOf(fixture.implementation.toUri().toString()), locations.map { it.uri })
        assertEquals(listOf(2), locations.map { it.range.start.line })
    }

    @Test
    fun `inherited concrete methods appear once for multiple implementing subclasses`(): Unit = runBlocking {
        val fixture = createWorkspace()
        val left = writeSource("Left.groovy", "class Left extends Impl {}\n")
        val right = writeSource("Right.groovy", "class Right extends Impl {}\n")
        fixture.compileWorkspace()
        assertNotNull(fixture.service.getWorkspaceSymbolIndex()?.getDocument(left.toUri()))
        assertNotNull(fixture.service.getWorkspaceSymbolIndex()?.getDocument(right.toUri()))

        val locations = fixture.provider.provideImplementations(fixture.contract.toUri().toString(), Position(1, 12))
            .toList()

        assertEquals(listOf(fixture.implementation.toUri().toString()), locations.map { it.uri })
        assertEquals(listOf(1), locations.map { it.range.start.line })
    }

    @Test
    fun `comments inside an interface do not select the enclosing declaration`(): Unit = runBlocking {
        val fixture = createWorkspace()
        Files.writeString(
            fixture.contract,
            "interface Contract {\n    // Contract documentation\n    String greet()\n}\n",
        )
        fixture.compileWorkspace()

        assertEquals(
            emptyList(),
            fixture.provider.provideImplementations(fixture.contract.toUri().toString(), Position(1, 8)).toList(),
        )
    }

    @Test
    fun `implements headers select the referenced interface`(): Unit = runBlocking {
        val fixture = createWorkspace()
        fixture.compileWorkspace()

        val locations = fixture.provider.provideImplementations(
            fixture.implementation.toUri().toString(),
            Position(0, 24),
        )
            .toList()

        assertEquals(listOf(fixture.implementation.toUri().toString()), locations.map { it.uri })
    }

    @Test
    fun `nested interface declarations remain implementation targets`(): Unit = runBlocking {
        val service = GroovyCompilationService()
        val uri = workspace.resolve("Nested.groovy").toUri()
        val source = "class Outer {\n    interface Nested { String greet() }\n}\n" +
            "class Impl implements Outer.Nested { String greet() { 'hi' } }\n"
        assertTrue(service.compile(uri, source).isSuccess)

        val locations = ImplementationProvider(service).provideImplementations(uri.toString(), Position(1, 16)).toList()

        assertEquals(listOf(uri.toString()), locations.map { it.uri })
        assertEquals(listOf(3), locations.map { it.range.start.line })
    }

    @Test
    fun `most specific interface default wins over its parent default`(): Unit = runBlocking {
        val service = GroovyCompilationService()
        val uri = workspace.resolve("Defaults.groovy").toUri()
        val source = "interface A {\n    default String greet() { 'a' }\n}\n" +
            "interface B extends A {\n    default String greet() { 'b' }\n}\n" +
            "class C implements A, B {}\n"
        assertTrue(service.compile(uri, source).isSuccess)

        val locations = ImplementationProvider(service).provideImplementations(uri.toString(), Position(1, 22)).toList()

        assertEquals(listOf(uri.toString()), locations.map { it.uri })
        assertEquals(listOf(4), locations.map { it.range.start.line })
    }

    @Test
    fun `uniquely resolved interface calls find concrete implementations`(): Unit = runBlocking {
        val fixture = createWorkspace()
        val caller = writeSource(
            "Caller.groovy",
            "class Caller {\n    String run(Contract value) {\n" +
                "        value.greet()\n    }\n}\n",
        )
        fixture.compileWorkspace()
        assertNotNull(fixture.service.getWorkspaceSymbolIndex()?.getDocument(caller.toUri()))

        val locations = fixture.provider.provideImplementations(caller.toUri().toString(), Position(2, 16)).toList()

        assertEquals(
            listOf(fixture.implementation.toUri().toString()),
            locations.map { it.uri },
            fixture.service.getWorkspaceSymbolIndex()?.getDocument(caller.toUri()).toString(),
        )
        assertEquals(listOf(1), locations.map { it.range.start.line })
    }

    @Test
    fun `same arity dynamic overload calls remain unresolved`(): Unit = runBlocking {
        val fixture = createWorkspace()
        Files.writeString(
            fixture.contract,
            "interface Contract {\n    String greet(String value)\n    String greet(Integer value)\n}\n",
        )
        Files.writeString(
            fixture.implementation,
            "class Impl implements Contract {\n    String greet(String value) { 's' }\n" +
                "    String greet(Integer value) { 'i' }\n}\n",
        )
        val caller = writeSource(
            "Caller.groovy",
            "class Caller {\n    String run(Contract value, arg) {\n" +
                "        value.greet(arg)\n    }\n}\n",
        )
        fixture.compileWorkspace()
        assertNotNull(fixture.service.getWorkspaceSymbolIndex()?.getDocument(caller.toUri()))

        assertEquals(
            emptyList(),
            fixture.provider.provideImplementations(caller.toUri().toString(), Position(2, 16)).toList(),
        )
    }

    @Test
    fun `malformed URIs return no navigation locations`(): Unit = runBlocking {
        assertEquals(
            emptyList(),
            ImplementationProvider(
                GroovyCompilationService(),
            ).provideImplementations("file:///bad uri", Position(0, 0)).toList(),
        )
    }

    @Test
    fun `classpath interfaces remain navigation targets in headers and calls`(): Unit = runBlocking {
        val service = GroovyCompilationService()
        val uri = workspace.resolve("RunnableImpl.groovy").toUri()
        val source = "class Impl implements Runnable {\n    void run() {}\n}\n" +
            "class Caller {\n    void start(Runnable value) {\n        value.run()\n    }\n}\n"
        assertTrue(service.compile(uri, source).isSuccess)
        val provider = ImplementationProvider(service)

        assertEquals(
            listOf(0),
            provider.provideImplementations(uri.toString(), Position(0, 24)).toList().map { it.range.start.line },
        )
        assertEquals(
            listOf(1),
            provider.provideImplementations(uri.toString(), Position(5, 16)).toList().map { it.range.start.line },
        )
    }

    @Test
    fun `extends headers select the referenced abstract class`(): Unit = runBlocking {
        val service = GroovyCompilationService()
        val uri = workspace.resolve("AbstractImpl.groovy").toUri()
        assertTrue(service.compile(uri, "abstract class Base {}\nclass Impl extends Base {}\n").isSuccess)

        assertEquals(
            listOf(1),
            ImplementationProvider(service).provideImplementations(uri.toString(), Position(1, 21)).toList()
                .map { it.range.start.line },
        )
    }

    @Test
    fun `imported interface names remain implementation targets`(): Unit = runBlocking {
        val fixture = createWorkspace()
        Files.writeString(fixture.contract, "package contracts\ninterface Contract { String greet() }\n")
        Files.writeString(
            fixture.implementation,
            "import contracts.Contract\nclass Impl implements Contract { String greet() { 'hi' } }\n",
        )
        val imports = writeSource("Imports.groovy", "import contracts.Contract\nclass Imports {}\n")
        fixture.compileWorkspace()

        assertEquals(
            listOf(fixture.implementation.toUri().toString()),
            fixture.provider.provideImplementations(imports.toUri().toString(), Position(0, 19)).toList().map {
                it.uri
            },
        )
        assertEquals(
            emptyList(),
            fixture.provider.provideImplementations(imports.toUri().toString(), Position(0, 1)).toList(),
        )
    }

    @Test
    fun `static imports select the class but never its member or member alias`(): Unit = runBlocking {
        val fixture = createWorkspace()
        Files.writeString(
            fixture.contract,
            "package contracts\ninterface Contract { String FIELD = 'x'; String greet() }\n",
        )
        Files.writeString(
            fixture.implementation,
            "import contracts.Contract\nclass Impl implements Contract { String greet() { 'hi' } }\n",
        )
        val imports = listOf(
            "import static contracts.Contract.FIELD",
            "import static contracts.Contract.FIELD as label",
            "import static contracts.Contract.*",
        ).mapIndexed { index, text ->
            writeSource("Imports$index.groovy", "$text\nclass Imports$index {}\n") to text
        }
        fixture.compileWorkspace()

        imports.forEach { (path, text) ->
            assertEquals(
                listOf(fixture.implementation.toUri().toString()),
                fixture.provider.provideImplementations(
                    path.toUri().toString(),
                    Position(
                        0,
                        text.indexOf("Contract") + 2,
                    ),
                )
                    .toList().map { it.uri },
            )
            listOf("import", "static", "FIELD", "label", "*").forEach { token ->
                val column = text.indexOf(token)
                if (column >= 0) {
                    assertEquals(
                        emptyList(),
                        fixture.provider.provideImplementations(path.toUri().toString(), Position(0, column)).toList(),
                    )
                }
            }
        }
    }

    @Test
    fun `normal import aliases select the interface at the original name and alias`(): Unit = runBlocking {
        val fixture = createWorkspace()
        Files.writeString(fixture.contract, "package contracts\ninterface Contract { String greet() }\n")
        Files.writeString(
            fixture.implementation,
            "import contracts.Contract\nclass Impl implements Contract { String greet() { 'hi' } }\n",
        )
        val text = "import contracts.Contract as Alias"
        val header = "class Imports implements Alias { String greet() { 'alias' } }"
        val imports = writeSource("Imports.groovy", "$text\n$header\n")
        fixture.compileWorkspace()
        val expected = listOf(fixture.implementation.toUri().toString(), imports.toUri().toString()).sorted()

        listOf("Contract", "Alias").forEach { token ->
            assertEquals(
                expected,
                fixture.provider.provideImplementations(
                    imports.toUri().toString(),
                    Position(0, text.indexOf(token) + 2),
                )
                    .toList().map { it.uri }.sorted(),
            )
        }
        assertEquals(
            expected,
            fixture.provider.provideImplementations(
                imports.toUri().toString(),
                Position(1, header.indexOf("Alias") + 2),
            )
                .toList().map { it.uri }.sorted(),
        )
    }

    private data class Fixture(
        val service: GroovyCompilationService,
        val documents: DocumentProvider,
        val contract: Path,
        val implementation: Path,
    ) {
        val provider = ImplementationProvider(service)
    }

    private suspend fun Fixture.compileWorkspace() {
        service.workspaceManager.initializeWorkspace(workspace)
        service.getWorkspaceCompiler().compileWorkspace()
    }

    private fun createWorkspace(cacheLimit: Int = 300): Fixture {
        val contract = writeSource("Contract.groovy", "interface Contract {\n    String greet()\n}\n")
        val implementation =
            writeSource("Impl.groovy", "class Impl implements Contract {\n    String greet() { 'hi' }\n}\n")
        val documents = DocumentProvider()
        val service = GroovyCompilationService(documentProvider = documents, compilationCacheMaxEntries = cacheLimit)
        service.workspaceManager.initializeWorkspace(workspace)
        return Fixture(service, documents, contract, implementation)
    }

    private fun writeSource(relative: String, content: String): Path = workspace.resolve("src").resolve(relative).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, content)
    }
}
