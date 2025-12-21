package com.github.albertocavalcante.groovyjenkins.gdsl

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for GdslParser - parses Jenkins GDSL output into structured metadata.
 *
 * TDD RED phase: These tests define expected behavior before implementation.
 */
class GdslParserTest {

    @Test
    fun `parses simple method with single param`() {
        val gdsl = """
            method(name: 'echo', type: 'Object', params: [message:'java.lang.String'], doc: 'Print Message')
        """.trimIndent()

        val result = GdslParser.parse(gdsl)

        assertEquals(1, result.steps.size)
        val echo = result.steps[0]
        assertEquals("echo", echo.name)
        assertEquals("Print Message", echo.documentation)
        assertEquals(1, echo.parameters.size)
        assertEquals("java.lang.String", echo.parameters["message"])
        assertFalse(echo.requiresNode)
    }

    @Test
    fun `parses method with no params`() {
        val gdsl = """
            method(name: 'isUnix', type: 'Object', params: [:], doc: 'Checks if running on a Unix-like node')
        """.trimIndent()

        val result = GdslParser.parse(gdsl)

        assertEquals(1, result.steps.size)
        val isUnix = result.steps[0]
        assertEquals("isUnix", isUnix.name)
        assertTrue(isUnix.parameters.isEmpty())
    }

    @Test
    fun `parses method with namedParams`() {
        val gdsl = """
            method(name: 'sh', type: 'Object', namedParams: [parameter(name: 'script', type: 'java.lang.String'), parameter(name: 'returnStdout', type: 'boolean'), ], doc: 'Shell Script')
        """.trimIndent()

        val result = GdslParser.parse(gdsl)

        assertEquals(1, result.steps.size)
        val sh = result.steps[0]
        assertEquals("sh", sh.name)
        assertEquals(2, sh.namedParameters.size)
        assertEquals("java.lang.String", sh.namedParameters["script"])
        assertEquals("boolean", sh.namedParameters["returnStdout"])
    }

    @Test
    fun `parses method with both params and namedParams`() {
        val gdsl = """
            method(name: 'timeout', type: 'Object', params: [body:Closure], namedParams: [parameter(name: 'time', type: 'int'), parameter(name: 'unit', type: 'java.util.concurrent.TimeUnit'), ], doc: 'Enforce time limit')
        """.trimIndent()

        val result = GdslParser.parse(gdsl)

        assertEquals(1, result.steps.size)
        val timeout = result.steps[0]
        assertEquals("timeout", timeout.name)
        assertEquals(1, timeout.parameters.size)
        assertTrue(timeout.parameters.containsKey("body"))
        assertEquals(2, timeout.namedParameters.size)
        assertTrue(timeout.takesBlock)
    }

    @Test
    fun `identifies steps in node context as requiring node`() {
        val gdsl = """
            def nodeCtx = context(scope: closureScope())
            contributor(nodeCtx) {
                def call = enclosingCall('node')
                if (call) {
                    method(name: 'sh', type: 'Object', params: [script:'java.lang.String'], doc: 'Shell Script')
                    method(name: 'bat', type: 'Object', params: [script:'java.lang.String'], doc: 'Windows Batch Script')
                }
            }
        """.trimIndent()

        val result = GdslParser.parse(gdsl)

        assertEquals(2, result.steps.size)
        assertTrue(result.steps.all { it.requiresNode })
    }

    @Test
    fun `identifies steps in script context as not requiring node`() {
        val gdsl = """
            def ctx = context(scope: scriptScope())
            contributor(ctx) {
                method(name: 'echo', type: 'Object', params: [message:'java.lang.String'], doc: 'Print Message')
            }
        """.trimIndent()

        val result = GdslParser.parse(gdsl)

        assertEquals(1, result.steps.size)
        assertFalse(result.steps[0].requiresNode)
    }

    @Test
    fun `parses property declarations as global variables`() {
        val gdsl = """
            property(name: 'env', type: 'org.jenkinsci.plugins.workflow.cps.EnvActionImpl')
            property(name: 'params', type: 'org.jenkinsci.plugins.workflow.cps.ParamsVariable')
            property(name: 'currentBuild', type: 'org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper')
        """.trimIndent()

        val result = GdslParser.parse(gdsl)

        assertEquals(3, result.globalVariables.size)
        assertNotNull(result.globalVariables.find { it.name == "env" })
        assertNotNull(result.globalVariables.find { it.name == "params" })
        assertNotNull(result.globalVariables.find { it.name == "currentBuild" })
        assertEquals(
            "org.jenkinsci.plugins.workflow.cps.EnvActionImpl",
            result.globalVariables.find { it.name == "env" }?.type,
        )
    }

    @Test
    fun `handles escaped quotes in documentation`() {
        val gdsl = """
            method(name: 'echo', type: 'Object', params: [message:'String'], doc: 'Print a \'message\' to console')
        """.trimIndent()

        val result = GdslParser.parse(gdsl)

        assertEquals(1, result.steps.size)
        assertEquals("Print a 'message' to console", result.steps[0].documentation)
    }

    @Test
    fun `parses multiple method overloads for same step`() {
        val gdsl = """
            method(name: 'sleep', type: 'Object', params: [time:'int'], doc: 'Sleep')
            method(name: 'sleep', type: 'Object', namedParams: [parameter(name: 'time', type: 'int'), parameter(name: 'unit', type: 'java.util.concurrent.TimeUnit'), ], doc: 'Sleep')
        """.trimIndent()

        val result = GdslParser.parse(gdsl)

        // Should have 2 entries for sleep (different overloads)
        assertEquals(2, result.steps.size)
        assertTrue(result.steps.all { it.name == "sleep" })
    }

    @Test
    fun `detects steps that take closures as blocks`() {
        val gdsl = """
            method(name: 'node', type: 'Object', params: [label:'java.lang.String', body:Closure], doc: 'Allocate node')
            method(name: 'dir', type: 'Object', params: [path:'java.lang.String', body:Closure], doc: 'Change directory')
            method(name: 'echo', type: 'Object', params: [message:'String'], doc: 'Print')
        """.trimIndent()

        val result = GdslParser.parse(gdsl)

        val node = result.steps.find { it.name == "node" }
        val dir = result.steps.find { it.name == "dir" }
        val echo = result.steps.find { it.name == "echo" }

        assertNotNull(node)
        assertNotNull(dir)
        assertNotNull(echo)

        assertTrue(node.takesBlock)
        assertTrue(dir.takesBlock)
        assertFalse(echo.takesBlock)
    }

    @Test
    fun `ignores error comments at end of GDSL`() {
        val gdsl = """
            method(name: 'echo', type: 'Object', params: [message:'String'], doc: 'Print')

            // Errors on:
            // class org.jenkinsci.plugins.workflow.cps.steps.ParallelStep: null
        """.trimIndent()

        val result = GdslParser.parse(gdsl)

        assertEquals(1, result.steps.size)
        assertEquals("echo", result.steps[0].name)
    }

    @Test
    fun `parses real Jenkins GDSL sample`() {
        val gdsl = javaClass.getResourceAsStream("/sample-gdsl.groovy")?.bufferedReader()?.readText()

        // Skip if resource not found
        if (gdsl == null) {
            println("Skipping test - sample-gdsl.groovy not found")
            return
        }

        val result = GdslParser.parse(gdsl)

        // Should have many steps
        assertTrue(result.steps.isNotEmpty(), "Should parse at least some steps")

        // Should have global variables
        assertTrue(result.globalVariables.isNotEmpty(), "Should parse global variables")

        // Should identify some steps as requiring node
        assertTrue(result.steps.any { it.requiresNode }, "Should have node-requiring steps")
        assertTrue(result.steps.any { !it.requiresNode }, "Should have non-node steps")

        // Check specific steps
        val sh = result.steps.find { it.name == "sh" }
        assertNotNull(sh, "Should have sh step")
        assertTrue(sh.requiresNode, "sh should require node")

        val echo = result.steps.find { it.name == "echo" }
        assertNotNull(echo, "Should have echo step")
        assertFalse(echo.requiresNode, "echo should not require node")
    }

    @Test
    fun `merges overloads into single step with all parameters`() {
        val gdsl = """
            method(name: 'sh', type: 'Object', params: [script:'java.lang.String'], doc: 'Shell Script')
            method(name: 'sh', type: 'Object', namedParams: [parameter(name: 'script', type: 'java.lang.String'), parameter(name: 'returnStdout', type: 'boolean'), ], doc: 'Shell Script')
        """.trimIndent()

        val result = GdslParser.parseMerged(gdsl)

        // Merged should have only 1 entry for sh
        assertEquals(1, result.steps.size)
        val sh = result.steps["sh"]
        assertNotNull(sh)
        // Should have all parameters from both overloads
        assertTrue(sh.parameters.containsKey("script"))
        assertTrue(sh.namedParameters.containsKey("returnStdout"))
    }
}
