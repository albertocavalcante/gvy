package com.github.albertocavalcante.groovyspock

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.slf4j.LoggerFactory

/**
 * Extracts Spock feature methods from a ClassNode.
 *
 * A feature method is identified by having Spock block labels (given:, when:, then:, expect:, etc.)
 * in its body. This uses [SpockBlockIndex] to detect blocks.
 */
object SpockFeatureExtractor {
    private val logger = LoggerFactory.getLogger(SpockFeatureExtractor::class.java)

    /**
     * Extract all feature methods from a Spock specification class.
     *
     * @param classNode The ClassNode to analyze (should be a Spock specification)
     * @return List of [SpockFeatureMethod] representing the test methods
     */
    fun extractFeatures(classNode: ClassNode): List<SpockFeatureMethod> {
        val features = mutableListOf<SpockFeatureMethod>()

        for (method in classNode.methods) {
            if (isFeatureMethod(method)) {
                val featureName = extractFeatureName(method)
                val line = method.lineNumber.takeIf { it > 0 } ?: 1

                features.add(SpockFeatureMethod(name = featureName, line = line))
                logger.debug("Found feature method: {} at line {}", featureName, line)
            }
        }

        logger.debug("Extracted {} feature methods from {}", features.size, classNode.name)
        return features
    }

    /**
     * Determines if a method is a Spock feature method.
     *
     * A method is considered a feature method if:
     * 1. It's not static, not synthetic, and not a constructor
     * 2. It has at least one Spock block label (given:, when:, then:, expect:, etc.)
     */
    private fun isFeatureMethod(method: MethodNode): Boolean {
        // Skip special methods
        if (method.isStatic || method.isSynthetic) return false
        if (method.name in EXCLUDED_METHODS) return false

        // Check for Spock blocks
        val blockIndex = SpockBlockIndex.build(method)
        return blockIndex.blocks.isNotEmpty()
    }

    /**
     * Extract a clean feature name from a method.
     *
     * Spock feature methods can have string names like:
     * - def "should calculate sum"() { ... }
     *
     * Or traditional names:
     * - def shouldCalculateSum() { ... }
     */
    private fun extractFeatureName(method: MethodNode): String {
        val name = method.name

        // Check if it's a string-based method name (e.g., "$spock_feature_0_0")
        // In Spock, the actual name is stored as the method name directly
        // or we can use annotations to get the description

        // For now, return the raw method name
        // The VS Code extension can clean it up if needed
        return name
    }

    private val EXCLUDED_METHODS = setOf(
        "setup",
        "cleanup",
        "setupSpec",
        "cleanupSpec",
        "<init>", // Constructor
        "<clinit>", // Static initializer
        "getMetaClass",
        "setMetaClass",
        "invokeMethod",
        "getProperty",
        "setProperty",
    )
}
