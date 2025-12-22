package com.github.albertocavalcante.groovyspock

import com.github.albertocavalcante.groovyparser.api.ParseResult
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.ModuleNode
import java.net.URI

object SpockDetector {
    private val spockImportRegex = Regex("(?m)^\\s*import\\s+spock\\.")
    private val spockExtendsRegex =
        Regex("(?m)^\\s*(?:abstract\\s+)?class\\s+\\w+.*\\bextends\\s+spock\\.lang\\.Specification\\b")

    /**
     * Checks if a file contains any Spock specifications.
     */
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

        val specClassNode = getSpecificationClassNode(parseResult)

        return module.classes.any { isSpockSpec(it, module, specClassNode) }
    }

    /**
     * Checks if a specific class is a Spock specification.
     *
     * @param classNode The class to check.
     * @param module Optional ModuleNode for import-aware checks.
     * @param specClassNode Optional ClassNode for spock.lang.Specification from classpath.
     */
    fun isSpockSpec(classNode: ClassNode, module: ModuleNode? = null, specClassNode: ClassNode? = null): Boolean {
        // Tier 1: Semantic check if we have the Specification class from classpath (most correct).
        if (specClassNode != null && classNode.isDerivedFrom(specClassNode)) {
            return true
        }

        // Tier 2: Import-aware AST check (no class loading required).
        val superClass = classNode.superClass ?: return false

        if (superClass.name == "spock.lang.Specification") {
            return true
        }

        if (superClass.nameWithoutPackage == "Specification" && module != null) {
            return isSpockSpecImported(module)
        }

        return false
    }

    /**
     * Attempts to load spock.lang.Specification from the compilation classpath.
     */
    fun getSpecificationClassNode(parseResult: ParseResult): ClassNode? {
        val classLoader = parseResult.compilationUnit.classLoader
        if (classLoader is GroovyClassLoader) {
            return runCatching { classLoader.loadClass("spock.lang.Specification") }
                .map { ClassHelper.make(it) }
                .getOrNull()
        }
        return null
    }

    private fun isSpockSpecImported(module: ModuleNode): Boolean {
        val spockSpecImported =
            module.imports
                .asSequence()
                .mapNotNull(ImportNode::getClassName)
                .any { it == "spock.lang.Specification" }

        if (spockSpecImported) return true

        return module.starImports
            .asSequence()
            .mapNotNull(ImportNode::getPackageName)
            .any { it.trimEnd('.') == "spock.lang" }
    }

    /**
     * Quick heuristic check to see if a file is likely to be a Spock specification.
     */
    fun isLikelySpockSpec(uri: URI, content: String): Boolean {
        val path = uri.path ?: return false
        if (!path.endsWith(".groovy", ignoreCase = true)) return false

        if (path.endsWith("Spec.groovy", ignoreCase = true)) {
            return true
        }

        // NOTE: Heuristic / tradeoff:
        // Spock is typically identified by extending `spock.lang.Specification`, but that requires either AST or
        // classpath-aware type resolution. We use light string markers to enable quick, dependency-free detection.
        val normalized = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        // NOTE: Heuristic / tradeoff:
        // We intentionally key off common source-level patterns (`import spock.*`, `extends spock.lang.Specification`)
        // rather than deeper semantic checks. This keeps detection cheap and dependency-free, but can miss unusual code
        // layouts (e.g., split class declarations) or produce false negatives.
        return spockImportRegex.containsMatchIn(normalized) ||
            spockExtendsRegex.containsMatchIn(normalized)
    }
}
