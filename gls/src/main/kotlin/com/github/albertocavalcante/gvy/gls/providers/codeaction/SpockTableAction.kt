// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.groovyparser.ast.isScriptSafe
import com.github.albertocavalcante.gvy.spock.SpecificationStatus
import com.github.albertocavalcante.gvy.spock.SpockSourceContext
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either

internal data class SpockTableRequest(
    val uri: String,
    val content: String,
    val module: ModuleNode?,
    val selection: Range,
    val only: List<String>? = null,
    val version: Int? = null,
    /** True when the compile that produced [module] reported errors, so classes may be missing. */
    val isPartial: Boolean = false,
    /** Remembered verdicts for this document (#1387); null disables recording and recall. */
    val verdicts: SpockVerdictMemory.Access? = null,
)

/**
 * Aligns unambiguous, single-line Spock tables without evaluating their cells.
 *
 * The edit changes source, so it is offered only for a confident [SpecificationStatus.SPECIFICATION]
 * (#1211: a heuristic alone cannot justify a source-changing edit). UNCERTAIN classes, for example a
 * star-imported `Specification` without Spock on the compile classpath, get no action until target
 * evidence can decide them (#1285 step 2, #1211 row 5).
 *
 * Known gap: a degraded compile loses the action for specs proven only by resolution (a star-imported
 * `Specification`, a base from a jar or another workspace file). The parser retries at CONVERSION, where
 * nothing resolves, when a class degrades into a Script or the compiler fails without a collected error
 * (for example a classpath missing the Groovy runtime). Ordinary collected errors keep the resolved AST,
 * and syntax errors leave no compiled snapshot at all. Specs spelled `extends spock.lang.Specification`
 * or with an explicit import stay confident.
 *
 * A spec whose base file currently fails to compile keeps the action through [SpockVerdictMemory]: an
 * UNCERTAIN class is treated as a spec only when the last clean compile proved it and the degraded part is
 * that same base (#1387). The memory is read here only; completion and definition never consume it.
 */
internal object SpockTableAction {
    private const val MAX_COLUMNS = 256
    private val separator = Regex("\\s*(\\|\\|?)\\s*")

    fun createActions(request: SpockTableRequest): List<CodeAction> {
        if (request.only?.any(::matchesKind) == false) return emptyList()
        val lines = request.content.lines()
        val module = request.module ?: return emptyList()
        val source = SpockSourceContext.from(module, request.isPartial)
        val recalled = request.verdicts?.exchange(module, source).orEmpty()
        return source.classes
            .filter { !it.classNode.isScriptSafe() }
            .filter {
                it.status == SpecificationStatus.SPECIFICATION ||
                    (it.status == SpecificationStatus.UNCERTAIN && it.classNode.name in recalled)
            }
            .flatMap { it.classNode.methods }
            .flatMap { method ->
                val collector = TableCollector(lines)
                (method.code as? BlockStatement)?.statements.orEmpty().forEach(collector::accept)
                collector.finish()
            }
            .mapNotNull { table -> createAction(request.uri, table, request.selection, request.version) }
    }

    private fun matchesKind(requested: String): Boolean =
        requested.isEmpty() || requested == CodeActionKind.RefactorRewrite ||
            CodeActionKind.RefactorRewrite.startsWith("$requested.")

    private data class Row(
        val cells: List<String>,
        val separators: List<String>,
        val range: Range,
        val isHeader: Boolean,
        val original: String,
    )

    private class TableCollector(private val lines: List<String>) {
        private val tables = mutableListOf<List<Row>>()
        private var rows = mutableListOf<Row>()
        private var inWhere = false
        private var invalidTable = false

        fun accept(statement: Statement) {
            statement.statementLabels?.lastOrNull()?.let(::startBlock)
            if (inWhere) acceptExpression((statement as? ExpressionStatement)?.expression)
        }

        private fun startBlock(label: String) {
            flush()
            inWhere = when (label) {
                "where" -> true
                "and", "combined" -> inWhere
                else -> false
            }
        }

        private fun acceptExpression(expression: Expression?) {
            val row = expression?.let { row(it, lines) }
            if (row != null) {
                rows.add(row)
            } else if (expression is BinaryExpression && expression.operation.text in listOf("|", "||")) {
                invalidTable = true
            } else {
                flush()
            }
        }

        fun finish(): List<List<Row>> {
            flush()
            return tables
        }

        private fun flush() {
            if (rows.isNotEmpty() && !invalidTable) tables.add(rows)
            rows = mutableListOf()
            invalidTable = false
        }
    }

    private fun flatten(expression: Expression): List<Expression> {
        val pending = ArrayDeque<Expression>()
        val cells = mutableListOf<Expression>()
        pending.addLast(expression)
        while (pending.isNotEmpty()) {
            if (pending.size + cells.size > MAX_COLUMNS) return emptyList()
            val next = pending.removeLast()
            if (next is BinaryExpression && next.operation.text in listOf("|", "||")) {
                pending.addLast(next.rightExpression)
                pending.addLast(next.leftExpression)
            } else {
                cells.add(next)
            }
        }
        return cells
    }

    @Suppress("ReturnCount") // Decline source ranges that cannot be rewritten without ambiguity.
    private fun row(expression: Expression, lines: List<String>): Row? {
        val cells = flatten(expression)
        if (cells.size < 2) return null
        val line = cells.first().lineNumber - 1
        val source = lines.getOrNull(line) ?: return null
        if (cells.any { it.lineNumber - 1 != line || it.lastLineNumber - 1 != line }) return null
        val ranges = cells.map { (it.columnNumber - 1) until (it.lastColumnNumber - 1) }
        if (ranges.any { it.first < 0 || it.last >= source.length || it.isEmpty() }) return null
        if (source.take(ranges.first().first).isNotBlank() || source.drop(ranges.last().last + 1).isNotBlank()) {
            return null
        }
        val separators = ranges.zipWithNext().map { (left, right) ->
            if (left.last >= right.first) return null
            separator.matchEntire(source.substring(left.last + 1, right.first))?.groupValues?.get(1)
                ?: return null
        }
        val text = ranges.map { source.substring(it).trim() }
        // NOTE: Comments inside cells are conservatively left for the general Groovy formatter.
        if (text.any { "/*" in it || "//" in it }) return null
        return Row(
            text,
            separators,
            Range(Position(line, ranges.first().first), Position(line, ranges.last().last + 1)),
            cells.all { it is VariableExpression },
            source.substring(ranges.first().first, ranges.last().last + 1),
        )
    }

    @Suppress("ReturnCount") // Each guard rejects an unsafe or unnecessary edit.
    private fun createAction(uri: String, table: List<Row>, selection: Range, version: Int?): CodeAction? {
        if (table.size < 2 || !table.first().isHeader) return null
        val header = table.first()
        if (table.any { it.cells.size != header.cells.size || it.separators != header.separators }) return null
        val range = Range(header.range.start, table.last().range.end)
        if (compare(selection.end, range.start) < 0 || compare(selection.start, range.end) > 0) return null
        val widths = header.cells.indices.map { column -> table.maxOf { it.cells[column].length } }
        val edits = table.map { row ->
            val replacement = row.cells.mapIndexed { column, cell ->
                if (column == row.cells.lastIndex) cell else cell.padEnd(widths[column])
            }.reduceIndexed { index, text, cell -> text + " ${row.separators[index - 1]} " + cell }
            TextEdit(row.range, replacement)
        }
        if (edits.map { it.newText } == table.map { it.original }) return null
        return CodeAction("Align Spock data table").apply {
            kind = CodeActionKind.RefactorRewrite
            val document = VersionedTextDocumentIdentifier(uri, version)
            edit = WorkspaceEdit(listOf(Either.forLeft(TextDocumentEdit(document, edits))))
        }
    }

    private fun compare(left: Position, right: Position): Int =
        if (left.line == right.line) left.character.compareTo(right.character) else left.line.compareTo(right.line)
}
