package com.github.albertocavalcante.gvy.diagnostics.codenarc

import org.codehaus.groovy.ast.ModuleNode
import org.codenarc.rule.Violation

/**
 * Hybrid range calculator that delegates to AST or heuristic based on rule support.
 *
 * Strategy:
 * 1. For AST-supported rules (ClassName, MethodName, FieldName, UnusedVariable, UnusedImport):
 *    - If ModuleNode is available, try AST-based positioning first
 *    - If AST lookup fails, fall back to heuristic
 * 2. For all other rules: use heuristic positioning directly
 *
 * This provides improved accuracy for supported rules while maintaining full
 * coverage through the proven heuristic approach.
 *
 * @param moduleNode The native Groovy AST, or null if unavailable
 * @see AstAwareRangeCalculator for AST-based positioning
 * @see RuleRangeCalculator for heuristic positioning
 */
class HybridRangeCalculator(private val moduleNode: ModuleNode?) {

    // Lazy initialization - only create if needed
    private val astCalculator: AstAwareRangeCalculator? by lazy {
        moduleNode?.let { AstAwareRangeCalculator(AstNodeFinder(it)) }
    }

    /**
     * Calculate the range for a violation using the best available method.
     *
     * @param violation The CodeNarc violation
     * @param fallbackLine The source line from the file (used if violation.sourceLine is null)
     * @return Pair of (startColumn, endColumn) in 0-based LSP coordinates
     */
    fun calculateRange(violation: Violation, fallbackLine: String): Pair<Int, Int> {
        val ruleName = violation.rule.name

        // For AST-supported rules, try AST path first
        if (AstAwareRangeCalculator.isSupported(ruleName) && astCalculator != null) {
            val astRange = astCalculator?.calculateRange(violation)
            if (astRange != null) {
                return astRange
            }
            // AST lookup failed - fall through to heuristic
        }

        // Use heuristic for unsupported rules or when AST fails
        return RuleRangeCalculator.calculateRange(violation, fallbackLine)
    }
}
