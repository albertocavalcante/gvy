package com.github.albertocavalcante.gvy.gls.providers.semantictokens

import com.github.albertocavalcante.groovyparser.ast.isScriptSafe
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.gvy.gls.utils.declaredName
import com.github.albertocavalcante.gvy.gls.utils.firstNameOccurrence
import com.github.albertocavalcante.gvy.gls.utils.lastNameOccurrence
import com.github.albertocavalcante.gvy.gls.utils.skipSourceTrivia
import com.github.albertocavalcante.gvy.gls.utils.sourceIdentifierEnd
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/** Proves identifier spellings inside captured, URI-owned AST source fragments. */
internal class SemanticSourceRanges(val index: TextIndex, private val owned: (ASTNode) -> Boolean = { true }) {
    constructor(source: String) : this(TextIndex(source))

    fun range(node: ASTNode): Range? {
        if (!owned(node)) return null
        return index.utf16Range(
            Range(
                Position(node.lineNumber - 1, node.columnNumber - 1),
                Position(node.lastLineNumber - 1, node.lastColumnNumber - 1),
            ),
        )?.takeIf { index.offsetOf(it.start) <= index.offsetOf(it.end) }
    }

    fun declaration(node: AnnotatedNode, name: String, type: ASTNode? = null, initial: ASTNode? = null): Range? {
        if (node is ClassNode && node.isScriptSafe()) return null
        val scope = scope(node, node.annotations.orEmpty() + listOfNotNull(type), listOfNotNull(initial)) ?: return null
        val enumMember = node is FieldNode &&
            TokenTypeResolver.getTokenTypeForField(node) == TokenTypeResolver.TokenTypes.ENUM_MEMBER
        return if (node is ClassNode || enumMember) {
            firstNameOccurrence(
                index,
                scope,
                name,
            )
        } else {
            lastNameOccurrence(index, scope, name)
        }
    }

    fun methodName(node: MethodNode): Range? {
        val after: List<ASTNode> = node.annotations.orEmpty() + node.genericsTypes?.toList().orEmpty()
        val scope = scope(node, after, listOfNotNull(node.parameters.firstOrNull(), node.code)) ?: return null
        var candidate = firstNameOccurrence(index, scope, node.name)
        val limit = index.offsetOf(scope.end)
        while (candidate != null) {
            var next = skipSourceTrivia(index.text, index.offsetOf(candidate.end), limit)
            val before = index.offsetOf(candidate.start) - 1
            if (next < limit) {
                val closingQuote = index.text[next] in "\"'"
                val matchingOpen = before >= 0 && index.text[before] == index.text[next]
                if (closingQuote && matchingOpen) next = skipSourceTrivia(index.text, next + 1, limit)
            }
            if (next < limit && index.text[next] == '(') break
            candidate = firstNameOccurrence(index, Range(candidate.end, scope.end), node.name)
        }
        return candidate
    }

    fun importName(node: ImportNode, name: String): Range? =
        scope(node, node.annotations.orEmpty(), emptyList())?.let { lastNameOccurrence(index, it, name) }

    fun name(node: ASTNode, expected: String? = null): Range? = when {
        expected == "class" -> range(node)?.let { lastNameOccurrence(index, it, expected) }
        node is ClassNode || node is ClassExpression || node is AnnotationNode -> typeName(node)
        node is FieldNode -> declaration(node, node.name, node.type, node.initialExpression)
        node is PropertyNode -> declaration(node, node.name, node.type, node.field.initialExpression)
        node is StaticMethodCallExpression -> scope(node, emptyList(), listOfNotNull(node.arguments))?.let {
            lastNameOccurrence(index, it, node.method)
        }
        else -> reference(node, expected)
    }

    private fun reference(node: ASTNode, expected: String?): Range? {
        val text = expected ?: node.declaredName() ?: (node as? ConstantExpression)?.text
        return text?.let { word -> range(node)?.let { firstNameOccurrence(index, it, word) } }
    }

    private fun typeName(node: ASTNode): Range? {
        val scope = scope(node, (node as? AnnotatedNode)?.annotations.orEmpty(), emptyList()) ?: return null
        var start = skipSourceTrivia(index.text, index.offsetOf(scope.start), index.offsetOf(scope.end))
        if (node is AnnotationNode && index.text.getOrNull(start) == '@') start++
        return qualifiedName(start, index.offsetOf(scope.end))
    }

    private fun qualifiedName(start: Int, limit: Int): Range? {
        var at = skipSourceTrivia(index.text, start, limit)
        var end = sourceIdentifierEnd(index.text, at, limit) ?: return null
        if (index.text.substring(at, end) in setOf("def", "var", "class")) return null
        var chosen = at to end
        var next = nextQualifiedSegment(index.text, end, limit)
        while (next != null) {
            chosen = next
            next = nextQualifiedSegment(index.text, next.second, limit)
        }
        return Range(index.positionOf(chosen.first), index.positionOf(chosen.second))
    }

    private fun scope(node: ASTNode, after: List<ASTNode>, before: List<ASTNode>): Range? {
        val original = range(node) ?: return null
        var start = index.offsetOf(original.start)
        var end = index.offsetOf(original.end)
        after.mapNotNull { range(it) }.forEach {
            val offset = index.offsetOf(it.end)
            if (offset in (start + 1)..end) start = offset
        }
        before.mapNotNull { range(it) }.forEach {
            val offset = index.offsetOf(it.start)
            if (offset in (start + 1) until end) end = offset
        }
        return if (start < end) Range(index.positionOf(start), index.positionOf(end)) else null
    }
}

private fun nextQualifiedSegment(text: String, end: Int, limit: Int): Pair<Int, Int>? {
    val dot = skipSourceTrivia(text, end, limit)
    if (dot >= limit || text[dot] != '.') return null
    val start = skipSourceTrivia(text, dot + 1, limit)
    val nextEnd = sourceIdentifierEnd(text, start, limit) ?: return null
    return (start to nextEnd).takeUnless { text.substring(start, nextEnd) == "class" }
}
