package com.github.albertocavalcante.groovyjunit.junit4

import com.github.albertocavalcante.groovytesting.api.TestFramework
import com.github.albertocavalcante.groovytesting.api.TestFrameworkDetector
import com.github.albertocavalcante.groovytesting.api.TestItem
import com.github.albertocavalcante.groovytesting.api.TestItemKind
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode

class JUnit4TestDetector : TestFrameworkDetector {
    override val framework: TestFramework = TestFramework.JUNIT4

    companion object {
        private const val JUNIT4_TEST_ANNOTATION = "org.junit.Test"
        private const val JUNIT4_TEST_CASE_CLASS = "junit.framework.TestCase"
        private const val JUNIT4_RUN_WITH_ANNOTATION = "org.junit.runner.RunWith"
    }

    override fun appliesTo(classNode: ClassNode, module: ModuleNode?, classLoader: ClassLoader?): Boolean {
        val hasImport = module?.imports?.any {
            it.className == JUNIT4_TEST_ANNOTATION ||
                it.className == JUNIT4_TEST_CASE_CLASS ||
                it.className == JUNIT4_RUN_WITH_ANNOTATION
        } ?: false

        val hasRunWithAnnotation = classNode.annotations.any {
            it.classNode.name == "RunWith" || it.classNode.name == JUNIT4_RUN_WITH_ANNOTATION
        }

        val isTestCase = isTestCase(classNode)

        val hasTestMethod = classNode.methods.any { method ->
            method.annotations.any { it.classNode.name == "Test" || it.classNode.name == JUNIT4_TEST_ANNOTATION }
        }

        return hasImport || hasRunWithAnnotation || isTestCase || hasTestMethod
    }

    override fun extractTests(classNode: ClassNode): List<TestItem> {
        val tests = mutableListOf<TestItem>()
        val className = classNode.name
        val isTestCase = isTestCase(classNode)

        // Add class item
        tests.add(
            TestItem(
                id = className,
                name = classNode.nameWithoutPackage,
                kind = TestItemKind.CLASS,
                framework = TestFramework.JUNIT4,
                line = classNode.lineNumber.coerceAtLeast(1),
            ),
        )

        // Add methods
        classNode.methods.forEach { method ->
            val isAnnotated =
                method.annotations.any { it.classNode.name == "Test" || it.classNode.name == JUNIT4_TEST_ANNOTATION }
            val isLegacyTest =
                isTestCase && method.name.startsWith("test") && method.isPublic &&
                    method.returnType.name == "void" && method.parameters.isEmpty()

            if (isAnnotated || isLegacyTest) {
                tests.add(
                    TestItem(
                        id = "$className.${method.name}",
                        name = method.name,
                        kind = TestItemKind.METHOD,
                        framework = TestFramework.JUNIT4,
                        line = method.lineNumber.coerceAtLeast(1),
                        parent = className,
                    ),
                )
            }
        }

        return tests
    }

    private fun isTestCase(classNode: ClassNode): Boolean {
        var current = classNode
        while (current.name != "java.lang.Object") {
            if (current.name == JUNIT4_TEST_CASE_CLASS) return true
            // Basic check for direct extension if unresolved
            if (current.superClass?.name == JUNIT4_TEST_CASE_CLASS ||
                current.superClass?.name == "TestCase"
            ) {
                return true
            }
            if (current.superClass == null) break
            current = current.superClass
        }
        return false
    }
}
