package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParserSeverity
import groovy.lang.GroovyClassLoader
import groovy.lang.GroovyShell
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.exists
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A jar on the compile classpath can ship a *local* AST transformation: an annotation whose
 * `@GroovyASTTransformationClass` names a transform class in the same jar. Analysis must not run it. The transform
 * below only writes a marker file named by a system property. The control proves the scenario can run it.
 *
 * Why it is blocked: the compile unit loads transform classes through the server's own transform loader, which never
 * sees workspace jars, so a transform that only exists in a classpath jar is not found and is reported as an error
 * from SEMANTIC_ANALYSIS (where local transforms are collected). Transforms in Groovy's own jars (`@CompileStatic`,
 * `@Canonical`, ...) are on that loader and keep working. The same holds for a precompiled `@AnnotationCollector`
 * with a `processor` class from the jar.
 */
class ClasspathLocalTransformTest {
    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun clearProperty() {
        System.clearProperty(MARKER_PROPERTY)
    }

    private fun groovyJar(): Path =
        Path.of(Class.forName("groovy.lang.Closure").protectionDomain.codeSource.location.toURI())

    /** A jar with annotation `probe.Mark` and its local transform `probe.MarkTransform` (writes the marker). */
    private fun transformJar(): Path {
        val classes = tempDir.resolve("classes").also { Files.createDirectories(it) }
        val config = CompilerConfiguration().apply { targetDirectory = classes.toFile() }
        CompilationUnit(config).apply {
            LIBRARY_SOURCES.forEach { (name, text) -> addSource("$name.groovy", text) }
            compile()
        }
        val jar = tempDir.resolve("shared-library.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            val names = listOf(
                "probe/Mark.class",
                "probe/MarkTransform.class",
                "probe/Boom.class",
                "probe/MarkProcessor.class",
            )
            for (name in names) {
                out.putNextEntry(JarEntry(name))
                out.write(Files.readAllBytes(classes.resolve(name)))
                out.closeEntry()
            }
        }
        return jar
    }

    private val directSource = "@probe.Mark\nclass A {}"
    private val processorSource = "@probe.Boom\nclass A {}"

    @Test
    fun `control - a plain compile that sees the jar does run its local transform`() = control(directSource)

    @Test
    fun `control - a precompiled collector with a processor from the jar does run under a plain compile`() =
        control(processorSource)

    private fun control(source: String) {
        val marker = tempDir.resolve("control-marker")
        System.setProperty(MARKER_PROPERTY, marker.toString())
        // The parent must own Groovy, or the transform implements a different ASTTransformation and is skipped.
        val loader = GroovyClassLoader(GroovyClassLoader::class.java.classLoader)
        loader.addClasspath(transformJar().toString())

        val failure = runCatching { GroovyShell(loader).evaluate(source) }.exceptionOrNull()

        assertTrue(marker.exists(), "control: the scenario must be able to run the transform ($failure)")
    }

    private fun assertNotRun(
        label: String,
        facade: GroovyParserFacade,
        classpath: List<Path>,
        phase: Int,
        source: String,
    ) {
        val marker = tempDir.resolve("marker-$label-$phase")
        System.setProperty(MARKER_PROPERTY, marker.toString())

        val result = facade.parse(
            ParseRequest(
                uri = URI.create("file:///A.groovy"),
                content = source,
                classpath = classpath,
                compilePhase = phase,
            ),
        )

        assertFalse(marker.exists(), "$label: the classpath jar's local transform ran at phase $phase")
        assertTrue(result.ast != null, "$label: no AST at phase $phase, so the check proves nothing")
        // From SEMANTIC_ANALYSIS the unknown transform class is reported as an error instead of being run
        val failed = result.diagnostics.any { it.severity == ParserSeverity.ERROR }
        assertTrue(failed == (phase >= Phases.SEMANTIC_ANALYSIS), "$label: unexpected diagnostics at phase $phase")
    }

    private val cases = mapOf(
        "direct" to directSource,
        "processor" to processorSource,
    )
    private val phases = listOf(Phases.CONVERSION, Phases.SEMANTIC_ANALYSIS, Phases.CANONICALIZATION)

    @Test
    fun `server-like facade does not run a classpath jar's local transform`() {
        val jar = transformJar()
        for ((label, source) in cases) {
            for (phase in phases) {
                assertNotRun("default-parent/$label", GroovyParserFacade(), listOf(groovyJar(), jar), phase, source)
            }
        }
    }

    @Test
    fun `facade sharing Groovy's loader does not run a classpath jar's local transform either`() {
        val jar = transformJar()
        for ((label, source) in cases) {
            for (phase in phases) {
                val facade = GroovyParserFacade(GroovyClassLoader::class.java.classLoader)
                assertNotRun("shared-parent/$label", facade, listOf(jar), phase, source)
            }
        }
    }

    private companion object {
        const val MARKER_PROPERTY = "gvy.test.local.transform.marker"

        private val LIBRARY_SOURCES: Map<String, String> = mapOf(
            "Mark" to """
package probe

import org.codehaus.groovy.transform.GroovyASTTransformationClass
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.TYPE])
@GroovyASTTransformationClass("probe.MarkTransform")
@interface Mark {}
            """.trimIndent(),
            "MarkTransform" to """
package probe

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation

@GroovyASTTransformation(phase = CompilePhase.SEMANTIC_ANALYSIS)
class MarkTransform implements ASTTransformation {
    void visit(ASTNode[] nodes, SourceUnit source) {
        new File(System.getProperty('$MARKER_PROPERTY')).text = 'x'
    }
}
            """.trimIndent(),
            "Boom" to """
package probe

import groovy.transform.AnnotationCollector

@AnnotationCollector(processor = 'probe.MarkProcessor')
@interface Boom {}
            """.trimIndent(),
            "MarkProcessor" to """
package probe

import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.transform.AnnotationCollectorTransform

class MarkProcessor extends AnnotationCollectorTransform {
    List<AnnotationNode> visit(
            AnnotationNode collector,
            AnnotationNode aliasAnnotationUsage,
            AnnotatedNode aliasAnnotated,
            SourceUnit source) {
        new File(System.getProperty('$MARKER_PROPERTY')).text = 'x'
        return []
    }
}
            """.trimIndent(),
        )
    }
}
