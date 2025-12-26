package com.github.albertocavalcante.groovyspock

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpockDetectorAstTest {

    private val parser = GroovyParserFacade(SpockDetectorAstTest::class.java.classLoader)

    @Test
    fun `detects spock spec when class extends fully qualified specification`() {
        val uri = URI.create("file:///src/test/groovy/com/example/FooSpec.groovy")
        val content = "class FooSpec extends spock.lang.Specification {}"

        val result = parser.parse(ParseRequest(uri = uri, content = content))

        assertTrue(result.isSuccessful)
        assertTrue(SpockDetector.isSpockSpec(uri, result))
    }

    @Test
    fun `detects spock spec when class extends Specification with spock import`() {
        val uri = URI.create("file:///src/test/groovy/com/example/FooSpec.groovy")
        val content =
            """
            import spock.lang.Specification

            class FooSpec extends Specification {}
            """.trimIndent()

        val result = parser.parse(ParseRequest(uri = uri, content = content))

        assertTrue(result.isSuccessful)
        assertTrue(SpockDetector.isSpockSpec(uri, result))
    }

    @Test
    fun `detects spock spec when class extends Specification with spock star import`() {
        val uri = URI.create("file:///src/test/groovy/com/example/FooSpec.groovy")
        val content =
            """
            import spock.lang.*

            class FooSpec extends Specification {}
            """.trimIndent()

        val result = parser.parse(ParseRequest(uri = uri, content = content))

        assertTrue(result.isSuccessful)
        assertTrue(SpockDetector.isSpockSpec(uri, result))
    }

    @Test
    fun `does not detect spock spec when class extends other Specification type`(@TempDir workspaceDir: Path) {
        val otherSpec =
            workspaceDir.resolve("Specification.groovy").also { path ->
                path.writeText(
                    """
                    package com.example

                    class Specification {}
                    """.trimIndent(),
                )
            }

        val uri = URI.create("file:///src/test/groovy/com/example/FooSpec.groovy")
        val content =
            """
            import com.example.Specification

            class FooSpec extends Specification {}
            """.trimIndent()

        val result =
            parser.parse(
                ParseRequest(
                    uri = uri,
                    content = content,
                    workspaceSources = listOf(otherSpec),
                ),
            )

        assertTrue(result.isSuccessful)
        assertFalse(SpockDetector.isSpockSpec(uri, result))
    }

    @Test
    fun `falls back to filename heuristic when source does not reference spock`() {
        val uri = URI.create("file:///src/test/groovy/com/example/NoSpockSpec.groovy")
        val content =
            """
            class NoSpockSpec {
                void broken( {
            }
            """.trimIndent()

        val result = parser.parse(ParseRequest(uri = uri, content = content))

        assertFalse(result.isSuccessful)
        assertTrue(SpockDetector.isSpockSpec(uri, result))
    }
}
