package com.github.albertocavalcante.groovyjunit.junit

import com.github.albertocavalcante.groovytesting.api.TestFramework
import com.github.albertocavalcante.groovytesting.api.TestFrameworkDetector
import com.github.albertocavalcante.groovytesting.api.TestItem
import com.github.albertocavalcante.groovytesting.api.TestItemKind
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode

/**
 * Detector for JUnit 5 tests.
 */
class JUnit5TestDetector : TestFrameworkDetector {

    override val framework: TestFramework = TestFramework.JUNIT5

    companion object {
        private const val JUNIT_JUPITER_PACKAGE = "org.junit.jupiter"
        private val TEST_ANNOTATIONS = setOf(
            "Test",
            "ParameterizedTest",
            "RepeatedTest",
            "TestFactory",
            "TestTemplate",
            "Nested",
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.params.ParameterizedTest",
            "org.junit.jupiter.api.RepeatedTest",
            "org.junit.jupiter.api.TestFactory",
            "org.junit.jupiter.api.TestTemplate",
            "org.junit.jupiter.api.Nested",
        )
    }

    override fun appliesTo(classNode: ClassNode, module: ModuleNode?, classLoader: ClassLoader?): Boolean {
        // 1. Check for imports
        if (module != null) {
            val hasJUnitImport = module.imports.any { it.className.startsWith(JUNIT_JUPITER_PACKAGE) } ||
                module.starImports.any { it.packageName.startsWith(JUNIT_JUPITER_PACKAGE) }

            if (hasJUnitImport) return true
        }

        // 2. Check for annotations on the class (e.g. @Nested)
        if (classNode.annotations.any { isTestAnnotation(it) }) {
            return true
        }

        // 3. Check for annotations on methods
        return classNode.methods.any { method ->
            method.annotations.any { isTestAnnotation(it) }
        }
    }

    override fun extractTests(classNode: ClassNode): List<TestItem> {
        val tests = mutableListOf<TestItem>()
        val className = classNode.name

        // Add the class itself
        tests.add(
            TestItem(
                id = className,
                name = classNode.nameWithoutPackage,
                kind = TestItemKind.CLASS,
                framework = TestFramework.JUNIT5,
                line = classNode.lineNumber.coerceAtLeast(1),
            ),
        )

        // Add test methods
        classNode.methods.forEach { method ->
            if (method.annotations.any { isTestAnnotation(it) }) {
                tests.add(
                    TestItem(
                        id = "$className.${method.name}",
                        name = method.name, // TODO: Support @DisplayName
                        kind = TestItemKind.METHOD,
                        framework = TestFramework.JUNIT5,
                        line = method.lineNumber.coerceAtLeast(1),
                        parent = className,
                    ),
                )
            }
        }

        return tests
    }

    private fun isTestAnnotation(annotation: AnnotationNode): Boolean =
        TEST_ANNOTATIONS.contains(annotation.classNode.name) ||
            TEST_ANNOTATIONS.contains(annotation.classNode.nameWithoutPackage)
}
