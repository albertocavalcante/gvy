package com.github.albertocavalcante.groovyspock

import com.github.albertocavalcante.nativeapi.ParseResult
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.ModuleNode
import java.net.URI

object SpockDetector {
    private const val SPOCK_SPECIFICATION_FQN = "spock.lang.Specification"

    private val spockImportRegex = Regex("(?m)^\\s*import\\s+spock\\.")
    private val spockExtendsRegex =
        Regex("(?m)^\\s*(?:abstract\\s+)?class\\s+\\w+.*\\bextends\\s+spock\\.lang\\.Specification\\b")
    private val spockBlockLabelRegex =
        Regex("(?m)^\\s*(given|when|then|expect|cleanup|where)\\s*:")

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
    private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

    fun isSpockSpec(classNode: ClassNode, module: ModuleNode? = null, specClassNode: ClassNode? = null): Boolean {
        // Semantic check if we have the Specification class from classpath
        if (specClassNode != null && classNode.isDerivedFrom(specClassNode)) {
            logger.debug { "Detected Spock spec via derivedFrom: ${classNode.name}" }
            return true
        }

        // Robust AST-based check using sequence traversal
        val hierarchy = classNode.hierarchy().toList()

        // Fallback: If parsing resulted in a Script node (typically due to missing classpath),
        // check if the module imports Spock. This allows detection even when 'extends Specification'
        // resolution failed and the parser fell back to Script.
        if (module != null &&
            classNode.superClass?.name == "groovy.lang.Script" &&
            isSpockSpecImported(module)
        ) {
            return true
        }

        return hierarchy.any {
            it.isSpecification(module)
        }
    }

    private fun ClassNode.hierarchy(): Sequence<ClassNode> = sequence {
        var current: ClassNode? = this@hierarchy
        while (current != null) {
            yield(current)

            // IF resolution failed (superClass is Object), check unresolvedSuperClass
            if (current.superClass == ClassHelper.OBJECT_TYPE) {
                val unresolved = current.unresolvedSuperClass
                if (unresolved != null && unresolved != ClassHelper.OBJECT_TYPE) {
                    yield(unresolved)
                }
            }

            current = current.superClass
        }
    }

    private fun ClassNode.isSpecification(module: ModuleNode?): Boolean {
        if (name == SPOCK_SPECIFICATION_FQN) return true

        if (nameWithoutPackage == "Specification" && module != null) {
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
            return runCatching { classLoader.loadClass(SPOCK_SPECIFICATION_FQN) }
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
                .any { it == SPOCK_SPECIFICATION_FQN }

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

        // NOTE: Heuristic / tradeoff:
        // Spock is typically identified by extending `spock.lang.Specification`, but that requires either AST or
        // classpath-aware type resolution. We use light string markers to enable quick, dependency-free detection.
        // We do NOT rely on filename alone (e.g., *Spec.groovy) as that produces false positives.
        val normalized = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        // Remove comments and string literals to avoid false positives
        val cleaned = removeCommentsAndStrings(normalized)

        // NOTE: Heuristic / tradeoff:
        // We intentionally key off common source-level patterns:
        // 1. `import spock.*` - direct Spock imports
        // 2. `extends spock.lang.Specification` - explicit Spock superclass
        // 3. Spock block labels (given:, when:, then:, etc.) - unique to Spock test structure
        // This keeps detection cheap and dependency-free, but can miss unusual code layouts or produce false negatives.
        // Block label detection helps catch specs that extend custom base classes (e.g., BaseSpec, BaseTest).
        return spockImportRegex.containsMatchIn(cleaned) ||
            spockExtendsRegex.containsMatchIn(cleaned) ||
            spockBlockLabelRegex.containsMatchIn(cleaned)
    }

    /**
     * Removes comments and string literals from code to avoid false positive detections.
     * This is a heuristic approach that handles common cases but may not be perfect for all edge cases.
     *
     * NOTE: Heuristic / tradeoff:
     * This function is intentionally complex as it needs to handle multiple Groovy string and comment formats
     * (single/double quotes, triple quotes, GStrings, single/multi-line comments, escape sequences).
     * A proper solution would require a full lexer/parser, but that would defeat the purpose of a lightweight
     * heuristic check. Edge cases like nested strings or complex escape sequences may not be handled perfectly,
     * but this is acceptable for a best-effort detection mechanism that's supplemented by AST-based checks.
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "MagicNumber")
    private fun removeCommentsAndStrings(code: String): String {
        val result = StringBuilder()
        var i = 0
        while (i < code.length) {
            when {
                // Single-line comment
                code.startsWith("//", i) -> {
                    i = code.indexOf('\n', i).let { if (it == -1) code.length else it + 1 }
                }
                // Multi-line comment
                code.startsWith("/*", i) -> {
                    i = code.indexOf("*/", i + 2).let { if (it == -1) code.length else it + 2 }
                }
                // Triple-quoted string (GString or regular)
                code.startsWith("'''", i) || code.startsWith("\"\"\"", i) -> {
                    val delimiter = code.substring(i, i + 3)
                    i = code.indexOf(delimiter, i + 3).let { if (it == -1) code.length else it + 3 }
                }
                // Single-quoted string
                code[i] == '\'' -> {
                    i++
                    while (i < code.length) {
                        if (code[i] == '\\') {
                            i += 2 // Skip escaped character
                        } else if (code[i] == '\'') {
                            i++
                            break
                        } else {
                            i++
                        }
                    }
                }
                // Double-quoted string (GString)
                code[i] == '"' -> {
                    i++
                    while (i < code.length) {
                        if (code[i] == '\\') {
                            i += 2 // Skip escaped character
                        } else if (code[i] == '"') {
                            i++
                            break
                        } else {
                            i++
                        }
                    }
                }
                // Regular code
                else -> {
                    result.append(code[i])
                    i++
                }
            }
        }
        return result.toString()
    }
}
