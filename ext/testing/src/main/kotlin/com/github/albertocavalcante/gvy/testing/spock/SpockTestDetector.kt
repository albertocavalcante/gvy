// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.testing.spock

import com.github.albertocavalcante.gvy.spock.SpecificationEvidence
import com.github.albertocavalcante.gvy.spock.SpecificationStatus
import com.github.albertocavalcante.gvy.spock.SpockClassContext
import com.github.albertocavalcante.gvy.spock.SpockDetector
import com.github.albertocavalcante.gvy.spock.SpockFeatureExtractor
import com.github.albertocavalcante.gvy.spock.SpockSourceContext
import com.github.albertocavalcante.gvy.testing.api.TestFramework
import com.github.albertocavalcante.gvy.testing.api.TestFrameworkDetector
import com.github.albertocavalcante.gvy.testing.api.TestItem
import com.github.albertocavalcante.gvy.testing.api.TestItemKind
import groovy.lang.GroovyClassLoader
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

private const val LOG_CLASS_NAME_LIMIT = 120

/**
 * Spock test framework detector.
 *
 * Delegates to [SpockDetector] for detection and [SpockFeatureExtractor]
 * for extracting feature methods. This adapter bridges the existing
 * spock module with the new groovy-testing API.
 *
 * Specification identity comes from the module's source model (#974): a confident verdict decides
 * and UNCERTAIN keeps [SpockDetector.isSpockSpec]. The registry asks once per class, so the verdicts
 * for the most recent module are cached (see [ModuleVerdicts]).
 */
class SpockTestDetector internal constructor(private val classify: (ModuleNode) -> List<SpockClassContext>) :
    TestFrameworkDetector {
    constructor() : this({ SpockSourceContext.from(it, isPartial = false).classes })

    /**
     * Verdicts for one module, aligned with `module.classes`. Only enums are kept: every ClassNode
     * references its module, so caching contexts would pin an old AST. The module is held weakly and
     * compared by identity; any mismatch rebuilds.
     */
    private class ModuleVerdicts(
        module: ModuleNode,
        val verdicts: List<Pair<SpecificationStatus, SpecificationEvidence>>,
    ) {
        private val module = WeakReference(module)

        fun isFor(candidate: ModuleNode): Boolean =
            module.get() === candidate && verdicts.size == candidate.classes.size
    }

    private val lastModule = AtomicReference<ModuleVerdicts?>(null)

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
        val owner = module?.let { contextFor(classNode, it) }
        val result = if (owner != null) {
            owner.isSpecification(module, specClassNode)
        } else {
            SpockDetector.isSpockSpec(classNode, module, specClassNode)
        }
        logger.debug {
            val specStatus = if (specClassNode != null) "loaded" else "null"
            val className = classNode.name.take(LOG_CLASS_NAME_LIMIT)
            "SpockTestDetector.appliesTo($className) = $result (specClassNode=$specStatus)"
        }
        return result
    }

    private fun contextFor(classNode: ClassNode, module: ModuleNode): SpockClassContext? {
        val index = module.classes.indexOfFirst { it === classNode }
        if (index < 0) return null
        val cached = lastModule.get()?.takeIf { it.isFor(module) }
            ?: ModuleVerdicts(module, classify(module).map { it.status to it.evidence }).also(lastModule::set)
        val (status, evidence) = cached.verdicts.getOrNull(index) ?: return null
        return SpockClassContext(classNode, status, evidence)
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
