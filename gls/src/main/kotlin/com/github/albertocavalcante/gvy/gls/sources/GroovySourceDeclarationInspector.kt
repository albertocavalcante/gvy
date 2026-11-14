// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.annotations.CompilerSourceRanges
import com.github.albertocavalcante.groovyparser.annotations.sourceTypeDeclarationName
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParserRange
import org.codehaus.groovy.control.Phases
import java.net.URI

private const val MAX_GROOVY_INSPECTION_CHARACTERS = 1_048_576

/** Uses native source declarations, never a regex match inside a comment, string or annotation argument. */
internal class GroovySourceDeclarationInspector {
    fun inspect(
        content: String,
        binaryName: String,
        sourceUri: URI,
        checkCancelled: () -> Unit = {},
    ): SourceDefinition? {
        checkCancelled()
        if (content.length > MAX_GROOVY_INSPECTION_CHARACTERS) return null
        val parsed = GroovyParserFacade().parse(
            ParseRequest(
                uri = sourceUri,
                content = content,
                compilePhase = Phases.CONVERSION,
                parseMode = ParseMode.MINIMAL,
                checkCancelled = checkCancelled,
            ),
        )
        return try {
            parsed.sourceAnnotations.declarations.singleOrNull { it.binaryName == binaryName }
                ?.let { declaration ->
                    SourceDefinition(declaration.declarationRange.toSourceSpan(), declaration.nameRange.toSourceSpan())
                }
                ?: fallbackDeclaration(parsed.ast?.classes.orEmpty(), content, binaryName)
        } finally {
            // Only detached spans escape. The separate shared transform loader remains owned by the native parser.
            parsed.compilationUnit.classLoader.close()
        }
    }

    private fun fallbackDeclaration(
        classes: List<org.codehaus.groovy.ast.ClassNode>,
        content: String,
        binaryName: String,
    ): SourceDefinition? {
        val node = classes.singleOrNull { it.name == binaryName } ?: return null
        val ranges = CompilerSourceRanges(content)
        return ranges.range(node)?.let { range ->
            sourceTypeDeclarationName(node, content, ranges, range)?.let { name ->
                SourceDefinition(range.toSourceSpan(), name.toSourceSpan())
            }
        }
    }

    private fun ParserRange.toSourceSpan(): SourceSpan =
        SourceSpan(start.line, start.character, end.line, end.character)
}
