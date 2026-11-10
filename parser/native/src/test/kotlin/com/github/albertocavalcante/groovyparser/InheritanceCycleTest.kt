package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.time.Duration

/** A class hierarchy cycle must produce a diagnostic, never a hang (issue #1369). */
class InheritanceCycleTest {
    @TempDir
    lateinit var tempDir: Path

    private fun parse(code: String, roots: List<Path> = emptyList(), phase: Int = Phases.SEMANTIC_ANALYSIS) =
        assertTimeoutPreemptively(TIMEOUT) {
            GroovyParserFacade().parse(
                ParseRequest(
                    uri = URI.create("file:///Probe.groovy"),
                    content = code,
                    sourceRoots = roots,
                    compilePhase = phase,
                ),
            )
        }

    @Test
    fun `a cycle inside one file terminates with a diagnostic`() {
        val result = parse("class A extends B {}\nclass B extends A {}")
        assertTrue(result.diagnostics.any { it.message.contains("ycle") }, "${result.diagnostics.map { it.message }}")
    }

    @Test
    fun `a cycle across files terminates with a diagnostic`() {
        tempDir.resolve("A.groovy").toFile().writeText("class A extends B {}")
        tempDir.resolve("B.groovy").toFile().writeText("class B extends Probe {}")
        val result = parse("class Probe extends A {}", listOf(tempDir))
        assertTrue(result.diagnostics.any { it.message.contains("ycle") }, "${result.diagnostics.map { it.message }}")
    }

    @Test
    fun `an interface cycle terminates`() {
        parse("interface I extends J {}\ninterface J extends I {}")
    }

    @Test
    fun `a self-extending class terminates`() {
        parse("class A extends A {}")
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
