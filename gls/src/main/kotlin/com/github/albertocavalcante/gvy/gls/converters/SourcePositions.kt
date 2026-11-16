// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.converters

import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.groovyparser.ast.types.Position as GroovyPosition
import org.eclipse.lsp4j.Position as LspPosition

/**
 * The Groovy position (code-point column) of this protocol position (UTF-16 column) in [source].
 * Without source text, or for a position outside it, the columns are copied unchanged.
 */
fun LspPosition.toGroovyPosition(source: String?): GroovyPosition =
    source?.let { TextIndex(it).codePointPosition(this) }?.toGroovyPosition() ?: toGroovyPosition()
