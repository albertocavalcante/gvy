package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ResolutionNodeUtilsTest {

    @Test
    fun `getClassName strips member from static import`() {
        val parser = GroovyParserFacade()
        val content = """
            import static com.example.Util.helper

            class Example {
            }
        """.trimIndent()

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///Example.groovy"),
                content = content,
            ),
        )

        val importNode = result.ast?.staticImports?.values?.firstOrNull()
        assertNotNull(importNode)
        assertEquals("helper", importNode.fieldName)
        assertEquals("com.example.Util", getClassName(importNode))
    }
}
