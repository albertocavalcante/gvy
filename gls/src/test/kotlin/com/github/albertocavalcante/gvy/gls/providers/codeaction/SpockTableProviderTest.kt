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
    private fun useSpockClasspath(vararg extra: Path, sources: List<Path> = emptyList()) {
        compilationService.updateWorkspaceModel(
            workspace,
            listOf(jarOf(spock.lang.Specification::class.java), jarOf(groovy.lang.GroovyObject::class.java)) + extra,
            sources,
        )
    }

    /** A workspace with `BaseSpec.groovy` and a table spec extending [superclass], compiled cleanly. */
    private inner class BrokenBaseFixture(superclass: String = "BaseSpec") {
        val src: Path = Files.createDirectories(workspace.resolve("src"))
        val base: Path = writeBase("BaseSpec")
        val file: Path = src.resolve("TableSpec.groovy")
        val fileUri: URI = file.toUri()
        var text: String = content.replace("extends spock.lang.Specification", "extends $superclass")
        private var version = 1

        init {
            Files.writeString(file, text)
            useSpockClasspath(sources = listOf(src))
        }

        fun writeBase(
            name: String,
            broken: Boolean = false,
            extends: String = "extends Specification",
            prefix: String = "",
        ): Path = src.resolve("$name.groovy").also {
            val body = if (broken) "{ void m( }" else "{}"
            Files.writeString(it, "import spock.lang.*\n${prefix}abstract class $name $extends $body\n")
        }

        suspend fun edit(newText: String = "$text\n// edit $version") {
            text = newText
            version++
            documents.put(fileUri, text, version)
            compilationService.compile(fileUri, text)
        }

        suspend fun hasAction(paramsLine: Int = 5): Boolean = provider.provideCodeActions(
            params(line = paramsLine).apply { textDocument = TextDocumentIdentifier(fileUri.toString()) },
        ).any { it.title == "Align Spock data table" }
    }

    @Test
    fun `a broken base spec file keeps the table action through the remembered verdict`(): Unit = runBlocking {
        val fixture = BrokenBaseFixture()
        fixture.edit(fixture.text)
        assertTrue(fixture.hasAction(), "clean compile")

        fixture.writeBase("BaseSpec", broken = true)
        fixture.edit()

        assertTrue(fixture.hasAction(), "the remembered verdict for the same base applies")
    }

    @Test
    fun `a spec moved to a different broken base gets no remembered verdict`(): Unit = runBlocking {
        val fixture = BrokenBaseFixture()
        fixture.edit(fixture.text)
        assertTrue(fixture.hasAction(), "clean compile")

        fixture.writeBase("OtherBaseSpec", broken = true)
        fixture.edit(fixture.text.replace("extends BaseSpec", "extends OtherBaseSpec"))

        assertTrue(!fixture.hasAction(), "the superclass spelling changed, so nothing is reused")
    }

    @Test
    fun `an extends edit behind an annotation closure is noticed while the base is broken`(): Unit = runBlocking {
        val fixture = BrokenBaseFixture()
        fixture.edit("import spock.lang.Requires\n@Requires({ true })\n" + fixture.text)
        assertTrue(fixture.hasAction(paramsLine = 7), "clean compile")

        fixture.writeBase("BaseSpec", broken = true)
        fixture.edit(fixture.text.replace("extends BaseSpec", "extends MissingBaseSpec"))

        assertTrue(!fixture.hasAction(paramsLine = 7), "the superclass spelling changed behind a closure")
    }

    @Test
    fun `a base edited off Specification before it broke is not trusted`(): Unit = runBlocking {
        val fixture = BrokenBaseFixture()
        fixture.edit(fixture.text)
        assertTrue(fixture.hasAction(), "clean compile records the verdict")

        // The base stops extending Specification and the spec recompiles cleanly, with no code action asked.
        fixture.writeBase("BaseSpec", extends = "")
        fixture.edit()
        fixture.writeBase("BaseSpec", broken = true, extends = "")
        fixture.edit()

        assertTrue(!fixture.hasAction(), "the broken base's prelude no longer matches the recorded one")
    }

    @Test
    fun `a doc comment naming the base cannot hide its extends edit`(): Unit = runBlocking {
        val doc = "/** Base class BaseSpec, see {@link Object} */\n"
        val fixture = BrokenBaseFixture()
        fixture.writeBase("BaseSpec", prefix = doc)
        fixture.edit(fixture.text)
        assertTrue(fixture.hasAction(), "clean compile records the verdict")

        fixture.writeBase("BaseSpec", broken = true, extends = "", prefix = doc)
        fixture.edit()

        assertTrue(!fixture.hasAction(), "the prelude is lexed past the comment and no longer matches")
    }

    @Test
    fun `an in-file base edited off its remote base is noticed while the remote base is broken`(): Unit = runBlocking {
        val fixture = BrokenBaseFixture()
        val withLocal = "class LocalBase extends BaseSpec {}\n" +
            fixture.text.replace("class TableSpec extends BaseSpec", "class TableSpec extends LocalBase")
        fixture.edit(withLocal)
        assertTrue(fixture.hasAction(paramsLine = 6), "clean compile records the verdict through LocalBase")

        fixture.writeBase("BaseSpec", broken = true)
        fixture.edit(
            fixture.text.replace("class LocalBase extends BaseSpec", "class LocalBase extends SomethingMissing"),
        )

        assertTrue(!fixture.hasAction(paramsLine = 6), "the in-file link's superclass changed")
    }

    @Test
    fun `a new file shadowing Specification in the base package is noticed`(): Unit = runBlocking {
        val fixture = BrokenBaseFixture()
        fixture.edit(fixture.text)
        assertTrue(fixture.hasAction(), "clean compile records the verdict")

        Files.writeString(fixture.src.resolve("Specification.groovy"), "class Specification {}\n")
        fixture.writeBase("BaseSpec", broken = true)
        fixture.edit()

        assertTrue(!fixture.hasAction(), "the base package's file set changed")
    }

    @Test
    fun `a new file shadowing Specification in the base package under another root is noticed`(): Unit = runBlocking {
        val fixture = BrokenBaseFixture()
        val testRoot = Files.createDirectories(workspace.resolve("test-src"))
        useSpockClasspath(sources = listOf(fixture.src, testRoot))
        fixture.edit(fixture.text)
        assertTrue(fixture.hasAction(), "clean compile records the verdict")

        Files.writeString(testRoot.resolve("Specification.groovy"), "class Specification {}\n")
        fixture.writeBase("BaseSpec", broken = true)
        fixture.edit()

        assertTrue(!fixture.hasAction(), "the base package's file set across both roots changed")
    }

    @Test
    fun `nested source roots still list the base's real package`(): Unit = runBlocking {
        // Roots /w and /w/src: deriving the package from the directory would give `src` under /w, missing the
        // default package of /w itself.
        val fixture = BrokenBaseFixture()
        useSpockClasspath(sources = listOf(workspace, fixture.src))
        fixture.edit(fixture.text)
        assertTrue(fixture.hasAction(), "clean compile records the verdict")

        Files.writeString(workspace.resolve("Specification.groovy"), "class Specification {}\n")
        fixture.writeBase("BaseSpec", broken = true)
        fixture.edit()

        assertTrue(!fixture.hasAction(), "a shadowing file in the base's package under the outer root is seen")
    }

    @Test
    fun `closing the document forgets its remembered verdicts`(): Unit = runBlocking {
        val fixture = BrokenBaseFixture()
        fixture.edit(fixture.text)
        assertTrue(fixture.hasAction(), "clean compile")

        provider.forgetDocument(fixture.fileUri)
        fixture.writeBase("BaseSpec", broken = true)
        fixture.edit()

        assertTrue(!fixture.hasAction(), "nothing is remembered after close")
    }

    @Test
    fun `a classpath change forbids reusing a remembered verdict`(): Unit = runBlocking {
        val fixture = BrokenBaseFixture()
        fixture.edit(fixture.text)
        assertTrue(fixture.hasAction(), "clean compile")

        useSpockClasspath(baseSpecJar(), sources = listOf(fixture.src))
        fixture.writeBase("BaseSpec", broken = true)
        fixture.edit()

        assertTrue(!fixture.hasAction(), "a new compile fingerprint invalidates the record")
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
