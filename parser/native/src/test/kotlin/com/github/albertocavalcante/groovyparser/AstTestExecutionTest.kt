package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * `@ASTTest` (shipped in the core `groovy` jar) runs its closure inside the compiler JVM whenever the compiled
 * project has Groovy on its classpath. Analysis must never execute source text from the workspace. The closures
 * below only write a marker file into a temp directory; every test asserts that the marker was NOT written.
 */
class AstTestExecutionTest {
    @TempDir
    lateinit var tempDir: Path

    private val phases = listOf(Phases.CONVERSION, Phases.SEMANTIC_ANALYSIS, Phases.CANONICALIZATION)

    private fun groovyJar(): Path =
        Path.of(Class.forName("groovy.lang.Closure").protectionDomain.codeSource.location.toURI())

    private fun parse(
        code: String,
        phase: Int,
        sourceRoots: List<Path> = emptyList(),
        uri: URI = URI.create("file:///A.groovy"),
    ) = GroovyParserFacade().parse(
        ParseRequest(
            uri = uri,
            content = code,
            classpath = listOf(groovyJar()),
            sourceRoots = sourceRoots,
            compilePhase = phase,
        ),
    )

    /** `{ new File('<marker>').text = 'x' }` for a marker named [name]; the returned path must not exist afterwards. */
    private fun closure(name: String): Pair<String, Path> {
        val marker = tempDir.resolve(name)
        return "{ new File('${marker.toString().replace("\\", "/")}').text = 'x' }" to marker
    }

    /** Parses [template] (with `CLOSURE` replaced by a marker-writing closure) at every phase; nothing may run. */
    private fun assertNotExecuted(name: String, template: String) {
        for (phase in phases) {
            val (closure, marker) = closure("$name-$phase")
            val result = parse(template.replace("CLOSURE", closure), phase)

            assertFalse(marker.exists(), "$name: @ASTTest closure executed at phase $phase")
            assertNotNull(result.ast, "$name: the AST must survive (phase $phase)")
        }
    }

    @Test
    fun `plain ASTTest on a class`() = assertNotExecuted(
        "plain",
        "@groovy.transform.ASTTest(phase=org.codehaus.groovy.control.CompilePhase.SEMANTIC_ANALYSIS," +
            " value=CLOSURE)\nclass A {}",
    )

    @Test
    fun `simple name, star import and alias`() {
        assertNotExecuted("simple", "import groovy.transform.ASTTest\n@ASTTest(value=CLOSURE)\nclass A {}")
        assertNotExecuted("star", "import groovy.transform.*\n@ASTTest(value=CLOSURE)\nclass A {}")
        assertNotExecuted("alias", "import groovy.transform.ASTTest as T\n@T(value=CLOSURE)\nclass A {}")
    }

    @Test
    fun `members, parameters and locals`() = assertNotExecuted(
        "members",
        """
        class A {
            @groovy.transform.ASTTest(value=CLOSURE) int field
            @groovy.transform.ASTTest(value=CLOSURE) void m(@groovy.transform.ASTTest(value=CLOSURE) int p) {
                @groovy.transform.ASTTest(value=CLOSURE) def local = 1
            }
        }
        """.trimIndent(),
    )

    @Test
    fun `closure parameter, catch parameter and type use`() {
        assertNotExecuted(
            "closure-param",
            "def c = { @groovy.transform.ASTTest(value=CLOSURE) int x -> x }\nclass A {}",
        )
        assertNotExecuted(
            "catch-param",
            "class A { void m() { try { } catch (@groovy.transform.ASTTest(value=CLOSURE) Exception e) { } } }",
        )
        assertNotExecuted("type-use", "class A { List<@groovy.transform.ASTTest(value=CLOSURE) String> l }")
    }

    @Test
    fun `enum constant body, anonymous class and trait`() {
        assertNotExecuted(
            "enum-body",
            "enum E { X { @groovy.transform.ASTTest(value=CLOSURE) void m() { } } ; void m() { } }",
        )
        assertNotExecuted(
            "anonymous",
            "class A { def r = new Runnable() { @groovy.transform.ASTTest(value=CLOSURE) void run() { } } }",
        )
        assertNotExecuted("trait", "trait T { @groovy.transform.ASTTest(value=CLOSURE) void m() { } }")
    }

    private fun collector(entries: String, extra: String = "") =
        "import groovy.transform.AnnotationCollector\nimport groovy.transform.AnnotationCollectorMode\n" +
            "import groovy.transform.ASTTest\nimport groovy.transform.ASTTest as T\n" +
            "@AnnotationCollector(value=[$entries]$extra)\n@interface Boom {}\n"

    @Test
    fun `AnnotationCollector alias forms`() {
        val use = "\n@Boom(value=CLOSURE)\nclass A {}"
        assertNotExecuted("collector-simple", collector("ASTTest") + use)
        assertNotExecuted("collector-qualified", collector("groovy.transform.ASTTest") + use)
        assertNotExecuted("collector-alias", collector("T") + use)
        assertNotExecuted("collector-class-literal", collector("ASTTest.class") + use)
        assertNotExecuted("collector-qualified-class-literal", collector("groovy.transform.ASTTest.class") + use)
    }

    @Test
    fun `AnnotationCollector in every mode`() {
        val use = "\n@Boom(value=CLOSURE)\nclass A {}"
        val modes = listOf(
            "DUPLICATE",
            "PREFER_COLLECTOR",
            "PREFER_COLLECTOR_MERGED",
            "PREFER_EXPLICIT",
            "PREFER_EXPLICIT_MERGED",
        )
        for (mode in modes) {
            assertNotExecuted("collector-$mode", collector("ASTTest", ", mode=AnnotationCollectorMode.$mode") + use)
        }
    }

    @Test
    fun `AnnotationCollector declared after its use or nested in a class`() {
        assertNotExecuted(
            "collector-after-use",
            "@Boom(value=CLOSURE)\nclass A {}\n" + collector("ASTTest"),
        )
        assertNotExecuted(
            "collector-nested",
            "import groovy.transform.AnnotationCollector\nimport groovy.transform.ASTTest\n" +
                "class Outer {\n @AnnotationCollector([ASTTest]) @interface Boom {}\n" +
                " @Boom(value=CLOSURE) void m() { }\n}",
        )
    }

    @Test
    fun `AnnotationCollector that carries ASTTest as a plain annotation`() = assertNotExecuted(
        "collector-direct",
        "import groovy.transform.AnnotationCollector\nimport groovy.transform.ASTTest\n" +
            "@AnnotationCollector @ASTTest(value=CLOSURE) @interface Boom {}\n@Boom\nclass A {}",
    )

    @Test
    fun `transitive collectors declared in other workspace sources`() {
        for (phase in listOf(Phases.SEMANTIC_ANALYSIS, Phases.CANONICALIZATION)) {
            val (closure, marker) = closure("workspace-$phase")
            val root = tempDir.resolve("src-$phase").also { Files.createDirectories(it) }
            Files.writeString(root.resolve("Boom.groovy"), collector("ASTTest"))
            Files.writeString(
                root.resolve("Bigger.groovy"),
                "import groovy.transform.AnnotationCollector\n@AnnotationCollector([Boom])\n@interface Bigger {}",
            )
            val code = "@Bigger(value=$closure)\nclass A {}"
            val file = root.resolve("A.groovy").also { Files.writeString(it, code) }

            parse(code, phase, sourceRoots = listOf(root), uri = file.toUri())

            assertFalse(marker.exists(), "transitive workspace collector executed at phase $phase")
        }
    }
}
