package com.github.albertocavalcante.gvy.testing.spock

import com.github.albertocavalcante.gvy.spock.SpockDetector
import com.github.albertocavalcante.gvy.spock.SpockFeatureExtractor
import com.github.albertocavalcante.gvy.testing.api.TestFramework
import com.github.albertocavalcante.gvy.testing.api.TestFrameworkDetector
import com.github.albertocavalcante.gvy.testing.api.TestItem
import com.github.albertocavalcante.gvy.testing.api.TestItemKind
import groovy.lang.GroovyClassLoader
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode

/**
 * Spock test framework detector.
 *
 * Delegates to [SpockDetector] for detection and [SpockFeatureExtractor]
 * for extracting feature methods. This adapter bridges the existing
 * spock module with the new groovy-testing API.
 */
class SpockTestDetector : TestFrameworkDetector {

    private val logger = KotlinLogging.logger {}

    override val framework: TestFramework = TestFramework.SPOCK

    override fun appliesTo(classNode: ClassNode, module: ModuleNode?, classLoader: ClassLoader?): Boolean {
        val specClassNode = if (classLoader is GroovyClassLoader) {
            runCatching { classLoader.loadClass("spock.lang.Specification") }
                .map { ClassHelper.make(it) }
                .getOrNull()
        } else {
            null
        }
        val result = SpockDetector.isSpockSpec(classNode, module, specClassNode)
        logger.info {
            val specStatus = if (specClassNode != null) "loaded" else "null"
            "SpockTestDetector.appliesTo(${classNode.name}) = $result (specClassNode=$specStatus)"
        }
        return result
    }

    override fun extractTests(classNode: ClassNode): List<TestItem> {
        val result = mutableListOf<TestItem>()
        val className = classNode.name

        // Add the class itself as a test suite
        result.add(
            TestItem(
                id = className,
                name = classNode.nameWithoutPackage,
                kind = TestItemKind.CLASS,
                framework = TestFramework.SPOCK,
                line = classNode.lineNumber.coerceAtLeast(1),
            ),
        )

        // Add each feature method
        val features = SpockFeatureExtractor.extractFeatures(classNode)
        for (feature in features) {
            result.add(
                TestItem(
                    id = "$className.${feature.name}",
                    name = feature.name,
                    kind = TestItemKind.METHOD,
                    framework = TestFramework.SPOCK,
                    line = feature.line,
                    parent = className,
                ),
            )
        }

        return result
    }
}
