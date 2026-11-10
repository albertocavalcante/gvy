package com.github.albertocavalcante.groovyparser

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.github.albertocavalcante.groovyparser.ast.visitor.RecursiveAstVisitor
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Logging must never change a parse result (#1409). A supertype compiled from Groovy sits on a classpath that lacks
 * the Groovy runtime, so its own supertypes (`groovy.lang.GroovyObject`) cannot load: walking its hierarchy throws
 * `NoClassDefFoundError`. Only the visitor's logger is raised to DEBUG, never the root, and it is restored after.
 */
class DebugLoggingLinkageTest {
    @TempDir
    lateinit var tempDir: Path

    /** A jar with a Groovy-compiled `lib.Base`, and nothing of the Groovy runtime. */
    private fun baseJar(): Path {
        val classes = Files.createDirectories(tempDir.resolve("classes"))
        CompilationUnit(CompilerConfiguration().apply { targetDirectory = classes.toFile() }).apply {
            addSource("Base.groovy", "package lib\nclass Base {}\n")
            compile()
        }
        val jar = tempDir.resolve("lib.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry("lib/Base.class"))
            out.write(Files.readAllBytes(classes.resolve("lib/Base.class")))
            out.closeEntry()
        }
        return jar
    }

    private fun parse(jar: Path): ParseResult = GroovyParserFacade().parse(
        ParseRequest(
            uri = URI.create("file:///Probe.groovy"),
            content = "import lib.Base\nclass Probe extends Base {}\n",
            classpath = listOf(jar),
            compilePhase = Phases.CANONICALIZATION,
        ),
    )

    private fun <T> withVisitorLogging(level: Level, block: () -> T): T {
        val logger = LoggerFactory.getLogger(RecursiveAstVisitor::class.java) as Logger
        val previous = logger.level
        logger.level = level
        return try {
            block()
        } finally {
            logger.level = previous
        }
    }

    /** What a caller can observe of a parse, independent of object identity. */
    private fun observed(result: ParseResult) = listOf(
        result.ast?.classes?.map { it.name },
        result.diagnostics.map { "${it.severity}:${it.message}" },
        result.outcome.isDegraded,
    )

    @Test
    fun `debug logging on the visitor does not change the result of a parse with an unloadable supertype`() {
        val jar = baseJar()

        val info = withVisitorLogging(Level.INFO) { parse(jar) }
        val debug = withVisitorLogging(Level.DEBUG) { parse(jar) }

        assertNotNull(info.ast, "the INFO parse must still produce a module")
        assertEquals(observed(info), observed(debug), "DEBUG logging changed the parse result")
    }
}
