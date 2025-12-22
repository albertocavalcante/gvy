package com.github.albertocavalcante.groovytesting.registry

import com.github.albertocavalcante.groovytesting.api.TestFramework
import com.github.albertocavalcante.groovytesting.api.TestFrameworkDetector
import com.github.albertocavalcante.groovytesting.api.TestItem
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.slf4j.LoggerFactory

/**
 * Registry for test framework detectors.
 *
 * Provides a plugin-style architecture for registering and querying
 * test framework detectors. Allows the LSP to support multiple frameworks
 * without hard-coding dependencies.
 *
 * Usage:
 * ```
 * // Register detectors
 * TestFrameworkRegistry.register(SpockTestDetector())
 * TestFrameworkRegistry.register(JUnit5TestDetector())
 *
 * // Discover tests in a class
 * val tests = TestFrameworkRegistry.extractTests(classNode, module)
 * ```
 */
object TestFrameworkRegistry {
    private val logger = LoggerFactory.getLogger(TestFrameworkRegistry::class.java)
    private val detectors = mutableListOf<TestFrameworkDetector>()

    /**
     * Registers a test framework detector.
     *
     * @param detector The detector to register.
     */
    fun register(detector: TestFrameworkDetector) {
        logger.info("Registering test framework detector: ${detector.framework}")
        detectors.add(detector)
    }

    /**
     * Registers a detector only if one for the same framework isn't already registered.
     *
     * Idempotent - safe to call multiple times (e.g., from init blocks).
     *
     * @param detector The detector to register.
     * @return true if registered, false if already present.
     */
    fun registerIfAbsent(detector: TestFrameworkDetector): Boolean {
        if (detectors.any { it.framework == detector.framework }) {
            logger.debug("Detector for ${detector.framework} already registered, skipping")
            return false
        }
        register(detector)
        return true
    }

    /**
     * Unregisters all detectors. Useful for testing.
     */
    fun clear() {
        detectors.clear()
    }

    /**
     * Returns all registered detectors.
     */
    fun getDetectors(): List<TestFrameworkDetector> = detectors.toList()

    /**
     * Returns the detector for a specific framework, if registered.
     */
    fun getDetector(framework: TestFramework): TestFrameworkDetector? = detectors.find { it.framework == framework }

    /**
     * Finds the first detector that applies to the given class.
     *
     * @return The applicable detector, or null if none match.
     */
    fun findDetector(
        classNode: ClassNode,
        module: ModuleNode? = null,
        classLoader: ClassLoader? = null,
    ): TestFrameworkDetector? = detectors.find { it.appliesTo(classNode, module, classLoader) }

    /**
     * Extracts tests from a class using the appropriate detector.
     *
     * @return Test items if a detector applies, empty list otherwise.
     */
    fun extractTests(
        classNode: ClassNode,
        module: ModuleNode? = null,
        classLoader: ClassLoader? = null,
    ): List<TestItem> {
        val detector = findDetector(classNode, module, classLoader) ?: return emptyList()
        return detector.extractTests(classNode)
    }

    /**
     * Checks if any registered detector applies to the given class.
     */
    fun isTestClass(classNode: ClassNode, module: ModuleNode? = null, classLoader: ClassLoader? = null): Boolean =
        findDetector(classNode, module, classLoader) != null
}
