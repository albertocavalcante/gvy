package com.github.albertocavalcante.groovylsp.jenkins

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests that verify the complete Jenkins interop functionality
 * from end-to-end, including Kotlin-Groovy interaction, performance under load,
 * and real-world usage scenarios.
 */
class JenkinsInteropIntegrationTest {

    private lateinit var jenkinsInterop: JenkinsInteropTest

    @BeforeEach
    fun setUp() {
        jenkinsInterop = JenkinsInteropTest()
    }

    @Test
    fun `should successfully complete full interop workflow`() {
        // Test the complete workflow from Kotlin -> Groovy -> back to Kotlin
        val result = jenkinsInterop.testGroovyInterop()

        assertAll(
            "Full interop workflow verification",
            { assertTrue(result.success, "Interop should succeed") },
            { assertNotNull(result.groovyResult, "Should get Groovy result") },
            { assertNotNull(result.parsedMethods, "Should get parsed methods") },
            { assertEquals(true, result.isValidGdsl, "Should validate GDSL") },
            { assertContains(result.message, "success", ignoreCase = true) },
        )

        // Verify the Groovy result structure
        val groovyResult = result.groovyResult!!
        assertAll(
            "Groovy result structure",
            { assertEquals("Groovy-Kotlin interop is working!", groovyResult["message"]) },
            { assertEquals("SimpleGdslParser", groovyResult["parser"]) },
            { assertEquals("1.0", groovyResult["version"]) },
            { assertTrue(groovyResult["timestamp"] is Long) },
        )

        // Verify parsed methods
        val parsedMethods = result.parsedMethods!!
        assertAll(
            "Parsed methods verification",
            { assertEquals(2, parsedMethods.size) },
            { assertTrue(parsedMethods.contains("build")) },
            { assertTrue(parsedMethods.contains("sh")) },
        )
    }

    @Test
    fun `should handle type interop consistently across multiple calls`() {
        // Test that type conversion is stable across multiple invocations
        val results = mutableListOf<List<String>>()

        repeat(10) {
            val methods = jenkinsInterop.demonstrateTypeInterop()
            results.add(methods)
        }

        // All results should be identical
        val firstResult = results.first()
        results.forEach { result ->
            assertEquals(firstResult, result, "All type interop results should be identical")
        }

        // Verify the expected methods are present
        assertAll(
            "Type interop consistency",
            { assertEquals(3, firstResult.size) },
            { assertTrue(firstResult.contains("echo")) },
            { assertTrue(firstResult.contains("sh")) },
            { assertTrue(firstResult.contains("build")) },
        )
    }

    @Test
    fun `should maintain thread safety in concurrent integration scenarios`() = runTest {
        val concurrencyLevel = 50
        val resultsMap = ConcurrentHashMap<Int, InteropTestResult>()

        // Launch concurrent integration tests
        val jobs = (1..concurrencyLevel).map { taskId ->
            async {
                val result = jenkinsInterop.testGroovyInterop()
                resultsMap[taskId] = result
                taskId to result
            }
        }

        val completedJobs = jobs.awaitAll()

        // Verify all jobs completed successfully
        assertEquals(concurrencyLevel, completedJobs.size)
        assertEquals(concurrencyLevel, resultsMap.size)

        // Verify consistency across all results
        val firstResult = completedJobs.first().second
        completedJobs.forEach { (taskId, result) ->
            assertAll(
                "Concurrent result $taskId verification",
                { assertEquals(firstResult.success, result.success) },
                { assertEquals(firstResult.parsedMethods?.size, result.parsedMethods?.size) },
                { assertEquals(firstResult.isValidGdsl, result.isValidGdsl) },
                { assertNotNull(result.groovyResult) },
                { assertTrue(result.message.contains("success", ignoreCase = true)) },
            )
        }
    }

    @Test
    fun `should handle realistic Jenkins pipeline GDSL integration`() {
        val realisticJenkinsPipeline = createRealisticJenkinsPipelineGdsl()
        val parsedMethods = SimpleGdslParser.parseMethodNames(realisticJenkinsPipeline).toSet()
        val isValid = SimpleGdslParser.isValidGdsl(realisticJenkinsPipeline)

        verifyJenkinsPipelineGdslParsing(isValid, parsedMethods)
    }

    private fun createRealisticJenkinsPipelineGdsl(): String = """
        // Jenkins Declarative Pipeline GDSL
        contribute(context(ctype: 'org.jenkinsci.plugins.workflow.cps.CpsScript')) {
            // Core pipeline structure
            method(name: 'pipeline', type: 'void', params: [body: 'Closure'])

            // Agent specification
            method(name: 'agent', type: 'void', params: [spec: 'String'])

            // Build stages
            method(name: 'stages', type: 'void', params: [body: 'Closure'])
            method(name: 'stage', type: 'void', params: [name: 'String', body: 'Closure'])
            method(name: 'steps', type: 'void', params: [body: 'Closure'])

            // Step implementations
            method(name: 'sh', type: 'Object', params: [script: 'String'])
            method(name: 'bat', type: 'Object', params: [script: 'String'])
            method(name: 'echo', type: 'void', params: [message: 'String'])
            method(name: 'dir', type: 'void', params: [path: 'String', body: 'Closure'])

            // SCM operations
            method(name: 'checkout', type: 'Object', params: [scm: 'Map'])
            method(name: 'git', type: 'Object', params: [url: 'String', branch: 'String'])

            // Build tools
            method(name: 'maven', type: 'Object', params: [goals: 'String'])
            method(name: 'gradle', type: 'Object', params: [tasks: 'String'])

            // Artifact management
            method(name: 'archiveArtifacts', type: 'void', params: [artifacts: 'String'])
            method(name: 'publishTestResults', type: 'void', params: [testResultsPattern: 'String'])
            method(name: 'stash', type: 'void', params: [name: 'String', includes: 'String'])
            method(name: 'unstash', type: 'void', params: [name: 'String'])

            // Parallel execution
            method(name: 'parallel', type: 'void', params: [branches: 'Map'])

            // Post-build actions
            method(name: 'post', type: 'void', params: [body: 'Closure'])
            method(name: 'always', type: 'void', params: [body: 'Closure'])
            method(name: 'success', type: 'void', params: [body: 'Closure'])
            method(name: 'failure', type: 'void', params: [body: 'Closure'])
            method(name: 'cleanup', type: 'void', params: [body: 'Closure'])
        }
    """.trimIndent()

    private fun verifyJenkinsPipelineGdslParsing(isValid: Boolean, parsedMethods: Set<String>) {
        assertAll(
            "Realistic Jenkins GDSL integration",
            { assertTrue(isValid, "Should recognize as valid GDSL") },
            { assertEquals(23, parsedMethods.size, "Should parse all Jenkins pipeline methods") },
            *createMethodAssertions(parsedMethods),
        )
    }

    private fun createMethodAssertions(methods: Set<String>) = arrayOf(
        // Core pipeline methods
        { assertTrue(methods.contains("pipeline")) },
        { assertTrue(methods.contains("agent")) },
        { assertTrue(methods.contains("stages")) },
        { assertTrue(methods.contains("stage")) },
        { assertTrue(methods.contains("steps")) },
        // Build steps
        { assertTrue(methods.contains("sh")) },
        { assertTrue(methods.contains("bat")) },
        { assertTrue(methods.contains("echo")) },
        // SCM methods
        { assertTrue(methods.contains("checkout")) },
        { assertTrue(methods.contains("git")) },
        // Build tools
        { assertTrue(methods.contains("maven")) },
        { assertTrue(methods.contains("gradle")) },
        // Artifacts and advanced features
        { assertTrue(methods.contains("archiveArtifacts")) },
        { assertTrue(methods.contains("stash")) },
        { assertTrue(methods.contains("unstash")) },
        { assertTrue(methods.contains("parallel")) },
        { assertTrue(methods.contains("post")) },
        { assertTrue(methods.contains("always")) },
        { assertTrue(methods.contains("success")) },
        { assertTrue(methods.contains("failure")) },
    )

    @Test
    fun `should handle complex shared library GDSL integration`() {
        // Test with Jenkins shared library GDSL pattern
        val sharedLibraryGdsl = """
            // Jenkins Shared Library GDSL
            contribute(context(ctype: 'org.jenkinsci.plugins.workflow.cps.CpsScript')) {
                // Custom shared library methods
                method(name: 'deployToEnvironment', type: 'void',
                       params: [environment: 'String', version: 'String', config: 'Map'])
                method(name: 'runTests', type: 'Object',
                       params: [testSuite: 'String', parallel: 'Boolean'])
                method(name: 'notifyTeam', type: 'void',
                       params: [channel: 'String', message: 'String', color: 'String'])
                method(name: 'buildDockerImage', type: 'String',
                       params: [dockerfile: 'String', tag: 'String'])
                method(name: 'scanForVulnerabilities', type: 'Map',
                       params: [image: 'String', threshold: 'String'])
                method(name: 'deployToKubernetes', type: 'void',
                       params: [namespace: 'String', manifest: 'String'])
            }

            // Additional context for specific stages
            contribute(context(ctype: 'hudson.model.Job')) {
                method(name: 'setupEnvironment', type: 'Map')
                method(name: 'cleanup', type: 'void')
                method(name: 'rollback', type: 'void', params: [version: 'String'])
            }
        """.trimIndent()

        val parsedMethods = SimpleGdslParser.parseMethodNames(sharedLibraryGdsl).toSet()
        val isValid = SimpleGdslParser.isValidGdsl(sharedLibraryGdsl)

        assertAll(
            "Shared library GDSL integration",
            { assertTrue(isValid, "Should recognize complex shared library GDSL as valid") },
            { assertEquals(9, parsedMethods.size, "Should parse all shared library methods") },

            // Deployment methods
            { assertTrue(parsedMethods.contains("deployToEnvironment")) },
            { assertTrue(parsedMethods.contains("deployToKubernetes")) },

            // Testing methods
            { assertTrue(parsedMethods.contains("runTests")) },
            { assertTrue(parsedMethods.contains("scanForVulnerabilities")) },

            // Communication methods
            { assertTrue(parsedMethods.contains("notifyTeam")) },

            // Docker methods
            { assertTrue(parsedMethods.contains("buildDockerImage")) },

            // Environment management
            { assertTrue(parsedMethods.contains("setupEnvironment")) },
            { assertTrue(parsedMethods.contains("cleanup")) },
            { assertTrue(parsedMethods.contains("rollback")) },
        )
    }

    @Test
    fun `should maintain performance under integration load`() {
        val iterations = 100
        val startTime = System.currentTimeMillis()

        // Simulate high-frequency integration scenarios
        val results = mutableListOf<InteropTestResult>()
        repeat(iterations) {
            val result = jenkinsInterop.testGroovyInterop()
            results.add(result)

            // Also test type interop
            val typeResult = jenkinsInterop.demonstrateTypeInterop()
            assertEquals(3, typeResult.size, "Type interop should be consistent")
        }

        val duration = System.currentTimeMillis() - startTime

        // Performance assertions
        assertAll(
            "Integration performance",
            { assertTrue(duration < 10_000, "Should complete $iterations iterations in <10 seconds") },
            { assertEquals(iterations, results.size) },
            { assertTrue(results.all { it.success }, "All integration tests should succeed") },
        )

        // Verify consistency across all results
        val firstResult = results.first()
        results.forEach { result ->
            assertEquals(
                firstResult.parsedMethods?.size,
                result.parsedMethods?.size,
                "Method parsing should be consistent across iterations",
            )
        }
    }

    @Test
    fun `should handle error recovery in integration scenarios`() {
        // Test that integration continues to work even after encountering edge cases
        val edgeCaseInputs = listOf(
            null,
            "",
            "invalid GDSL content",
            "method(broken syntax",
            "contribute(malformed",
        )

        // Process edge cases
        edgeCaseInputs.forEach { input ->
            val result = SimpleGdslParser.parseMethodNames(input)
            assertNotNull(result, "Should always return a result, even for invalid input")
        }

        // Verify that normal operation continues after edge cases
        val normalResult = jenkinsInterop.testGroovyInterop()
        assertTrue(normalResult.success, "Normal operation should continue after edge cases")

        val typeResult = jenkinsInterop.demonstrateTypeInterop()
        assertEquals(3, typeResult.size, "Type interop should work after edge cases")
    }

    @Test
    fun `should support Jenkins plugin ecosystem patterns`() {
        // Test patterns common in Jenkins plugin ecosystem
        val pluginGdsl = """
            // Jenkins Plugin GDSL Patterns
            contribute(context(ctype: 'hudson.model.Job')) {
                // Docker plugin
                method(name: 'dockerBuild', type: 'Object', params: [image: 'String'])
                method(name: 'dockerPush', type: 'void', params: [image: 'String', registry: 'String'])

                // Kubernetes plugin
                method(name: 'kubernetesDeploy', type: 'void', params: [configs: 'String'])
                method(name: 'kubernetesApply', type: 'void', params: [file: 'String'])

                // SonarQube plugin
                method(name: 'sonarQubeScan', type: 'Object')
                method(name: 'waitForQualityGate', type: 'Object')

                // Slack plugin
                method(name: 'slackSend', type: 'void', params: [channel: 'String', message: 'String'])

                // Email plugin
                method(name: 'emailext', type: 'void', params: [to: 'String', subject: 'String', body: 'String'])

                // JUnit plugin
                method(name: 'junit', type: 'void', params: [testResults: 'String'])

                // Artifactory plugin
                method(name: 'artifactoryUpload', type: 'void', params: [spec: 'String'])
            }
        """.trimIndent()

        val parsedMethods = SimpleGdslParser.parseMethodNames(pluginGdsl).toSet()

        assertAll(
            "Jenkins plugin ecosystem support",
            { assertEquals(10, parsedMethods.size) },

            // Docker plugin methods
            { assertTrue(parsedMethods.contains("dockerBuild")) },
            { assertTrue(parsedMethods.contains("dockerPush")) },

            // Kubernetes plugin methods
            { assertTrue(parsedMethods.contains("kubernetesDeploy")) },
            { assertTrue(parsedMethods.contains("kubernetesApply")) },

            // Quality tools
            { assertTrue(parsedMethods.contains("sonarQubeScan")) },
            { assertTrue(parsedMethods.contains("waitForQualityGate")) },
            { assertTrue(parsedMethods.contains("junit")) },

            // Communication
            { assertTrue(parsedMethods.contains("slackSend")) },
            { assertTrue(parsedMethods.contains("emailext")) },

            // Artifact management
            { assertTrue(parsedMethods.contains("artifactoryUpload")) },
        )
    }

    @Test
    fun `should verify complete interop data flow integrity`() {
        // Test that data flows correctly through the entire Kotlin -> Groovy -> Kotlin pipeline
        val testResult = SimpleGdslParser.getTestResult()

        // Verify the data structure integrity
        assertAll(
            "Data flow integrity",
            { assertNotNull(testResult) },
            { assertEquals(4, testResult.size, "Result should have exactly 4 fields") },
            { assertTrue(testResult.containsKey("message")) },
            { assertTrue(testResult.containsKey("timestamp")) },
            { assertTrue(testResult.containsKey("parser")) },
            { assertTrue(testResult.containsKey("version")) },
        )

        // Test the full integration workflow multiple times
        repeat(5) { iteration ->
            val result = jenkinsInterop.testGroovyInterop()

            assertAll(
                "Integration iteration $iteration",
                { assertTrue(result.success) },
                { assertNotNull(result.groovyResult) },
                { assertEquals(testResult["message"], result.groovyResult!!["message"]) },
                { assertEquals(testResult["parser"], result.groovyResult!!["parser"]) },
                { assertEquals(testResult["version"], result.groovyResult!!["version"]) },
            )
        }
    }
}
