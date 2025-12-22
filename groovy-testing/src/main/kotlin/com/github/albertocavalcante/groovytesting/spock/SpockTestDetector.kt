package com.github.albertocavalcante.groovytesting.spock

import com.github.albertocavalcante.groovyspock.SpockDetector
import com.github.albertocavalcante.groovyspock.SpockFeatureExtractor
import com.github.albertocavalcante.groovytesting.api.TestFramework
import com.github.albertocavalcante.groovytesting.api.TestFrameworkDetector
import com.github.albertocavalcante.groovytesting.api.TestItem
import com.github.albertocavalcante.groovytesting.api.TestItemKind
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode

/**
 * Spock test framework detector.
 *
 * Delegates to [SpockDetector] for detection and [SpockFeatureExtractor]
 * for extracting feature methods. This adapter bridges the existing
 * groovy-spock module with the new groovy-testing API.
 */
class SpockTestDetector : TestFrameworkDetector {

    override val framework: TestFramework = TestFramework.SPOCK

    override fun appliesTo(classNode: ClassNode, module: ModuleNode?, classLoader: ClassLoader?): Boolean {
        val specClassNode = if (classLoader is GroovyClassLoader) {
            runCatching { classLoader.loadClass("spock.lang.Specification") }
                .map { ClassHelper.make(it) }
                .getOrNull()
        } else {
            null
        }
        return SpockDetector.isSpockSpec(classNode, module, specClassNode)
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
