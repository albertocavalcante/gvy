package com.github.albertocavalcante.gvy.gls.utils

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/** The name this node declares or refers to as a symbol; null for nodes that have none. */
fun ASTNode.declaredName(): String? = when (this) {
    is VariableExpression -> name
    is Parameter -> name
    is MethodNode -> name
    is FieldNode -> name
    is PropertyNode -> name
    is ClassNode -> nameWithoutPackage
    else -> null
}

/**
 * The first whole-word occurrence of [name] inside [range] of [text], as an LSP range; null if
 * there is none. Identifier characters are Java's, which covers Groovy identifiers. A range that
 * runs past the end of [text] is clamped to it.
 */
fun firstNameOccurrence(text: String, range: Range, name: String): Range? {
    if (name.isEmpty()) return null
    val lineStarts = lineStartOffsets(text)
    fun offsetOf(p: Position): Int =
        if (p.line >= lineStarts.size) text.length else minOf(lineStarts[p.line] + p.character, text.length)
    fun positionOf(offset: Int): Position {
        val line = lineStarts.indexOfLast { it <= offset }
        return Position(line, offset - lineStarts[line])
    }

    val end = offsetOf(range.end)
    var from = offsetOf(range.start)
    while (true) {
        val at = text.indexOf(name, from)
        if (at < 0 || at + name.length > end) return null
        if (isWordBoundary(text, at - 1) && isWordBoundary(text, at + name.length)) {
            return Range(positionOf(at), positionOf(at + name.length))
        }
        from = at + 1
    }
}

private fun isWordBoundary(text: String, index: Int): Boolean =
    index !in text.indices || !Character.isJavaIdentifierPart(text[index])

private fun lineStartOffsets(text: String): IntArray {
    val starts = ArrayList<Int>().apply { add(0) }
    text.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
    return starts.toIntArray()
}
