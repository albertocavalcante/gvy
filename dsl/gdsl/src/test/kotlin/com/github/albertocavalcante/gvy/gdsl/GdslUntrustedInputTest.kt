package com.github.albertocavalcante.gvy.gdsl

import groovy.lang.GroovyShell
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * GDSL is untrusted input (#1247): reading it must never run any of it. Each hostile script would
 * write [marker] (or exit the JVM) if executed; the old GroovyShell-based executor ran all of them.
 */
// SEPARATE_THREAD: a reader that ran `while (true) {}` would never notice an interrupt.
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class GdslUntrustedInputTest {

    @TempDir
    lateinit var tempDir: Path

    private val executor = GdslExecutor()

    private val marker: Path get() = tempDir.resolve("marker")

    private fun hostile(template: String): String = template.replace("MARKER", marker.toString().replace("\\", "\\\\"))

    private val validContributor = """
        contributor(context(scope: scriptScope())) {
            method(name: 'echo', type: 'Object', params: [message: 'java.lang.String'], doc: 'Print Message')
        }
    """.trimIndent()

    @ParameterizedTest
    @ValueSource(
        strings = [
            "new File('MARKER').text = 'x'",
            "Runtime.getRuntime().exec(['touch', 'MARKER'] as String[])",
            "['touch', 'MARKER'].execute()",
            "Class.forName('java.lang.Runtime').getRuntime().exec(['touch', 'MARKER'] as String[])",
            "this.metaClass.evil = { new File('MARKER').text = 'x' }; evil()",
            "class Evil { static { new File('MARKER').text = 'x' } }; new Evil()",
            "evaluate(\"new File('MARKER').text = 'x'\")",
            "contributor(context()) { new File('MARKER').text = 'x' }",
            "contributor(context()) { method(name: new File('MARKER').with { it.text = 'x'; 'n' }) }",
            "def ctx = context(scope: scriptScope()); ctx.metaClass.foo = 1; new File('MARKER').text = 'x'",
            "System.exit(1)",
        ],
    )
    fun `hostile code is skipped, never executed`(code: String) {
        val result = executor.executeAndCapture(hostile(code) + "\n" + validContributor, "hostile.gdsl")

        assertThat(Files.exists(marker)).describedAs("hostile code ran: $code").isFalse()
        assertThat(result.success).isTrue()
        assertThat(result.warnings).isNotEmpty()
        assertThat(result.methods.map { it.name }).containsExactly("echo")
    }

    @Test
    fun `an infinite loop is skipped, not run`() {
        val result = executor.executeAndCapture("while (true) {}\n$validContributor", "loop.gdsl")

        assertThat(result.methods.map { it.name }).containsExactly("echo")
        assertThat(result.warnings).anyMatch { it.contains("WhileStatement") }
    }

    @Test
    fun `Grab is not resolved`() {
        val script = "@Grab('com.example.untrusted:never-downloaded:1.0')\n" +
            "import com.example.Missing\n$validContributor"

        val result = executor.executeAndCapture(script, "grab.gdsl")

        assertThat(result.methods.map { it.name }).containsExactly("echo")
    }

    @Test
    fun `global AST transformations do not run while reading GDSL`() {
        val transformMarker = tempDir.resolve("transform-marker")
        System.setProperty(MarkerTransform.MARKER_PROPERTY, transformMarker.toString())
        try {
            // Control: a real compilation runs the test transformation.
            GroovyShell().parse("1")
            assertThat(Files.exists(transformMarker))
                .describedAs("control: the marker transform is registered")
                .isTrue()
            Files.delete(transformMarker)

            executor.executeAndCapture(validContributor, "transform.gdsl")

            assertThat(Files.exists(transformMarker)).describedAs("a compile phase ran while reading GDSL").isFalse()
        } finally {
            System.clearProperty(MarkerTransform.MARKER_PROPERTY)
        }
    }

    @Test
    fun `deep bracket nesting is rejected before parsing`() {
        val depth = 100_000
        val script = "def x = " + "[".repeat(depth) + "]".repeat(depth)

        val result = executor.executeAndCapture(script, "deep.gdsl")

        assertThat(result.success).isFalse()
        assertThat(result.error).contains("nested deeper")
    }

    @Test
    fun `a long expression chain does not crash the reader`() {
        val script = "def x = " + List(100_000) { "1" }.joinToString(" + ") + "\n$validContributor"

        val result = executor.executeAndCapture(script, "chain.gdsl")

        // Either the parser overflows (caught and reported) or the chain is skipped as unsupported.
        if (result.success) {
            assertThat(result.methods.map { it.name }).containsExactly("echo")
        } else {
            assertThat(result.error).isNotBlank()
        }
    }

    @Test
    fun `an oversized script is rejected before parsing`() {
        val script = "// " + "x".repeat(5 * 1024 * 1024)

        val result = executor.executeAndCapture(script, "huge.gdsl")

        assertThat(result.success).isFalse()
        assertThat(result.error).contains("larger than")
    }

    @Test
    fun `brackets inside strings and comments do not count as nesting`() {
        val script = "// " + "{".repeat(200) + "\n" + validContributor.replace("Print Message", "[".repeat(200))

        val result = executor.executeAndCapture(script, "strings.gdsl")

        assertThat(result.success).isTrue()
        assertThat(result.methods.single().documentation).isEqualTo("[".repeat(200))
    }
}
