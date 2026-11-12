// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import com.github.albertocavalcante.gvy.gls.utils.ParameterSourceRanges
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ClassExpression
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/** Groovy's parameter range covers both its type and name; source ranges distinguish their hover targets. */
internal fun parameterTypeHoverNode(node: ASTNode, index: TextIndex, position: Position): ASTNode {
    val parameter = node as? Parameter ?: return node
    val type = ParameterSourceRanges(index).type(parameter) ?: return node
    return if (position.line == type.line && position.character in type.start until type.start + type.length) {
        var component = parameter.type
        while (component.isArray) component = component.componentType
        ClassExpression(component)
    } else {
        node
    }
}

/** Token spans and protocol positions both use UTF-16 source offsets. */
internal fun isHoverPosition(index: TextIndex, position: Position, tokens: GroovyTokenIndex?): Boolean {
    if (!index.isValidPosition(position)) return false
    val source = index.text
    val offset = index.offsetOf(position)
    val line = checkNotNull(index.lineRange(position.line))
    val blank = source.substring(index.offsetOf(line.start), index.offsetOf(line.end)).isBlank()
    val whitespace = source.getOrNull(offset)?.isWhitespace() == true
    val afterIdentifier = offset > 0 && Character.isJavaIdentifierPart(source.codePointBefore(offset))
    return tokens?.isInComment(offset) != true && !blank &&
        (!whitespace || afterIdentifier || tokens?.isInString(offset) == true)
}

/** Highlights the occurrence under the pointer, even when its hover describes a declaration elsewhere. */
internal fun hoverIdentifierRange(index: TextIndex, position: Position): Range? {
    val text = index.text
    var offset = index.offsetOf(position)
    if (offset == text.length || !Character.isJavaIdentifierPart(text.codePointAt(offset))) {
        if (offset == 0 || !Character.isJavaIdentifierPart(text.codePointBefore(offset))) return null
        offset -= Character.charCount(text.codePointBefore(offset))
    }
    var start = offset
    while (start > 0 && Character.isJavaIdentifierPart(text.codePointBefore(start))) {
        start -= Character.charCount(text.codePointBefore(start))
    }
    if (!Character.isJavaIdentifierStart(text.codePointAt(start))) return null
    var end = offset
    while (end < text.length && Character.isJavaIdentifierPart(text.codePointAt(end))) {
        end += Character.charCount(text.codePointAt(end))
    }
    return Range(index.positionOf(start), index.positionOf(end))
}
