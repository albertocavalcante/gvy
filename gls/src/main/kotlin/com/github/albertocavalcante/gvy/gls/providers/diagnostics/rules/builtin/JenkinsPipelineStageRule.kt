package com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.builtin

import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.AbstractDiagnosticRule
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.DiagnosticAnalysisType
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.RuleContext
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Checks the stage structure of Declarative Pipelines.
 *
 * Only stages reachable through `pipeline { stages { ... } }` are analyzed, following the
 * Declarative `stages`, `parallel` and `matrix` nesting. Scripted pipelines (`node { stage(...) }`)
 * and scripted shared-library code never have `steps` blocks and are never reported.
 *
 * A Declarative stage must contain exactly one of `steps`, `stages`, `parallel` or `matrix`, and
 * `script` is a step, so it is only valid inside `steps`.
 *
 * NOTE: The rule walks the Groovy AST, so strings and comments cannot skew it. When the AST is
 * unavailable it reports nothing, and a finding whose keyword is not at its AST position is dropped.
 */
class JenkinsPipelineStageRule : AbstractDiagnosticRule() {

    private companion object {
        private const val STAGE = "stage"
        private const val SCRIPT = "script"
        private const val STEPS = "steps"
        private const val STAGES = "stages"
        private const val PARALLEL = "parallel"
        private const val MATRIX = "matrix"
        private val EXECUTION_BLOCKS = setOf(STEPS, STAGES, PARALLEL, MATRIX)
        private const val EXECUTION_BLOCK_LIST = "'steps', 'stages', 'parallel' or 'matrix'"
    }

    override val id = "jenkins-stage-structure"

    override val description =
        "Detect Declarative Pipeline stages without exactly one of steps, stages, parallel or matrix"

    override val analysisType = DiagnosticAnalysisType.AST

    override val defaultSeverity = DiagnosticSeverity.Warning

    override val enabledByDefault = true

    override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext): List<Diagnostic> {
        if (!context.isJenkinsFile(uri)) {
            return emptyList()
        }
        val module = context.getAst() as? ModuleNode ?: return emptyList()

        val findings = mutableListOf<Finding>()
        topLevelStatements(module)
            .mapNotNull { it.blockCall() }
            .filter { it.methodAsString == "pipeline" }
            .forEach { pipeline ->
                pipeline.sections(STAGES).forEach { checkStages(it, findings) }
            }

        val index = TextIndex(content)
        return findings.mapNotNull { finding ->
            // NOTE: A finding whose keyword is not at its AST position (stale AST) is dropped alone.
            val range = finding.range(index)
            if (range == null) {
                logger.debug { "Skipping unanchored '${finding.keyword}' finding in $uri" }
            }
            range?.let { diagnostic(it, finding.message) }
        }
    }

    private fun checkStages(stagesBlock: MethodCallExpression, findings: MutableList<Finding>) {
        stagesBlock.sections(STAGE).forEach { checkStage(it, findings) }
    }

    private fun checkStage(stage: MethodCallExpression, findings: MutableList<Finding>) {
        val name = stage.stageName()
        val sections = stage.sections()
        val executionBlocks = sections.filter { it.methodAsString in EXECUTION_BLOCKS }

        when {
            executionBlocks.isEmpty() ->
                findings += Finding(stage, STAGE, "Stage '$name' is missing a $EXECUTION_BLOCK_LIST block")
            executionBlocks.size > 1 ->
                findings += Finding(stage, STAGE, "Stage '$name' must contain only one of $EXECUTION_BLOCK_LIST")
        }

        sections.filter { it.methodAsString == SCRIPT }.forEach { script ->
            findings += Finding(script, SCRIPT, "'script' must be inside 'steps' (stage '$name')")
        }

        executionBlocks.forEach { block ->
            when (block.methodAsString) {
                STAGES, PARALLEL -> checkStages(block, findings)
                MATRIX -> block.sections(STAGES).forEach { checkStages(it, findings) }
            }
        }
    }

    /** Statements at the top level of the script and of each method body (shared-library `call()`). */
    private fun topLevelStatements(module: ModuleNode): List<Statement> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Statement, Boolean>())
        val bodies = listOfNotNull(module.statementBlock) +
            module.classes.flatMap { classNode -> classNode.methods.mapNotNull { it.code } }
        return bodies.flatMap { it.statementList() }.filter { seen.add(it) }
    }

    private fun Statement.statementList(): List<Statement> = when (this) {
        is BlockStatement -> statements
        else -> listOf(this)
    }

    /** A `name(...) { ... }` call on the implicit receiver, or null for any other statement. */
    private fun Statement.blockCall(): MethodCallExpression? {
        val call = (this as? ExpressionStatement)?.expression as? MethodCallExpression ?: return null
        return call.takeIf { it.isImplicitThis && it.closureArgument() != null }
    }

    private fun MethodCallExpression.arguments(): List<Expression> =
        (arguments as? TupleExpression)?.expressions.orEmpty()

    private fun MethodCallExpression.closureArgument(): ClosureExpression? =
        arguments().lastOrNull() as? ClosureExpression

    /** Block calls directly inside this call's closure, optionally restricted to [name]. */
    private fun MethodCallExpression.sections(name: String? = null): List<MethodCallExpression> {
        val body = closureArgument()?.code ?: return emptyList()
        return body.statementList()
            .mapNotNull { it.blockCall() }
            .filter { name == null || it.methodAsString == name }
    }

    private fun MethodCallExpression.stageName(): String =
        when (val first = arguments().firstOrNull { it !is ClosureExpression }) {
            null -> ""
            is ConstantExpression -> first.value?.toString().orEmpty()
            else -> first.text
        }

    /**
     * A diagnostic anchored at a block call. For a stage the range covers `stage('Name')`; for
     * any other block it covers the block keyword.
     *
     * Groovy positions count code points from 1; [TextIndex] converts them to LSP UTF-16 positions.
     */
    private class Finding(val call: MethodCallExpression, val keyword: String, val message: String) {
        private val startLine = call.method.lineNumber.takeIf { it > 0 } ?: call.lineNumber
        private val startColumn = call.method.columnNumber.takeIf { it > 0 } ?: call.columnNumber

        /** The LSP range, or null when the text at the AST position is not [keyword]. */
        fun range(index: TextIndex): Range? {
            if (startLine < 1 || startColumn < 1) return null
            val startOffset = index.groovyOffset(Position(startLine - 1, startColumn - 1)) ?: return null
            if (!index.text.startsWith(keyword, startOffset)) return null
            val start = index.positionOf(startOffset)
            val keywordEnd = index.positionOf(startOffset + keyword.length)
            if (keyword != STAGE) return Range(start, keywordEnd)

            val nameEnd = (call.arguments as? TupleExpression)?.expressions
                ?.firstOrNull { it !is ClosureExpression }
                ?.takeIf { it.lastLineNumber > 0 && it.lastColumnNumber > 0 }
                ?.let { index.groovyOffset(Position(it.lastLineNumber - 1, it.lastColumnNumber - 1)) }
                ?.takeIf { it > startOffset }
                ?: return Range(start, keywordEnd)
            var at = nameEnd
            while (at < index.text.length && index.text[at].isWhitespace()) at++
            val end = if (at < index.text.length && index.text[at] == ')') at + 1 else nameEnd
            return Range(start, index.positionOf(end))
        }
    }
}
