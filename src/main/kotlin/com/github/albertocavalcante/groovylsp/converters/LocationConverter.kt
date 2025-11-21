package com.github.albertocavalcante.groovylsp.converters

import com.github.albertocavalcante.groovyparser.ast.AstVisitor
import com.github.albertocavalcante.groovyparser.ast.safeRange
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import com.github.albertocavalcante.groovyparser.ast.types.Position as GroovyPosition
import com.github.albertocavalcante.groovyparser.ast.types.Range as GroovyRange
import org.eclipse.lsp4j.Position as LspPosition
import org.eclipse.lsp4j.Range as LspRange

fun GroovyPosition.toLspPosition(): LspPosition = LspPosition(this.line, this.character)

fun LspPosition.toGroovyPosition(): GroovyPosition = GroovyPosition(this.line, this.character)

fun GroovyRange.toLspRange(): LspRange = LspRange(this.start.toLspPosition(), this.end.toLspPosition())

fun LspRange.toGroovyRange(): GroovyRange = GroovyRange(this.start.toGroovyPosition(), this.end.toGroovyPosition())

fun ASTNode.toLspLocation(visitor: AstVisitor): Location? {
    val range = this.safeRange().getOrNull()?.toLspRange() ?: return null
    val uri = visitor.getUri(this)?.toString() ?: return null
    return Location(uri, range)
}

fun ASTNode.toLspRange(): LspRange? = this.safeRange().getOrNull()?.toLspRange()

fun ASTNode.toLspLocationLink(targetNode: ASTNode, visitor: AstVisitor): LocationLink? {
    val targetRange = targetNode.safeRange().getOrNull()?.toLspRange() ?: return null
    val targetUri = visitor.getUri(targetNode)?.toString() ?: return null
    val originRange = this.safeRange().getOrNull()?.toLspRange() ?: return null
    return LocationLink(targetUri, targetRange, originRange)
}
