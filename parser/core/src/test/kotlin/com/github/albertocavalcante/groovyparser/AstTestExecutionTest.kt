package com.github.albertocavalcante.groovyparser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertFalse

/** The core engine compiles with the context class loader, where `groovy-test` is visible on the test classpath. */
class AstTestExecutionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `ASTTest closure does not run when parsing`() {
        val marker = tempDir.resolve("pwned")
        val code = """
            @groovy.transform.ASTTest(phase=org.codehaus.groovy.control.CompilePhase.CONVERSION,
                value={ new File('${marker.toString().replace("\\", "/")}').text = 'x' })
            class A {}
        """.trimIndent()

        GroovyParser(ParserConfiguration()).parse(code)

        assertFalse(marker.exists(), "@ASTTest closure executed while parsing")
    }
}
