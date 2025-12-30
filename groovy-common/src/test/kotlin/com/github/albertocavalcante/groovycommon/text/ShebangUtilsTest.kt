package com.github.albertocavalcante.groovycommon.text

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShebangUtilsTest {

    @Test
    fun `should replace shebang with empty line`() {
        val source = "#!/usr/bin/env groovy\nprintln 'hello'"
        val expected = "\nprintln 'hello'"
        assertEquals(expected, ShebangUtils.replaceShebangWithEmptyLine(source))
    }

    @Test
    fun `should replace shebang with empty line and preserve other lines`() {
        val source = """
            #!/usr/bin/env groovy
            
            println 'hello'
        """.trimIndent()

        val expected = "\n\nprintln 'hello'"
        assertEquals(expected, ShebangUtils.replaceShebangWithEmptyLine(source))
    }

    @Test
    fun `should do nothing if no shebang is present`() {
        val source = "println 'hello'"
        assertEquals(source, ShebangUtils.replaceShebangWithEmptyLine(source))
    }

    @Test
    fun `should extract shebang and content`() {
        val source = "#!/usr/bin/env groovy\nprintln 'hello'"
        val result = ShebangUtils.extractShebang(source)

        assertEquals("#!/usr/bin/env groovy\n", result.shebang)
        assertEquals("println 'hello'", result.content)
    }

    @Test
    fun `should extract shebang from shebang-only file`() {
        // Edge case mentioned in review
        val source = "#!/usr/bin/env groovy"
        val result = ShebangUtils.extractShebang(source)

        assertEquals("#!/usr/bin/env groovy\n", result.shebang)
        assertEquals("", result.content)
    }

    @Test
    fun `should return null shebang if not present`() {
        val source = "println 'hello'"
        val result = ShebangUtils.extractShebang(source)

        assertNull(result.shebang)
        assertEquals(source, result.content)
    }

    @Test
    fun `should normalize CRLF in shebang`() {
        val source = "#!/usr/bin/env groovy\r\nprintln 'hello'"
        val result = ShebangUtils.extractShebang(source)

        assertEquals("#!/usr/bin/env groovy\n", result.shebang)
        assertEquals("println 'hello'", result.content)
    }
}
