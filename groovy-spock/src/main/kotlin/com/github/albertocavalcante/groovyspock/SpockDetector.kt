package com.github.albertocavalcante.groovyspock

import com.github.albertocavalcante.groovyparser.api.ParseResult
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.ModuleNode
import java.net.URI

object SpockDetector {
    private val spockImportRegex = Regex("(?m)^\\s*import\\s+spock\\.")
    private val spockExtendsRegex =
        Regex("(?m)^\\s*(?:abstract\\s+)?class\\s+\\w+.*\\bextends\\s+spock\\.lang\\.Specification\\b")

    fun isSpockSpec(uri: URI, parseResult: ParseResult): Boolean {
        val path = uri.path ?: return false
        if (!path.endsWith(".groovy", ignoreCase = true)) return false

        // NOTE: Heuristic / tradeoff:
        // File naming conventions are commonly used for specs and provide a cheap fallback when we cannot produce
        // an AST (e.g., syntax errors or incomplete edits). When we *do* have an AST, we prefer deterministic
        // AST/classpath-based signals to avoid false positives (e.g., a non-Spock class named `*Spec.groovy`).
        // TODO: Prefer project-aware test source detection (e.g., Gradle/Maven) over filename heuristics.
        val module =
            parseResult.ast ?: return path.endsWith("Spec.groovy", ignoreCase = true)

        // Tier 1: If Spock is on the compilation classpath, use semantic class hierarchy checks (most correct).
        val classLoader = parseResult.compilationUnit.classLoader
        if (classLoader is GroovyClassLoader) {
            runCatching { classLoader.loadClass("spock.lang.Specification") }
                .map { ClassHelper.make(it) }
                .getOrNull()
                ?.let { specClassNode ->
                    if (module.classes.any { it.isDerivedFrom(specClassNode) }) return true
                }
        }

        // Tier 2: Import-aware AST check (no class loading required).
        return isSpockSpecByImportsAndSuperClass(module)
    }

    fun isLikelySpockSpec(uri: URI, content: String): Boolean {
        val path = uri.path ?: return false
        if (!path.endsWith(".groovy", ignoreCase = true)) return false

        if (path.endsWith("Spec.groovy", ignoreCase = true)) {
            return true
        }

        // NOTE: Heuristic / tradeoff:
        // Spock is typically identified by extending `spock.lang.Specification`, but that requires either AST or
        // classpath-aware type resolution. We use light string markers to enable quick, dependency-free detection.
        // TODO: Prefer AST-driven detection (ClassNode.superClass) and/or classpath resolution when available.
        val normalized = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        // NOTE: Heuristic / tradeoff:
        // We intentionally key off common source-level patterns (`import spock.*`, `extends spock.lang.Specification`)
        // rather than deeper semantic checks. This keeps detection cheap and dependency-free, but can miss unusual code
        // layouts (e.g., split class declarations) or produce false negatives.
        // TODO: Prefer AST-driven detection (ClassNode.superClass) and/or classpath resolution when available.
        return spockImportRegex.containsMatchIn(normalized) ||
            spockExtendsRegex.containsMatchIn(normalized)
    }

    private fun isSpockSpecByImportsAndSuperClass(module: ModuleNode): Boolean {
        val spockSpecImported =
            module.imports
                .asSequence()
                .mapNotNull(ImportNode::getClassName)
                .any { it == "spock.lang.Specification" }

        val spockLangStarImported =
            module.starImports
                .asSequence()
                .mapNotNull(ImportNode::getPackageName)
                .any { it.trimEnd('.') == "spock.lang" }

        return module.classes.any { clazz ->
            val superClass = clazz.superClass ?: return@any false

            when {
                superClass.name == "spock.lang.Specification" -> true
                superClass.nameWithoutPackage == "Specification" -> spockSpecImported || spockLangStarImported
                else -> false
            }
        }
    }
}
