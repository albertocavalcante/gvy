package com.github.albertocavalcante.groovytesting.registry

import com.github.albertocavalcante.groovytesting.api.TestFramework
import com.github.albertocavalcante.groovytesting.api.TestFrameworkDetector
import com.github.albertocavalcante.groovytesting.api.TestItem
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Registry for test framework detectors.
 *
 * Provides a plugin-style architecture for registering and querying
 * test framework detectors. Allows the LSP to support multiple frameworks
 * without hard-coding dependencies.
 *
 * Thread-safe implementation using [CopyOnWriteArrayList] for safe concurrent
 * registration and iteration.
 *
 * Usage:
 * ```
 * // Use the default shared instance
 * TestFrameworkRegistry.default.register(SpockTestDetector())
 * TestFrameworkRegistry.default.register(JUnit5TestDetector())
 *
 * // Discover tests in a class
 * val tests = TestFrameworkRegistry.default.extractTests(classNode, module)
 *
 * // Or create isolated instances for testing
 * val registry = TestFrameworkRegistry()
 * registry.register(SpockTestDetector())
 * ```
 */
class TestFrameworkRegistry {
    private val logger = KotlinLogging.logger {}
    private val detectors = CopyOnWriteArrayList<TestFrameworkDetector>()

    companion object {
        /**
         * Shared default instance for production use.
         *
         * This instance is shared across the entire application and is thread-safe.
         * For testing, consider creating isolated instances instead.
         */
        @JvmStatic
        val default = TestFrameworkRegistry()
    }

    /**
     * Registers a test framework detector.
     *
     * @param detector The detector to register.
     */
    fun register(detector: TestFrameworkDetector) {
        logger.info { "Registering test framework detector: ${detector.framework}" }
        detectors.add(detector)
    }

    /**
     * Registers a detector only if one for the same framework isn't already registered.
     *
     * Idempotent - safe to call multiple times (e.g., from init blocks).
     * Thread-safe via synchronization to prevent duplicate registration.
     *
     * @param detector The detector to register.
     * @return true if registered, false if already present.
     */
    @Synchronized
    fun registerIfAbsent(detector: TestFrameworkDetector): Boolean {
        if (detectors.any { it.framework == detector.framework }) {
            logger.debug { "Detector for ${detector.framework} already registered, skipping" }
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
