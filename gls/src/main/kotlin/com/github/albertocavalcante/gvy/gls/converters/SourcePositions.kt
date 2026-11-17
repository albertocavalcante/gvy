// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.converters

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.Location
import java.net.URI
import com.github.albertocavalcante.groovyparser.ast.types.Position as GroovyPosition
import org.eclipse.lsp4j.Position as LspPosition
import org.eclipse.lsp4j.Range as LspRange

/**
 * The Groovy position (code-point column) of this protocol position (UTF-16 column) in [source].
 * Without source text, or for a position outside it, the columns are copied unchanged.
 */
fun LspPosition.toGroovyPosition(source: String?): GroovyPosition =
    source?.let { TextIndex(it).codePointPosition(this) }?.toGroovyPosition() ?: toGroovyPosition()

/**
 * This range, built from Groovy coordinates (code-point columns, LF-only lines), as a protocol range
 * (UTF-16 columns) over [source]. Without source text, or for a range outside it, it is returned unchanged.
 */
fun LspRange.toProtocolRange(source: String?): LspRange = source?.let { TextIndex(it).utf16Range(this) } ?: this

/** The protocol range of this node's span in [source]; null for a node without a valid span. */
fun ASTNode.toProtocolRange(source: String?): LspRange? = toLspRange()?.toProtocolRange(source)

/** The location of this node, with its range converted using the text [sourceOf] finds for the node's document. */
fun ASTNode.toLspLocation(visitor: GroovyAstModel, sourceOf: (String) -> String?): Location? {
    val location = toLspLocation(visitor) ?: return null
    return Location(location.uri, toProtocolRange(sourceOf(location.uri)) ?: location.range)
}

/** The compiled text of the document at [targetUri]: [current] for [currentUri], else its cached compile. */
@Suppress("SwallowedException") // an unparsable URI simply has no cached text
internal fun GroovyCompilationService.compiledTextOf(targetUri: String, currentUri: URI, current: String): String? {
    val target = try {
        URI.create(targetUri)
    } catch (e: IllegalArgumentException) {
        return null
    }
    return if (target == currentUri) current else getCompiledSnapshot(target)?.sourceText
}
