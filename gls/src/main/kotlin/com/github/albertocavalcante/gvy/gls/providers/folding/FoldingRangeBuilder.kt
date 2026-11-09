package com.github.albertocavalcante.gvy.gls.providers.folding

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import groovyjarjarantlr4.v4.runtime.CharStreams
import groovyjarjarantlr4.v4.runtime.Token
import org.apache.groovy.parser.antlr4.GroovyLangLexer
import org.apache.groovy.parser.antlr4.GroovyLexer
import org.apache.groovy.parser.antlr4.GroovySyntaxError
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap

/** Only nodes belonging to the captured document may supply structural folds. */
internal fun buildFoldingRanges(
    uri: URI,
    source: String,
    model: GroovyAstModel,
    checkCancelled: () -> Unit,
): List<FoldingRange> {
    // The tracker also owns ClassNode references in imports, headers and constructor calls.
    // Only module declaration identities represent class bodies; ClassNode equality is name-based.
    val declarations = Collections.newSetFromMap(IdentityHashMap<ClassNode, Boolean>())
    model.getAllClassNodes().forEach {
        checkCancelled()
        if (model.getUri(it) == uri) declarations.add(it)
    }
    val owned = model.getNodes(uri).filter {
        checkCancelled()
        model.getUri(it) == uri && (it !is ClassNode || it in declarations)
    }
    return FoldingRangeBuilder(TextIndex(source), owned).build(checkCancelled)
}

/** Pure source/AST construction; lexer offsets are projected without changing source bytes. */
internal class FoldingRangeBuilder(private val index: TextIndex, private val nodes: List<ASTNode>) {
    private data class Span(val start: Int, val end: Int)
    private data class Comment(val span: Span, val block: Boolean)

    fun build(checkCancelled: () -> Unit): List<FoldingRange> {
        val comments = comments(checkCancelled)
        val structures = nodes.mapNotNull { node ->
            checkCancelled()
            val structural = node is MethodNode || node is BlockStatement || node is ClosureExpression ||
                (node is ClassNode && !node.isScript)
            if (structural) {
                // Preserve the established class/method convention: leave the closing-brace line visible.
                range(node)?.let { fold(it.start.line, it.end.line - 1, FoldingRangeKind.Region) }
            } else {
                null
            }
        }
        val commentFolds = comments.filter { it.block }.mapNotNull {
            checkCancelled()
            fold(index.positionOf(it.span.start).line, index.positionOf(it.span.end).line, FoldingRangeKind.Comment)
        }
        return (structures + commentFolds + imports(comments.map { it.span }, checkCancelled))
            .distinctBy { Triple(it.startLine, it.endLine, it.kind) }
            .sortedWith(compareBy<FoldingRange> { it.startLine }.thenByDescending { it.endLine }.thenBy { it.kind })
            .take(MAX_FOLDING_RANGES)
    }

    private fun range(node: ASTNode): Range? = index.utf16Range(
        Range(
            Position(node.lineNumber - 1, node.columnNumber - 1),
            Position(node.lastLineNumber - 1, node.lastColumnNumber - 1),
        ),
    )?.takeIf { index.offsetOf(it.start) <= index.offsetOf(it.end) }

    private fun imports(comments: List<Span>, checkCancelled: () -> Unit): List<FoldingRange> {
        val imports = nodes.filterIsInstance<ImportNode>().mapNotNull {
            checkCancelled()
            range(it)?.let { range -> Span(index.offsetOf(range.start), index.offsetOf(range.end)) }
        }.distinct().sortedBy { it.start }
        val folds = mutableListOf<FoldingRange>()
        var group: Span? = null
        for (next in imports) {
            checkCancelled()
            val previous = group
            if (previous == null) {
                group = next
            } else if (trivia(previous.end, next.start, comments, checkCancelled)) {
                group = Span(previous.start, maxOf(previous.end, next.end))
            } else {
                importFold(previous)?.let { folds.add(it) }
                group = next
            }
        }
        group?.let { importFold(it) }?.let { folds.add(it) }
        return folds
    }

    private fun importFold(span: Span): FoldingRange? =
        fold(index.positionOf(span.start).line, index.positionOf(span.end).line, FoldingRangeKind.Imports)

    private fun trivia(start: Int, end: Int, comments: List<Span>, checkCancelled: () -> Unit): Boolean {
        var at = start
        while (at < end) {
            checkCancelled()
            if (index.text[at].isWhitespace() || index.text[at] == ';') {
                at++
            } else {
                val found = comments.binarySearchBy(at) { it.start }
                val previous = if (found >= 0) found else -found - 2
                val comment = comments.getOrNull(previous) ?: return false
                if (at < comment.start || at >= comment.end || comment.end > end) return false
                at = comment.end
            }
        }
        return true
    }

    private fun comments(checkCancelled: () -> Unit): List<Comment> {
        checkCancelled()
        val lexer = GroovyLangLexer(CharStreams.fromString(index.text))
        lexer.removeErrorListeners()
        val comments = mutableListOf<Comment>()
        try {
            while (true) {
                checkCancelled()
                val token = lexer.nextToken()
                if (token.type == Token.EOF) break
                comment(token, lexer.vocabulary.getSymbolicName(token.type))?.let { comments.add(it) }
            }
        } catch (_: GroovySyntaxError) {
            // Retain proven comments before malformed input; cancellation and fatal errors propagate.
        }
        return comments
    }

    private fun comment(token: Token, symbolicName: String?): Comment? {
        val text =
            token.takeIf { it.type == GroovyLexer.NL || symbolicName == "DELIMITED_COMMENT" }?.text ?: return null
        val block = when {
            text.startsWith("/*") && text.endsWith("*/") -> true
            text.startsWith("//") || text.startsWith("#!") -> false
            else -> null
        } ?: return null
        return commentSpan(token, text)?.let { Comment(it, block) }
    }

    private fun commentSpan(token: Token, text: String): Span? {
        val position = index.utf16Position(Position(token.line - 1, token.charPositionInLine)) ?: return null
        val start = index.offsetOf(position)
        return if (text.length <= index.text.length - start && index.text.regionMatches(start, text, 0, text.length)) {
            Span(start, start + text.length)
        } else {
            null
        }
    }

    private fun fold(start: Int, end: Int, kind: String): FoldingRange? =
        if (end > start) FoldingRange(start, end).apply { this.kind = kind } else null

    companion object {
        internal const val MAX_FOLDING_RANGES = 10_000
    }
}
