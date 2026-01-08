package com.github.albertocavalcante.diagnostics.codenarc

import com.github.albertocavalcante.groovyparser.ast.CoordinateSystem
import org.codehaus.groovy.ast.ASTNode
import org.codenarc.rule.Violation

/**
 * Calculates precise LSP ranges from AST nodes.
 *
 * For Phase 1 POC, supports:
 * - ClassName: highlight class name identifier
 * - MethodName: highlight method name identifier
 * - FieldName: highlight field name identifier
 * - UnusedVariable: highlight variable name
 * - UnusedImport: highlight import statement
 *
 * @param nodeFinder The AstNodeFinder to locate nodes by line number
 * @see HybridRangeCalculator for fallback to heuristics when AST lookup fails
 */
class AstAwareRangeCalculator(private val nodeFinder: AstNodeFinder) {

    companion object {
        /** Rules supported by AST-aware positioning in Phase 1 */
        val SUPPORTED_RULES = setOf(
            "ClassName",
            "MethodName",
            "FieldName",
            "UnusedVariable",
            "UnusedImport",
        )

        /**
         * Check if a rule is supported by AST-aware positioning.
         */
        fun isSupported(ruleName: String): Boolean = ruleName in SUPPORTED_RULES
    }

    /**
     * Calculate precise range for a violation using AST lookup.
     *
     * @param violation The CodeNarc violation
     * @return Pair of (startColumn, endColumn) in 0-based LSP coordinates,
     *         or null if AST lookup fails or rule is unsupported
     */
    fun calculateRange(violation: Violation): Pair<Int, Int>? {
        val ruleName = violation.rule.name
        if (!isSupported(ruleName)) {
            return null
        }

        return when (ruleName) {
            "ClassName" -> calculateClassNameRange(violation)
            "MethodName" -> calculateMethodNameRange(violation)
            "FieldName" -> calculateFieldNameRange(violation)
            "UnusedVariable" -> calculateUnusedVariableRange(violation)
            "UnusedImport" -> calculateUnusedImportRange(violation)
            else -> null
        }
    }

    private fun calculateClassNameRange(violation: Violation): Pair<Int, Int>? {
        val lineNumber = violation.lineNumber ?: return null
        val classNode = nodeFinder.findClassAtLine(lineNumber) ?: return null
        val sourceLine = violation.sourceLine ?: return null

        // Get the simple class name (without package and outer class prefix)
        val className = classNode.nameWithoutPackage.substringAfterLast('$')

        // Find the class name in the source line using word boundary matching
        return findIdentifierInLine(sourceLine, className)
    }

    private fun calculateMethodNameRange(violation: Violation): Pair<Int, Int>? {
        val lineNumber = violation.lineNumber ?: return null
        val methodNode = nodeFinder.findMethodAtLine(lineNumber) ?: return null
        val sourceLine = violation.sourceLine ?: return null

        val methodName = methodNode.name

        // Find the method name in the source line
        return findIdentifierInLine(sourceLine, methodName)
    }

    private fun calculateFieldNameRange(violation: Violation): Pair<Int, Int>? {
        val lineNumber = violation.lineNumber ?: return null
        val fieldNode = nodeFinder.findFieldAtLine(lineNumber) ?: return null
        val sourceLine = violation.sourceLine ?: return null

        val fieldName = fieldNode.name

        // Find the field name in the source line
        return findIdentifierInLine(sourceLine, fieldName)
    }

    private fun calculateUnusedVariableRange(violation: Violation): Pair<Int, Int>? {
        val lineNumber = violation.lineNumber ?: return null
        val sourceLine = violation.sourceLine ?: return null

        // Extract variable name from message: "The variable [varName] in class X is not used"
        val varName = extractBracketedValue(violation.message, "variable") ?: return null
        val variable = nodeFinder.findVariableAtLine(lineNumber, varName) ?: return null

        // Variable found - search for its name in the source line
        return findIdentifierInLine(sourceLine, varName)
    }

    private fun calculateUnusedImportRange(violation: Violation): Pair<Int, Int>? {
        val lineNumber = violation.lineNumber ?: return null
        val importNode = nodeFinder.findImportAtLine(lineNumber) ?: return null
        val sourceLine = violation.sourceLine ?: return null

        // For imports, highlight the class name (not "import" keyword)
        val className = importNode.className
        if (className != null) {
            // Try to find just the simple class name (e.g., "List" from "java.util.List")
            val simpleName = className.substringAfterLast('.')
            return findIdentifierInLine(sourceLine, simpleName)
        }

        // For star imports, highlight the package
        val packageName = importNode.packageName?.trimEnd('.')
        if (packageName != null) {
            return findIdentifierInLine(sourceLine, packageName)
        }

        return null
    }

    /**
     * Find an identifier in a source line using word boundary matching.
     *
     * @param sourceLine The source code line
     * @param identifier The identifier to find
     * @return Pair of (startColumn, endColumn) in 0-based LSP coordinates, or null if not found
     */
    private fun findIdentifierInLine(sourceLine: String, identifier: String): Pair<Int, Int>? {
        // Use word boundary regex to find exact identifier match
        val regex = Regex("""\b${Regex.escape(identifier)}\b""")
        val match = regex.find(sourceLine) ?: return null
        return Pair(match.range.first, match.range.last + 1)
    }

    /**
     * Extract a value enclosed in brackets from a message.
     * E.g., "The variable [myVar] in class X" -> "myVar"
     */
    private fun extractBracketedValue(message: String?, keyword: String): String? {
        if (message == null) return null
        val regex = Regex("""The $keyword \[([^\]]+)\]""", RegexOption.IGNORE_CASE)
        return regex.find(message)?.groupValues?.getOrNull(1)
    }
}
