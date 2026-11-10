package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.Formatter
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpockTableProviderTest {
    private val uri = URI.create("file:///TableSpec.groovy")
    private val compilationService = GroovyCompilationService()
    private val documents = DocumentProvider()
    private val provider = CodeActionProvider(
        compilationService,
        documents,
        object : Formatter {
            override fun format(text: String): String = text
        },
    )
    private val content = """
        class TableSpec extends spock.lang.Specification {
            def "feature"() {
                expect:
                true
                where:
                a | b
                100|2
            }
        }
    """.trimIndent()

    @AfterEach
    fun clearCaches() {
        compilationService.clearCaches()
    }

    @Test
    fun `default compilation produces a versioned table action`(): Unit = runBlocking {
        documents.put(uri, content, 7)
        compilationService.compile(uri, content)

        val action = provider.provideCodeActions(params()).single { it.title == "Align Spock data table" }
        val documentEdit = action.edit.documentChanges.single().left
        assertEquals(uri.toString(), documentEdit.textDocument.uri)
        assertEquals(7, documentEdit.textDocument.version)
        assertEquals(listOf("a   | b", "100 | 2"), documentEdit.edits.map { it.newText })
    }

    @Test
    fun `withholds table action when current document differs from cached compilation`(): Unit = runBlocking {
        documents.put(uri, content, 7)
        compilationService.compile(uri, content)
        assertNotNull(documents.update(uri, content.replace("100", "1000"), 8))

        val actions = provider.provideCodeActions(params())

        assertTrue(actions.none { it.title == "Align Spock data table" })
    }

    @Test
    fun `withholds table action when client requests quick fixes`(): Unit = runBlocking {
        documents.put(uri, content, 7)
        compilationService.compile(uri, content)
        val params = params().apply { context.only = listOf(CodeActionKind.QuickFix) }

        val actions = provider.provideCodeActions(params)

        assertTrue(actions.none { it.title == "Align Spock data table" })
    }

    @Test
    fun `a local class named Specification gets no table action even with a spock star import`(): Unit = runBlocking {
        val source = """
                import spock.lang.*
                class Specification {}
                class Rule extends Specification {
                    def check() {
                        expect:
                        true
                        where:
                        a | b
                        100|2
                    }
                }
        """.trimIndent()
        documents.put(uri, source, 1)
        compilationService.compile(uri, source)

        val actions = provider.provideCodeActions(params(line = 7))

        assertTrue(actions.none { it.title == "Align Spock data table" })
    }

    @Test
    fun `an uncertain external base gets no table action`(): Unit = runBlocking {
        val source = content.replace("spock.lang.Specification", "BaseSpec")
        documents.put(uri, source, 1)
        compilationService.compile(uri, source)

        val actions = provider.provideCodeActions(params())

        assertTrue(actions.none { it.title == "Align Spock data table" })
    }

    @Test
    fun `an uncertain class gets no table action because the edit changes source`(): Unit = runBlocking {
        // Without Spock on the compile classpath, `import spock.lang.*` alone cannot prove the base.
        val withoutSpock = object : ClassLoader(SpockTableProviderTest::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("spock.")) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }

            override fun getResource(name: String) = if (name.startsWith("spock/")) null else super.getResource(name)
        }
        val service = GroovyCompilationService(withoutSpock)
        val source = "import spock.lang.*\n" +
            content.replace("class TableSpec extends spock.lang.Specification", "class TableSpec extends Specification")
        val starProvider = CodeActionProvider(
            service,
            documents,
            object : Formatter {
                override fun format(text: String): String = text
            },
        )
        try {
            documents.put(uri, source, 1)
            service.compile(uri, source)

            val actions = starProvider.provideCodeActions(params(line = 6))

            assertTrue(actions.none { it.title == "Align Spock data table" })
        } finally {
            service.clearCaches()
        }
    }

    @TempDir
    lateinit var workspace: Path

    private val starImported = "import spock.lang.*\n" +
        content.replace("class TableSpec extends spock.lang.Specification", "class TableSpec extends Specification")

    private fun jarOf(type: Class<*>): Path = Path.of(type.protectionDomain.codeSource.location.toURI())

    /** Spock and the Groovy runtime on the workspace compile classpath, as a resolved Gradle project has them. */
    private fun useSpockClasspath(vararg extra: Path) {
        compilationService.updateWorkspaceModel(
            workspace,
            listOf(jarOf(spock.lang.Specification::class.java), jarOf(groovy.lang.GroovyObject::class.java)) + extra,
            emptyList(),
        )
    }

    @Test
    fun `a clean star-import spec keeps the table action`(): Unit = runBlocking {
        useSpockClasspath()
        documents.put(uri, starImported, 1)
        compilationService.compile(uri, starImported)

        val actions = provider.provideCodeActions(params(line = 6))

        assertTrue(actions.any { it.title == "Align Spock data table" })
    }

    @Test
    fun `a spec extending a base from a jar keeps the table action`(): Unit = runBlocking {
        useSpockClasspath(baseSpecJar())
        val source = content.replace("extends spock.lang.Specification", "extends com.example.JarBaseSpec")
        documents.put(uri, source, 1)
        compilationService.compile(uri, source)

        val actions = provider.provideCodeActions(params())

        assertTrue(actions.any { it.title == "Align Spock data table" })
    }

    @Test
    fun `an ordinary compile error elsewhere keeps the action because resolution survives`(): Unit = runBlocking {
        // A collected semantic error does not trigger the CONVERSION retry, so the hierarchy stays resolved.
        val broken = starImported.replaceFirst(
            "    def \"feature\"() {",
            "    def broken() {\n        def x = 1\n        def x = 2\n    }\n    def \"feature\"() {",
        )
        useSpockClasspath()
        documents.put(uri, broken, 1)
        compilationService.compile(uri, broken)

        val actions = provider.provideCodeActions(params(line = 10))

        assertTrue(actions.any { it.title == "Align Spock data table" })
    }

    @Test
    fun `an unresolved CONVERSION AST withholds the action for a spec proven only by resolution`(): Unit = runBlocking {
        // A degraded compile is retried at CONVERSION, where nothing resolves (documented on SpockTableAction).
        // Compile at CONVERSION directly, with a complete classpath, so the scenario does not depend on a
        // classpath that fails to load the base (which also breaks the parser's debug logging, see #1409).
        useSpockClasspath(baseSpecJar())
        val source = content.replace("extends spock.lang.Specification", "extends com.example.JarBaseSpec")
        documents.put(uri, source, 1)
        compilationService.compile(uri, source, compilePhase = Phases.CONVERSION)

        val actions = provider.provideCodeActions(params())

        assertTrue(actions.none { it.title == "Align Spock data table" })
    }

    /** `com.example.JarBaseSpec extends spock.lang.Specification`, compiled into a jar. */
    private fun baseSpecJar(): Path {
        val classes = Files.createDirectories(workspace.resolve("classes"))
        val config = org.codehaus.groovy.control.CompilerConfiguration().apply { targetDirectory = classes.toFile() }
        org.codehaus.groovy.control.CompilationUnit(config, null, groovy.lang.GroovyClassLoader(javaClass.classLoader))
            .apply {
                addSource(
                    "JarBaseSpec.groovy",
                    "package com.example\nabstract class JarBaseSpec extends spock.lang.Specification {}\n",
                )
                compile()
            }
        val jar = workspace.resolve("base.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            Files.walk(classes).filter(Files::isRegularFile).forEach { file ->
                out.putNextEntry(JarEntry(classes.relativize(file).toString().replace('\\', '/')))
                Files.copy(file, out)
                out.closeEntry()
            }
        }
        return jar
    }

    private fun params(line: Int = 5) = CodeActionParams().apply {
        textDocument = TextDocumentIdentifier(uri.toString())
        range = Range(Position(line, 8), Position(line, 8))
        context = CodeActionContext(emptyList())
    }
}
