package com.github.albertocavalcante.groovyjenkins

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for @Library annotation parsing from Groovy AST.
 */
class LibraryParserTest {

    @Test
    fun `should parse simple @Library annotation`() {
        val jenkinsfile = """
            @Library('pipeline-library')
            import com.example.Pipeline
            
            node {
                echo 'hello'
            }
        """.trimIndent()

        val parser = LibraryParser()
        val libraries = parser.parseLibraries(jenkinsfile)

        assertEquals(1, libraries.size)
        assertEquals("pipeline-library", libraries[0].name)
        assertEquals(null, libraries[0].version)
    }

    @Test
    fun `should parse @Library with version`() {
        val jenkinsfile = """
            @Library('pipeline-library@1.0.0')
            import com.example.Pipeline
            
            node {
                echo 'hello'
            }
        """.trimIndent()

        val parser = LibraryParser()
        val libraries = parser.parseLibraries(jenkinsfile)

        assertEquals(1, libraries.size)
        assertEquals("pipeline-library", libraries[0].name)
        assertEquals("1.0.0", libraries[0].version)
    }

    @Test
    fun `should parse multiple @Library annotations`() {
        val jenkinsfile = """
            @Library(['lib1@1.0', 'lib2'])
            import com.example.*
            
            node {
                echo 'hello'
            }
        """.trimIndent()

        val parser = LibraryParser()
        val libraries = parser.parseLibraries(jenkinsfile)

        assertEquals(2, libraries.size)
        assertEquals("lib1", libraries[0].name)
        assertEquals("1.0", libraries[0].version)
        assertEquals("lib2", libraries[1].name)
        assertEquals(null, libraries[1].version)
    }

    @Test
    fun `should parse @Library with underscore syntax`() {
        val jenkinsfile = """
            @Library('utils') _
            
            node {
                echo 'hello'
            }
        """.trimIndent()

        val parser = LibraryParser()
        val libraries = parser.parseLibraries(jenkinsfile)

        assertEquals(1, libraries.size)
        assertEquals("utils", libraries[0].name)
    }

    @Test
    fun `should parse library step syntax`() {
        val jenkinsfile = """
            library 'mylib@master'
            
            node {
                echo 'hello'
            }
        """.trimIndent()

        val parser = LibraryParser()
        val libraries = parser.parseLibraries(jenkinsfile)

        assertEquals(1, libraries.size)
        assertEquals("mylib", libraries[0].name)
        assertEquals("master", libraries[0].version)
    }

    @Test
    fun `should handle no libraries`() {
        val jenkinsfile = """
            node {
                echo 'hello'
            }
        """.trimIndent()

        val parser = LibraryParser()
        val libraries = parser.parseLibraries(jenkinsfile)

        assertTrue(libraries.isEmpty())
    }

    @Test
    fun `should parse mixed annotation and step syntax`() {
        val jenkinsfile = """
            @Library('lib1@1.0')
            import com.example.*
            
            library 'lib2@2.0'
            
            node {
                echo 'hello'
            }
        """.trimIndent()

        val parser = LibraryParser()
        val libraries = parser.parseLibraries(jenkinsfile)

        assertEquals(2, libraries.size)
        assertTrue(libraries.any { it.name == "lib1" && it.version == "1.0" })
        assertTrue(libraries.any { it.name == "lib2" && it.version == "2.0" })
    }
}

/**
 * Tests for SharedLibraryResolver that maps library names to configured jars.
 */
class SharedLibraryResolverTest {

    @Test
    fun `should resolve library to configured jar`() {
        val config = JenkinsConfiguration(
            sharedLibraries = listOf(
                SharedLibrary("pipeline-library", "/path/to/lib.jar", "/path/to/lib-sources.jar"),
            ),
        )

        val resolver = SharedLibraryResolver(config)
        val resolved = resolver.resolve(LibraryReference("pipeline-library", null))

        assertNotNull(resolved)
        assertEquals("pipeline-library", resolved.name)
        assertEquals("/path/to/lib.jar", resolved.jar)
        assertEquals("/path/to/lib-sources.jar", resolved.sourcesJar)
    }

    @Test
    fun `should resolve library ignoring version when not configured`() {
        val config = JenkinsConfiguration(
            sharedLibraries = listOf(
                SharedLibrary("mylib", "/path/to/mylib.jar"),
            ),
        )

        val resolver = SharedLibraryResolver(config)
        val resolved = resolver.resolve(LibraryReference("mylib", "1.0.0"))

        assertNotNull(resolved)
        assertEquals("mylib", resolved.name)
        assertEquals("/path/to/mylib.jar", resolved.jar)
    }

    @Test
    fun `should return null for missing library`() {
        val config = JenkinsConfiguration(
            sharedLibraries = listOf(
                SharedLibrary("lib1", "/path/to/lib1.jar"),
            ),
        )

        val resolver = SharedLibraryResolver(config)
        val resolved = resolver.resolve(LibraryReference("missing-lib", null))

        assertEquals(null, resolved)
    }

    @Test
    fun `should resolve multiple libraries`() {
        val config = JenkinsConfiguration(
            sharedLibraries = listOf(
                SharedLibrary("lib1", "/path/to/lib1.jar"),
                SharedLibrary("lib2", "/path/to/lib2.jar", "/path/to/lib2-sources.jar"),
            ),
        )

        val resolver = SharedLibraryResolver(config)

        val refs = listOf(
            LibraryReference("lib1", null),
            LibraryReference("lib2", "1.0"),
        )

        val resolved = resolver.resolveAll(refs)

        assertEquals(2, resolved.size)
        assertTrue(resolved.any { it.name == "lib1" })
        assertTrue(resolved.any { it.name == "lib2" })
    }

    @Test
    fun `should handle partially missing libraries`() {
        val config = JenkinsConfiguration(
            sharedLibraries = listOf(
                SharedLibrary("lib1", "/path/to/lib1.jar"),
            ),
        )

        val resolver = SharedLibraryResolver(config)

        val refs = listOf(
            LibraryReference("lib1", null),
            LibraryReference("missing", null),
        )

        val result = resolver.resolveAllWithWarnings(refs)

        assertEquals(1, result.resolved.size)
        assertEquals("lib1", result.resolved[0].name)
        assertEquals(1, result.missing.size)
        assertEquals("missing", result.missing[0].name)
    }
}
