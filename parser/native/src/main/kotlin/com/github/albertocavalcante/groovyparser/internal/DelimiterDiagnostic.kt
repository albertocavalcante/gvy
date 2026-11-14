// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.internal

import com.github.albertocavalcante.groovyparser.tokens.DelimiterFacts
import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import com.github.albertocavalcante.nativeapi.ParserDiagnostic
import com.github.albertocavalcante.nativeapi.ParserSeverity
import org.codehaus.groovy.syntax.SyntaxException

internal fun delimiterDiagnostic(
    error: SyntaxException,
    source: SourceSnapshot,
    index: GroovyTokenIndex,
): ParserDiagnostic? {
    if (!index.complete || !error.originalMessage.startsWith("Unexpected input:")) return null
    val offset = source.compilerOffsetOrNull(error.line, error.startColumn) ?: return null
    val conflict = index.delimiterFacts.conflictAt(offset)
    if (conflict != null) {
        val found = source.text.substring(conflict.closer.start, conflict.closer.end)
        val expected = DelimiterFacts.closingText(conflict.opener)
        return ParserDiagnostic(
            range = source.range(conflict.closer.start, conflict.closer.end),
            severity = ParserSeverity.ERROR,
            message = "Mismatched closing delimiter: expected '$expected' but found '$found'.",
            code = "mismatched-delimiter",
        )
    }
    val opener = index.delimiterFacts.unclosedAt(offset, atEndOfFile = offset == source.text.length) ?: return null
    val opening = source.text.substring(opener.start, opener.end)
    val closing = DelimiterFacts.closingText(opener)
    return ParserDiagnostic(
        range = source.range(opener.start, opener.end),
        severity = ParserSeverity.ERROR,
        message = "Unclosed '$opening': expected '$closing' before end of file.",
        code = "unclosed-delimiter",
    )
}
