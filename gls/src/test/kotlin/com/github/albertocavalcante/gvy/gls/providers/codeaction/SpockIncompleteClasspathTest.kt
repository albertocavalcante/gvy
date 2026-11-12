package com.github.albertocavalcante.gvy.gls.providers.codeaction

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.Fallback
import com.github.albertocavalcante.nativeapi.ParseRequest
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpockIncompleteClasspathTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `missing transitive superclass withholds a table edit at every log level`() {
        val (jar, completeJar) = libraryJars()
        val uri = URI.create("file:///ProbeSpec.groovy")
        val content = """
            class ProbeSpec extends lib.Base {
                def feature() {
                    expect:
                    true
                    where:
                    a | b
                    100|2
                }
            }
        """.trimIndent()
        val loggers = listOf("com.github.albertocavalcante.groovyparser", "com.github.albertocavalcante.gvy")
            .map { LoggerFactory.getLogger(it) as Logger }
        val previous = loggers.map { it.level }
        try {
            val healthy = GroovyParserFacade(javaClass.classLoader).parse(
                ParseRequest(uri, content, classpath = listOf(completeJar), compilePhase = Phases.CANONICALIZATION),
            )
            assertTrue(!healthy.outcome.isDegraded, "The complete hierarchy must resolve: ${healthy.outcome}")
            val healthyActions = SpockTableAction.createActions(
                SpockTableRequest(uri.toString(), content, healthy.ast, Range(Position(5, 8), Position(5, 8))),
            )
            assertEquals(1, healthyActions.size, "The complete hierarchy proves this is a specification")
            val observations = listOf(Level.INFO, Level.DEBUG, Level.TRACE).map { level ->
                loggers.forEach { it.level = level }
                loggers.forEach { assertEquals(level, it.effectiveLevel) }
                listOf(
                    "com.github.albertocavalcante.groovyparser.GroovyParserFacade",
                    "com.github.albertocavalcante.gvy.spock.SpockSourceContext",
                    "com.github.albertocavalcante.gvy.gls.providers.codeaction.SpockTableAction",
                ).forEach { assertEquals(level, (LoggerFactory.getLogger(it) as Logger).effectiveLevel) }
                val result = GroovyParserFacade(javaClass.classLoader).parse(
                    ParseRequest(uri, content, classpath = listOf(jar), compilePhase = Phases.CANONICALIZATION),
                )
                assertTrue(result.outcome.isDegraded, "The omitted superclass must affect compilation")
                assertEquals(Phases.CANONICALIZATION, result.outcome.requestedPhase)
                assertEquals(Phases.CONVERSION, result.outcome.achievedPhase)
                assertEquals(Fallback.COMPILATION_FAILED, result.outcome.fallback)
                assertTrue(result.outcome.fallbackReason.orEmpty().contains("thirdparty.Missing"), "${result.outcome}")
                val actions = SpockTableAction.createActions(
                    SpockTableRequest(uri.toString(), content, result.ast, Range(Position(5, 8), Position(5, 8))),
                )
                assertTrue(actions.isEmpty(), "A missing hierarchy must not authorize a source edit")
                result.outcome to result.diagnostics.map { it.severity to it.message }
            }
            assertEquals(observations.first(), observations[1])
            assertEquals(observations.first(), observations[2])
        } finally {
            loggers.zip(previous).forEach { (logger, level) -> logger.level = level }
        }
    }

    private fun libraryJars(): Pair<Path, Path> {
        val classes = Files.createDirectories(tempDir.resolve("classes"))
        val config = CompilerConfiguration().apply { targetDirectory = classes.toFile() }
        CompilationUnit(config, null, GroovyClassLoader(javaClass.classLoader)).apply {
            addSource("Missing.groovy", "package thirdparty; class Missing extends spock.lang.Specification {}")
            addSource("Base.groovy", "package lib; class Base extends thirdparty.Missing {}")
            compile()
        }
        fun jar(name: String, entries: List<String>): Path = tempDir.resolve(name).also { path ->
            JarOutputStream(Files.newOutputStream(path)).use { out ->
                entries.forEach { entry ->
                    out.putNextEntry(JarEntry(entry))
                    Files.copy(classes.resolve(entry), out)
                    out.closeEntry()
                }
            }
        }
        return jar("base.jar", listOf("lib/Base.class")) to
            jar("complete.jar", listOf("lib/Base.class", "thirdparty/Missing.class"))
    }
}
