package com.github.albertocavalcante.gvy.gls.providers.definition

import com.github.albertocavalcante.groovyparser.ast.containsPosition
import com.github.albertocavalcante.gvy.spock.SpockDataVariables
import com.github.albertocavalcante.gvy.spock.SpockSourceContext
import com.github.albertocavalcante.gvy.spock.SpockUnrollPattern
import com.github.albertocavalcante.gvy.spock.SpockUnrollSelection
import com.github.albertocavalcante.gvy.spock.SpockUnrollSource
import com.github.albertocavalcante.gvy.spock.UnrollPatternOrigin
import com.github.albertocavalcante.gvy.spock.UnrollRoot
import com.github.albertocavalcante.gvy.spock.UnrollSelection
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.DynamicVariable
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Position

/** Source-backed lookup only; never evaluates a data provider or an unroll expression. */
internal object SpockDefinitionLookup {
    /** [isPartial] is true when the compile that produced [module] reported errors. */
    fun find(
        module: ModuleNode,
        content: String,
        position: Position,
        origin: ASTNode? = null,
        isPartial: Boolean = false,
    ): ASTNode? {
        val line = content.lines().getOrNull(position.line) ?: return null
        val source = SpockSourceContext.from(module, isPartial)
        val header = HeaderContext(SpockUnrollSelection.forModule(module), SpockUnrollSource(content), content)
        return source.classes.filter { !it.classNode.isScript && it.isSpecification(module) }
            .flatMap { it.classNode.methods }
            .filter { it.containsPosition(position.line, position.character) }
            .firstNotNullOfOrNull { method ->
                val variables = SpockDataVariables.extract(method)
                val root = header.root(method, line, position)
                if (root != null) variables[root] else bodyReference(origin, variables, position)
            }
    }

    /** One request's unroll selection and literal mapping, shared by every feature it inspects. */
    private class HeaderContext(
        private val selector: SpockUnrollSelection.ModuleSelector,
        private val unrollSource: SpockUnrollSource,
        private val content: String,
    ) {
        /**
         * The data variable whose placeholder root is under the cursor, in the pattern Spock actually
         * uses (#1211 row 7). Only feature-level patterns map to this feature's header: a spec-level
         * pattern sits on the class. Rolled-up, unparameterized and configuration-dependent features
         * have no header placeholders to navigate.
         *
         * Known limit: `SpockConfig` is never read, so `unroll.unrollByDefault = false` is ignored. A plain
         * feature whose name pattern applies only by default ([UnrollSelection.Pattern.requiresUnrollByDefault])
         * still navigates, although Spock would not unroll it under that configuration.
         */
        fun root(method: MethodNode, line: String, position: Position): String? =
            when (val selection = selector.select(method)) {
                is UnrollSelection.Pattern -> selectedRoot(method, selection, position)
                is UnrollSelection.Uncertain -> textHeaderRoot(method, line, position)
                else -> null
            }

        private fun selectedRoot(method: MethodNode, selection: UnrollSelection.Pattern, position: Position): String? {
            val literal = when (selection.origin) {
                UnrollPatternOrigin.FEATURE_NAME -> unrollSource.featureName(method)
                UnrollPatternOrigin.FEATURE_ANNOTATION -> selection.annotationValue?.let(unrollSource::annotationValue)
                UnrollPatternOrigin.SPEC_ANNOTATION -> null
            }
            val offset = offsetOf(content, position)
            return literal?.takeIf { offset != null }?.let { mapped ->
                SpockUnrollPattern.parse(mapped.value).firstNotNullOfOrNull { placeholder ->
                    val root = placeholder.root as? UnrollRoot.DataVariable
                    root?.name?.takeIf { mapped.sourceRange(placeholder.rootRange)?.contains(offset) == true }
                }
            }
        }
    }

    /** UTF-16 offset of [position], counting lines the way [SpockUnrollSource] does (by `\n`). */
    private fun offsetOf(content: String, position: Position): Int? {
        var start = 0
        repeat(position.line) {
            val newline = content.indexOf('\n', start)
            if (newline < 0) return null
            start = newline + 1
        }
        return (start + position.character).takeIf { it <= content.length }
    }

    /**
     * Retained fallback (#1211 boundaries), used only when the unroll selection is UNCERTAIN (for
     * example an `@Unroll` bound through a star import):
     * - Purpose: keep header navigation where annotation identity or the value cannot be proven.
     * - Missing evidence: the annotation's binding or a constant value; target evidence would decide.
     * - Known failure modes: matches `Unroll` by simple name and finds the literal by quoted text on one
     *   line, so escaped or multiline spellings decline and a look-alike annotation can match.
     * - Permitted outputs: read-only navigation only.
     * - Tests: `a star-imported Unroll keeps the previous header matching`.
     * - Replacement: resolve the annotation with target evidence (#1285 step 2), then remove this path.
     */
    private fun textHeaderRoot(method: MethodNode, line: String, position: Position): String? {
        val body = method.code ?: return null
        // Groovy anchors annotated methods at the first annotation. Restrict matching to the
        // signature so a string in the body cannot masquerade as the feature's quoted name.
        val header = position.line + 1 < body.lineNumber ||
            (position.line + 1 == body.lineNumber && position.character < body.columnNumber - 1)
        if (!header) return null
        val patterns = buildList {
            add(method.name)
            method.annotations.filter {
                it.classNode.name in setOf("Unroll", "spock.lang.Unroll")
            }.mapNotNullTo(this) { (it.getMember("value") as? ConstantExpression)?.value as? String }
        }
        return patterns.firstNotNullOfOrNull { pattern ->
            // Escaped and multiline spellings decline rather than inventing an offset mapping.
            val quoted = listOf("\"$pattern\"", "'$pattern'").firstOrNull { line.contains(it) }
            val start = quoted?.let { line.indexOf(it) + 1 }
            start?.let {
                SpockDataVariables.placeholders(pattern).firstOrNull { reference ->
                    position.character - it in reference.startOffset until reference.endOffset
                }?.name
            }
        }
    }

    private fun bodyReference(
        origin: ASTNode?,
        variables: Map<String, VariableExpression>,
        position: Position,
    ): ASTNode? {
        val expression = origin as? VariableExpression ?: return null
        val binding = expression.accessedVariable
        val isDataBinding = binding == null || binding is DynamicVariable || binding is FieldNode
        val atReference = expression.lineNumber == position.line + 1 &&
            position.character in (expression.columnNumber - 1) until
            (expression.columnNumber - 1 + expression.name.length)
        // Use the cached cursor node rather than recursively visiting an arbitrarily deep method.
        // Data variables shadow fields, while real locals/closure parameters retain their binding.
        return if (atReference && isDataBinding) variables[expression.name] else null
    }
}
