package com.github.albertocavalcante.groovylsp

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinimalTest {

    @Test
    fun `test basic invalid groovy compilation`() = runTest {
        val compilationService = TestUtils.createCompilationService()

        val invalidGroovyContent = """
            package test
            class TestClass {
                void badMethod( {
                    println "This has syntax errors"
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test/BadClass.groovy")
        val result = compilationService.compile(uri, invalidGroovyContent)

        println("Result success: ${result.isSuccess}")
        println("Diagnostics count: ${result.diagnostics.size}")

        assertFalse(result.isSuccess)
        assertTrue(result.diagnostics.isNotEmpty())
    }
}
