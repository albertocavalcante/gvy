package com.github.albertocavalcante.groovytesting.api

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode

/**
 * Interface for test framework detectors.
 *
 * Implementations detect test classes and extract test methods for a specific
 * test framework (Spock, JUnit 5, JUnit 4, TestNG, etc.).
 *
 * Each detector is responsible for:
 * 1. Determining if a class belongs to its framework ([appliesTo])
 * 2. Extracting test items from the class ([extractTests])
 */
interface TestFrameworkDetector {
    /**
     * The test framework this detector handles.
     */
    val framework: TestFramework

    /**
     * Checks if this detector applies to the given class.
     *
     * @param classNode The class to check.
     * @param module Optional ModuleNode for import-aware checks.
     * @param classLoader Optional ClassLoader for classpath-aware checks.
     * @return true if this detector should handle the class.
     */
    fun appliesTo(classNode: ClassNode, module: ModuleNode? = null, classLoader: ClassLoader? = null): Boolean

    /**
     * Extracts test items from a class.
     *
     * Should only be called if [appliesTo] returns true.
     *
     * @param classNode The test class to extract tests from.
     * @return List of discovered test items (class + methods).
     */
    fun extractTests(classNode: ClassNode): List<TestItem>
}
