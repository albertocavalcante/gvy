// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParserSeverity
import groovy.lang.GroovyClassLoader
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
 * A jar on the compile classpath (for example a Jenkins shared library from `jenkins.sharedLibraries`) can ship a
 * global AST transformation (registered at CANONICALIZATION here) through
 * `META-INF/services/org.codehaus.groovy.transform.ASTTransformation`. Analysis must never run it. The transform
 * below only writes a marker file named by a system property. The control test proves the scenario can run it.
 *
 * Three defenses each hide the transform (the facade disables the service-declared classes found on the classpath,
 * its project loader hides the service file, and its transform loader never sees workspace jars), so this test
 * proves their combination, not any single layer. A manual mutation check is recorded in the pull request.
 */
class ClasspathGlobalTransformTest {
    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun clearProperty() {
        System.clearProperty(MARKER_PROPERTY)
    }

    /** A jar with a global transform at CONVERSION that writes the marker, registered via META-INF/services. */
    private fun transformJar(): Path {
        val classes = tempDir.resolve("classes").also { Files.createDirectories(it) }
        val config = CompilerConfiguration().apply { targetDirectory = classes.toFile() }
        CompilationUnit(config).apply {
            addSource(
                "MarkerTransform.groovy",
                """
                package probe

                import org.codehaus.groovy.ast.ASTNode
                import org.codehaus.groovy.control.CompilePhase
                import org.codehaus.groovy.control.SourceUnit
                import org.codehaus.groovy.transform.ASTTransformation
                import org.codehaus.groovy.transform.GroovyASTTransformation

                @GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
                class MarkerTransform implements ASTTransformation {
                    void visit(ASTNode[] nodes, SourceUnit source) {
                        new File(System.getProperty('$MARKER_PROPERTY')).text = 'x'
                    }
                }
                """.trimIndent(),
            )
            compile()
        }
        val jar = tempDir.resolve("shared-library.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry("probe/MarkerTransform.class"))
            out.write(Files.readAllBytes(classes.resolve("probe/MarkerTransform.class")))
            out.closeEntry()
            out.putNextEntry(JarEntry("META-INF/services/org.codehaus.groovy.transform.ASTTransformation"))
            out.write("probe.MarkerTransform\n".toByteArray())
            out.closeEntry()
        }
        return jar
    }

    @Test
    fun `control - a plain compile that sees the jar does run its global transform`() {
        val marker = tempDir.resolve("control-marker")
        System.setProperty(MARKER_PROPERTY, marker.toString())
        // The parent must be the loader that owns Groovy, or the transform implements a different copy of
        // ASTTransformation and Groovy silently skips it.
        val loader = GroovyClassLoader(GroovyClassLoader::class.java.classLoader)
        loader.addClasspath(transformJar().toString())

        val failure = runCatching { groovy.lang.GroovyShell(loader).evaluate("1 + 1") }.exceptionOrNull()

        assertTrue(
            marker.exists(),
            "control: the scenario must be able to run the transform, or the facade test proves nothing " +
                "($failure)",
        )
    }

    @Test
    fun `analysis with the jar on the classpath does not run its global transform`() {
        val marker = tempDir.resolve("marker")
        System.setProperty(MARKER_PROPERTY, marker.toString())
        val jar = transformJar()

        for (phase in listOf(Phases.CONVERSION, Phases.SEMANTIC_ANALYSIS, Phases.CANONICALIZATION)) {
            // Sharing Groovy's loader makes the jar's transform a valid ASTTransformation, as in the control
            val result = GroovyParserFacade(GroovyClassLoader::class.java.classLoader).parse(
                ParseRequest(
                    uri = URI.create("file:///Probe.groovy"),
                    content = "class Probe {}",
                    classpath = listOf(jar),
                    compilePhase = phase,
                ),
            )

            // The compile must really have reached the requested phase, or "no marker" would prove nothing
            assertTrue(
                result.diagnostics.none { it.severity == ParserSeverity.ERROR },
                "phase $phase: ${result.diagnostics.map { it.message }}",
            )
            assertTrue(result.ast?.classes?.any { it.name == "Probe" } == true, "phase $phase: no AST for Probe")
            assertFalse(marker.exists(), "the classpath jar's global transform ran at phase $phase")
        }
    }

    private companion object {
        const val MARKER_PROPERTY = "gvy.test.global.transform.marker"
    }
}
