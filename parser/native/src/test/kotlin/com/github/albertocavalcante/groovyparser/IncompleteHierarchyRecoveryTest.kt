package com.github.albertocavalcante.groovyparser

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.github.albertocavalcante.nativeapi.Fallback
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IncompleteHierarchyRecoveryTest {
    @TempDir
    lateinit var tempDir: Path

    @TestFactory
    fun `incomplete runtime classpath falls back and later parses recover`(): List<DynamicTest> {
        val jar = baseJar()
        return listOf(Level.INFO, Level.DEBUG, Level.TRACE).map { level ->
            DynamicTest.dynamicTest(level.toString()) {
                withParserLevel(level) {
                    val parser = GroovyParserFacade()
                    val result = parser.parse(
                        ParseRequest(
                            uri = URI.create("file:///Probe.groovy"),
                            content = "import lib.Base\nclass Probe extends Base {}\n",
                            classpath = listOf(jar),
                            compilePhase = Phases.CANONICALIZATION,
                        ),
                    )
                    assertTrue(result.outcome.isDegraded)
                    assertEquals(Phases.CANONICALIZATION, result.outcome.requestedPhase)
                    assertEquals(Phases.CONVERSION, result.outcome.achievedPhase)
                    assertEquals(Fallback.COMPILATION_FAILED, result.outcome.fallback)
                    assertTrue(assertNotNull(result.outcome.fallbackReason).contains("GroovyObject"))

                    val clean = parser.parse(
                        ParseRequest(
                            URI.create("file:///Later.groovy"),
                            "class Later {}",
                            compilePhase = Phases.CANONICALIZATION,
                        ),
                    )
                    assertTrue(clean.isSuccessful)
                    assertTrue(!clean.outcome.isDegraded)
                    val cleanAst = assertNotNull(clean.ast)
                    assertTrue(cleanAst.classes.any { it.name == "Later" })
                }
            }
        }
    }

    @Test
    fun `degraded outcome and diagnostics agree across log levels`() {
        val jar = baseJar()
        val parser = GroovyParserFacade()
        val outcomes = listOf(Level.INFO, Level.DEBUG, Level.TRACE).map { level ->
            withParserLevel(level) {
                val result = parser.parse(
                    ParseRequest(
                        URI.create("file:///Probe.groovy"),
                        "import lib.Base\nclass Probe extends Base {}\n",
                        classpath = listOf(jar),
                        compilePhase = Phases.CANONICALIZATION,
                    ),
                )
                Triple(
                    result.outcome,
                    result.diagnostics.map {
                        it.severity to it.message
                    },
                    result.ast?.classes?.map { it.name },
                )
            }
        }
        assertTrue(outcomes.first().first.isDegraded)
        assertEquals(outcomes.first(), outcomes[1])
        assertEquals(outcomes.first(), outcomes[2])
    }

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

    private fun <T> withParserLevel(level: Level, block: () -> T): T {
        val logger = LoggerFactory.getLogger("com.github.albertocavalcante.groovyparser") as Logger
        val previous = logger.level
        try {
            logger.level = level
            return block()
        } finally {
            logger.level = previous
        }
    }
}
