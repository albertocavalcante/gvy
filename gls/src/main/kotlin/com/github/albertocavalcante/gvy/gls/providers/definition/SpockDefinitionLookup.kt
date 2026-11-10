package com.github.albertocavalcante.gvy.gls.providers.definition

import com.github.albertocavalcante.groovyparser.ast.containsPosition
import com.github.albertocavalcante.gvy.spock.SpecificationStatus
import com.github.albertocavalcante.gvy.spock.SpockClassContext
import com.github.albertocavalcante.gvy.spock.SpockDataVariables
import com.github.albertocavalcante.gvy.spock.SpockDetector
import com.github.albertocavalcante.gvy.spock.SpockSourceContext
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
    fun find(module: ModuleNode, content: String, position: Position, origin: ASTNode? = null): ASTNode? {
        val line = content.lines().getOrNull(position.line) ?: return null
        val source = SpockSourceContext.from(module, isPartial = false)
        return source.classes.filter { !it.classNode.isScript && isSpecification(it, module) }
            .flatMap { it.classNode.methods }
            .filter { it.containsPosition(position.line, position.character) }
            .firstNotNullOfOrNull { method ->
                val variables = SpockDataVariables.extract(method)
                val root = headerRoot(method, line, position)
                if (root != null) variables[root] else bodyReference(origin, variables, position)
            }
    }

    /** A confident verdict decides (#974); UNCERTAIN keeps the previous class-level detector. */
    private fun isSpecification(owner: SpockClassContext, module: ModuleNode): Boolean = when (owner.status) {
        SpecificationStatus.SPECIFICATION -> true
        SpecificationStatus.NOT_SPECIFICATION -> false
        SpecificationStatus.UNCERTAIN -> SpockDetector.isSpockSpec(owner.classNode, module)
    }

    private fun headerRoot(method: MethodNode, line: String, position: Position): String? {
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
