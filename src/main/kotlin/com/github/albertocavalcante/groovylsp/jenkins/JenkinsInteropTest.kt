package com.github.albertocavalcante.groovylsp.jenkins

import org.slf4j.LoggerFactory

/**
 * Kotlin class that demonstrates interop with Groovy.
 * This class uses the SimpleGdslParser Groovy class to test the integration.
 */
class JenkinsInteropTest {

    companion object {
        private val logger = LoggerFactory.getLogger(JenkinsInteropTest::class.java)
    }

    /**
     * Tests the Groovy-Kotlin interop by calling Groovy methods from Kotlin.
     */
    @Suppress("TooGenericExceptionCaught") // Test method needs to catch all interop errors
    fun testGroovyInterop(): InteropTestResult {
        logger.info("Testing Groovy-Kotlin interop")

        try {
            // Test 1: Call static method
            val testResult = SimpleGdslParser.getTestResult()
            logger.debug("Groovy test result: $testResult")

            // Test 2: Parse method names
            val sampleGdsl = """
                contribute(context(ctype: 'hudson.model.Job')) {
                    method(name: 'build', type: 'Object', params: [:])
                    method(name: 'sh', type: 'Object', params: [script: 'String'])
                }
            """.trimIndent()

            val methodNames = SimpleGdslParser.parseMethodNames(sampleGdsl)
            logger.debug("Parsed method names: $methodNames")

            // Test 3: Validate GDSL
            val isValid = SimpleGdslParser.isValidGdsl(sampleGdsl)
            logger.debug("GDSL is valid: $isValid")

            return InteropTestResult(
                success = true,
                groovyResult = testResult,
                parsedMethods = methodNames,
                isValidGdsl = isValid,
                message = "Groovy-Kotlin interop working successfully",
            )
        } catch (e: Exception) {
            logger.error("Groovy-Kotlin interop test failed", e)
            return InteropTestResult(
                success = false,
                message = "Interop failed: ${e.message}",
                error = e,
            )
        }
    }

    /**
     * Demonstrates that Kotlin can call Groovy methods with proper type handling.
     */
    fun demonstrateTypeInterop(): List<String> {
        // Call Groovy method that returns a List<String>
        val gdslInput = """
            method(name: 'echo', type: 'void')
            method(name: 'sh', type: 'Object')
            method(name: 'build', type: 'hudson.model.Run')
        """.trimIndent()

        return SimpleGdslParser.parseMethodNames(gdslInput)
    }
}

/**
 * Result of the interop test.
 */
data class InteropTestResult(
    val success: Boolean,
    val groovyResult: Map<String, Any?>? = null,
    val parsedMethods: List<String>? = null,
    val isValidGdsl: Boolean? = null,
    val message: String,
    val error: Throwable? = null,
)
