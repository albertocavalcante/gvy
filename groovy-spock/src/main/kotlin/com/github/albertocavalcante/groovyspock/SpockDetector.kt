package com.github.albertocavalcante.groovyspock

import java.net.URI

object SpockDetector {
    private val spockImportRegex = Regex("(?m)^\\s*import\\s+spock\\.")
    private val spockExtendsRegex =
        Regex("(?m)^\\s*(?:abstract\\s+)?class\\s+\\w+.*\\bextends\\s+spock\\.lang\\.Specification\\b")

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
}
