// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import com.github.albertocavalcante.nativeapi.ParserSeverity
import groovy.lang.GroovyClassLoader
import groovy.lang.GroovyShell
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
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
import javax.tools.ToolProvider
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An annotation collector that arrives precompiled in a jar is expanded by asking its data class for the collected
 * annotations: the compiler loads that class through the compile class loader, which sees project jars, and invokes
 * its static `value()` method. Analysis must not run it. The data class below only writes a marker file named by a
 * system property; the control proves the scenario can run it.
 *
 * The policy for collectors declared in source already drops their data class. The same holds here for collectors
 * read from class files: only a data class the server itself supplies is used, so Groovy's own collectors keep
 * expanding and a project's collector is reported instead of expanded.
 */
class PrecompiledCollectorPolicyTest {
    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun clearProperty() {
        System.clearProperty(MARKER_PROPERTY)
    }

    private fun groovyJar(): Path =
        Path.of(Class.forName("groovy.lang.Closure").protectionDomain.codeSource.location.toURI())

    /** A jar with `probe.DataColl`, whose data class writes the marker, and the Groovy-compiled `probe.Coll`. */
    private fun collectorJar(): Path {
        val classes = Files.createDirectories(tempDir.resolve("classes"))
        val sources = Files.createDirectories(tempDir.resolve("java").resolve("probe"))
        JAVA_SOURCES.forEach { (name, text) -> sources.resolve("$name.java").writeText(text) }
        val arguments = listOf("-proc:none", "-cp", groovyJar().toString(), "-d", classes.toString()) +
            JAVA_SOURCES.keys.map { sources.resolve("$it.java").toString() }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *arguments.toTypedArray()))
        val config = CompilerConfiguration().apply { targetDirectory = classes.toFile() }
        CompilationUnit(config).apply {
            addSource("Coll.groovy", GROOVY_COLLECTOR)
            compile()
        }
        val jar = tempDir.resolve("collectors.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            Files.walk(classes).use { files ->
                files.filter { Files.isRegularFile(it) }.forEach { file ->
                    out.putNextEntry(JarEntry(classes.relativize(file).joinToString("/")))
                    out.write(Files.readAllBytes(file))
                    out.closeEntry()
                }
            }
        }
        return jar
    }

    private fun parse(
        source: String,
        phase: Int,
        classpath: List<Path>,
        facade: GroovyParserFacade = GroovyParserFacade(),
    ): ParseResult = facade.parse(
        ParseRequest(
            uri = URI.create("file:///A.groovy"),
            content = source,
            classpath = classpath,
            compilePhase = phase,
        ),
    )

    private fun methodsOfA(result: ParseResult): Set<String> =
        result.ast!!.classes.first { it.name == "A" }.methods.mapTo(HashSet()) { it.name }

    /** Names of the classes the JVM defined while [action] ran. */
    private fun classesDefinedDuring(action: () -> Unit): List<String> {
        val file = Files.createTempFile(tempDir, "defined", ".jfr")
        Recording().use { recording ->
            recording.enable("jdk.ClassDefine")
            recording.start()
            action()
            recording.stop()
            recording.dump(file)
        }
        return RecordingFile.readAllEvents(file).mapNotNull { it.getClass("definedClass")?.name }
    }

    @Test
    fun `control - a plain compile that sees the jar does run the collector's data class`() {
        val marker = tempDir.resolve("control-marker")
        System.setProperty(MARKER_PROPERTY, marker.toString())
        val loader = GroovyClassLoader(GroovyClassLoader::class.java.classLoader)
        loader.addClasspath(collectorJar().toString())

        val failure = runCatching { GroovyShell(loader).evaluate(DATA_COLLECTOR_USE) }.exceptionOrNull()

        assertTrue(marker.exists(), "control: the scenario must be able to run the data class ($failure)")
    }

    @Test
    fun `a collector's data class from a jar does not run during analysis`() {
        val classpath = listOf(groovyJar(), collectorJar())
        for (phase in listOf(Phases.SEMANTIC_ANALYSIS, Phases.CANONICALIZATION)) {
            val marker = tempDir.resolve("marker-$phase")
            System.setProperty(MARKER_PROPERTY, marker.toString())

            val result = parse(DATA_COLLECTOR_USE, phase, classpath)

            assertTrue(result.ast != null, "no AST at phase $phase, so the check proves nothing")
            assertFalse(marker.exists(), "code from the dependency jar ran inside analysis at phase $phase")
        }
    }

    @Test
    fun `a project collector is reported as not expanded, not as an error`() {
        val result = parse(DATA_COLLECTOR_USE, Phases.CANONICALIZATION, listOf(groovyJar(), collectorJar()))

        val errors = result.diagnostics.filter { it.severity == ParserSeverity.ERROR }.map { it.message }
        assertTrue(errors.isEmpty(), errors.toString())
        val notice = result.diagnostics.single { it.code == PROJECT_COLLECTOR_NOT_EXPANDED }
        assertEquals(ParserSeverity.INFORMATION, notice.severity)
        assertTrue("probe.DataColl" in notice.message, notice.message)
        assertFalse("toString" in methodsOfA(result), "the project collector was expanded")
    }

    @Test
    fun `a collector compiled by Groovy into a jar never has its helper class loaded`() {
        val loaded = mutableListOf<String>()
        val facade = GroovyParserFacade(ClassLoader.getPlatformClassLoader()) { parent ->
            object : SdkFallbackGroovyClassLoader(parent) {
                override fun loadClass(
                    name: String,
                    lookupScriptFiles: Boolean,
                    preferClassOverScript: Boolean,
                    resolve: Boolean,
                ): Class<*> {
                    loaded.add(name)
                    return super.loadClass(name, lookupScriptFiles, preferClassOverScript, resolve)
                }
            }
        }

        val result = parse(
            "@probe.Coll\nclass A { String x }",
            Phases.CANONICALIZATION,
            listOf(groovyJar(), collectorJar()),
            facade,
        )

        assertTrue(result.ast != null)
        assertFalse(loaded.any { it.startsWith("probe.Coll$") }, "the collector's helper class was loaded: $loaded")
        assertEquals(1, result.diagnostics.count { it.code == PROJECT_COLLECTOR_NOT_EXPANDED })
    }

    @Test
    fun `Groovy's own collectors still expand with and without Groovy on the project classpath`() {
        for (classpath in listOf(listOf(groovyJar()), emptyList())) {
            val result = parse(BUILT_IN_COLLECTORS, Phases.CANONICALIZATION, classpath)

            val errors = result.diagnostics.filter { it.severity == ParserSeverity.ERROR }.map { it.message }
            assertTrue(errors.isEmpty(), "classpath=$classpath: $errors")
            assertTrue(result.diagnostics.none { it.code == PROJECT_COLLECTOR_NOT_EXPANDED })
            assertTrue(methodsOfA(result).containsAll(setOf("toString", "equals", "hashCode")), "$classpath")
        }
    }

    @Test
    fun `expanding a collector that sets a mode defines no class at run time`() {
        val sources = listOf(
            BUILT_IN_COLLECTORS,
            """
                import groovy.transform.AnnotationCollector
                import groovy.transform.AnnotationCollectorMode
                import groovy.transform.ToString

                @ToString
                @AnnotationCollector(mode = AnnotationCollectorMode.PREFER_EXPLICIT)
                @interface Described {}

                @Described
                class A { String x }
            """.trimIndent(),
        )
        for (source in sources) {
            lateinit var result: ParseResult

            val defined = classesDefinedDuring { result = parse(source, Phases.CANONICALIZATION, listOf(groovyJar())) }

            assertTrue("toString" in methodsOfA(result), "the collector was not expanded")
            assertEquals(emptyList(), defined.filter { it.startsWith("Expression$") })
        }
    }

    private companion object {
        const val MARKER_PROPERTY = "gvy.test.collector-data-marker"
        const val DATA_COLLECTOR_USE = "@probe.DataColl\nclass A { String x }"
        const val BUILT_IN_COLLECTORS = "@groovy.transform.Canonical\nclass A { String x; int y }\n" +
            "@groovy.transform.Immutable\nclass P { String name }"
        const val GROOVY_COLLECTOR = "package probe\nimport groovy.transform.*\n" +
            "@ToString\n@AnnotationCollector\n@interface Coll {}"
        val JAVA_SOURCES = mapOf(
            "Data" to """
                package probe;
                public class Data {
                    static {
                        String marker = System.getProperty("$MARKER_PROPERTY");
                        if (marker != null) {
                            try {
                                java.nio.file.Files.writeString(java.nio.file.Path.of(marker), "ran");
                            } catch (java.io.IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        }
                    }
                    public static Object[][] value() {
                        return new Object[][] {
                            { groovy.transform.ToString.class, new java.util.HashMap<String, Object>() }
                        };
                    }
                }
            """.trimIndent(),
            "DataColl" to """
                package probe;
                import groovy.transform.AnnotationCollector;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                @Retention(RetentionPolicy.RUNTIME)
                @AnnotationCollector(serializeClass = Data.class)
                public @interface DataColl {}
            """.trimIndent(),
        )
    }
}
