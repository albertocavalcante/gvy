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
    fun shouldParseSimpleLibraryAnnotation() {
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
    fun shouldParseLibraryWithVersion() {
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
    fun shouldParseMultipleLibraryAnnotations() {
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
    fun shouldParseLibraryWithUnderscoreSyntax() {
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
    fun shouldParseLibraryStepSyntax() {
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
    fun shouldHandleNoLibraries() {
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
    fun shouldParseMixedAnnotationAndStepSyntax() {
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
    fun shouldResolveLibraryToConfiguredJar() {
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
    fun shouldResolveLibraryIgnoringVersionWhenNotConfigured() {
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
    fun shouldReturnNullForMissingLibrary() {
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
    fun shouldResolveMultipleLibraries() {
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
    fun shouldHandlePartiallyMissingLibraries() {
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

class LibraryParserEdgeCasesTest {
    @Test
    fun shouldParseLibraryWithShebangPrefix() {
        val jenkinsfile = """
            #!/usr/bin/env groovy
            @Library('utils') _
            
            pipeline {
                agent any
            }
        """.trimIndent()

        val parser = LibraryParser()
        val libraries = parser.parseLibraries(jenkinsfile)

        assertEquals(1, libraries.size)
        assertEquals("utils", libraries[0].name)
    }

    @Test
    fun shouldParseLibraryWithSingleLineCommentPrefix() {
        val jenkinsfile = """
            // This is a comment
            // Another comment
            @Library('utils') _
            
            pipeline {
                agent any
            }
        """.trimIndent()

        val parser = LibraryParser()
        val libraries = parser.parseLibraries(jenkinsfile)

        assertEquals(1, libraries.size)
        assertEquals("utils", libraries[0].name)
    }

    @Test
    fun shouldParseLibraryWithMultilineCommentPrefix() {
        val jenkinsfile = """
            /*
             * Multi-line comment
             * Copyright 2024
             */
            @Library('utils') _
            
            pipeline {
                agent any
            }
        """.trimIndent()

        val parser = LibraryParser()
        val libraries = parser.parseLibraries(jenkinsfile)

        assertEquals(1, libraries.size)
        assertEquals("utils", libraries[0].name)
    }

    @Test
    fun shouldParseLibraryAfterPipelineBlock() {
        // This is valid syntax in Groovy script (local var in run method), so parser should find it.
        val jenkinsfile = """
            pipeline {
                agent any
            }
            @Library('utils') _
        """.trimIndent()

        val parser = LibraryParser()
        val libraries = parser.parseLibraries(jenkinsfile)

        // Updated expectation: The parser correctly finds this as a variable declaration.
        assertEquals(1, libraries.size)
        assertEquals("utils", libraries[0].name)
    }

    @Test
    fun shouldParseComplexRealWorldJenkinsfile() {
        // Based on npm-groovy-lint example
        val jenkinsfile = """
            #!groovy
            @Library('Utils_DXCO4SF@master') _ // Shared Library managed at https://example.com
            
            pipeline {
                agent { 
                    dockerfile {
                         args '-u 0:0' 
                    } 
                }
                stages {
                    stage('Test') {
                        steps { echo 'test' }
                    }
                }
            }
        """.trimIndent()

        val parser = LibraryParser()
        val libraries = parser.parseLibraries(jenkinsfile)

        assertEquals(1, libraries.size)
        assertEquals("Utils_DXCO4SF", libraries[0].name)
        assertEquals("master", libraries[0].version)
    }

    @Test
    fun shouldParseValidLibraryEvenIfAnotherIsInvalid() {
        // Valid library at top, invalid syntax at bottom
        val jenkinsfile = """
            @Library('valid-lib') _
            
            pipeline {
                agent any
            }
            
            @Library('invalid-syntax') // Missing variable declaration
        """.trimIndent()

        val parser = LibraryParser()
        val libraries = parser.parseLibraries(jenkinsfile)

        // We hope to recover the valid one despite the syntax error later in the file
        assertEquals(1, libraries.size)
        assertEquals("valid-lib", libraries[0].name)
    }
}
