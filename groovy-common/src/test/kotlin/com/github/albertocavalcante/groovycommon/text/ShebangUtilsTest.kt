package com.github.albertocavalcante.groovycommon.text

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShebangUtilsTest {

    @Test
    fun shouldReplaceShebangWithEmptyLine() {
        val source = "#!/usr/bin/env groovy\nprintln 'hello'"
        val expected = "\nprintln 'hello'"
        assertEquals(expected, ShebangUtils.replaceShebangWithEmptyLine(source))
    }

    @Test
    fun shouldReplaceShebangWithEmptyLineAndPreserveOtherLines() {
        val source = """
            #!/usr/bin/env groovy
            
            println 'hello'
        """.trimIndent()

        val expected = "\n\nprintln 'hello'"
        assertEquals(expected, ShebangUtils.replaceShebangWithEmptyLine(source))
    }

    @Test
    fun shouldDoNothingIfNoShebangIsPresent() {
        val source = "println 'hello'"
        assertEquals(source, ShebangUtils.replaceShebangWithEmptyLine(source))
    }

    @Test
    fun shouldExtractShebangAndContent() {
        val source = "#!/usr/bin/env groovy\nprintln 'hello'"
        val result = ShebangUtils.extractShebang(source)

        assertEquals("#!/usr/bin/env groovy\n", result.shebang)
        assertEquals("println 'hello'", result.content)
    }

    @Test
    fun shouldExtractShebangFromShebangOnlyFile() {
        // Edge case mentioned in review
        val source = "#!/usr/bin/env groovy"
        val result = ShebangUtils.extractShebang(source)

        assertEquals("#!/usr/bin/env groovy\n", result.shebang)
        assertEquals("", result.content)
    }

    @Test
    fun shouldReturnNullShebangIfNotPresent() {
        val source = "println 'hello'"
        val result = ShebangUtils.extractShebang(source)

        assertNull(result.shebang)
        assertEquals(source, result.content)
    }

    @Test
    fun shouldNormalizeCrlfInShebang() {
        val source = "#!/usr/bin/env groovy\r\nprintln 'hello'"
        val result = ShebangUtils.extractShebang(source)

        assertEquals("#!/usr/bin/env groovy\n", result.shebang)
        assertEquals("println 'hello'", result.content)
    }
}
