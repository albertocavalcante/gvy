package com.github.albertocavalcante.gvy.diagnostics.codenarc

import groovy.lang.GroovySystem
import org.codenarc.CodeNarcRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Isolated("Captures the process output streams")
class CodeAnalyzerOutputTest {
    @Test
    fun `embedded analysis sends its summary to stderr without replacing stdout`() {
        val originalOut = System.out
        val originalErr = System.err
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val originalRunnerMetaClass = GroovySystem.getMetaClassRegistry().getMetaClass(CodeNarcRunner::class.java)
        PrintStream(stdout, true, Charsets.UTF_8).use { capturedOut ->
            PrintStream(stderr, true, Charsets.UTF_8).use { capturedErr ->
                System.setOut(capturedOut)
                System.setErr(capturedErr)
                try {
                    val results = DefaultCodeAnalyzer().analyze(
                        "def answer = 42   \n",
                        "Answer.groovy",
                        "ruleset { TrailingWhitespace }",
                    )

                    assertEquals(1, results.getTotalNumberOfFiles(true))
                    assertTrue((1..3).sumOf { results.getNumberOfViolationsWithPriority(it, true) } > 0)
                    assertSame(capturedOut, System.out)
                    assertSame(
                        originalRunnerMetaClass,
                        GroovySystem.getMetaClassRegistry().getMetaClass(CodeNarcRunner::class.java),
                    )
                    assertEquals("", stdout.toString(Charsets.UTF_8))
                    assertTrue(stderr.toString(Charsets.UTF_8).contains("CodeNarc completed:"))
                } finally {
                    System.setOut(originalOut)
                    System.setErr(originalErr)
                }
            }
        }
    }
}
