// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.annotations.CompilerSourceRanges
import com.github.albertocavalcante.groovyparser.annotations.sourceTypeDeclarationName
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParserRange
import org.codehaus.groovy.ast.MethodNode
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

    fun inspectMember(
        content: String,
        binaryName: String,
        memberName: String,
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
            val owner = parsed.ast?.classes.orEmpty().singleOrNull { it.name == binaryName } ?: return null
            val methods = owner.methods.filter { method ->
                checkCancelled()
                method.name == memberName && method.declaringClass.name == binaryName && !method.isSynthetic
            }
            val method = methods.singleOrNull() ?: return null
            val ranges = CompilerSourceRanges(content)
            val declarationRange = ranges.range(method) ?: return null
            val nameRange = methodNameRange(method, memberName, content, ranges, declarationRange, checkCancelled)
                ?: return null
            SourceDefinition(declarationRange.toSourceSpan(), nameRange.toSourceSpan())
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

    private fun methodNameRange(
        method: MethodNode,
        expectedName: String,
        content: String,
        ranges: CompilerSourceRanges,
        declarationRange: ParserRange,
        checkCancelled: () -> Unit,
    ): ParserRange? {
        val start = method.annotations.map { annotation ->
            val annotationRange = ranges.range(annotation) ?: return null
            ranges.offset(annotationRange.end)
        }.maxOrNull() ?: ranges.offset(declarationRange.start)
        val end = method.code?.let { code ->
            val codeRange = ranges.range(code) ?: return null
            ranges.offset(codeRange.start)
        } ?: ranges.offset(declarationRange.end)
        if (start >= end) return null

        var offset = start
        var lastIdentifier: ParserRange? = null
        while (offset < end) {
            checkCancelled()
            when {
                content[offset].isWhitespace() -> offset++
                content.startsWith("/*", offset) -> {
                    val commentEnd = content.indexOf("*/", offset + 2)
                    if (commentEnd < 0 || commentEnd + 2 > end) return null
                    offset = commentEnd + 2
                }
                content.startsWith("//", offset) -> {
                    while (offset < end && content[offset] != '\r' && content[offset] != '\n') offset++
                }
                content[offset] == '(' -> {
                    val nameRange = lastIdentifier ?: return null
                    return nameRange.takeIf { ranges.text(it) == expectedName }
                }
                content[offset] == '\'' || content[offset] == '"' -> return null
                else -> {
                    val identifierEnd = groovyIdentifierEnd(content, offset, end)
                    if (identifierEnd == null) {
                        lastIdentifier = null
                        offset += Character.charCount(content.codePointAt(offset))
                    } else {
                        lastIdentifier = ParserRange(ranges.positionAt(offset), ranges.positionAt(identifierEnd))
                        offset = identifierEnd
                    }
                }
            }
        }
        return null
    }

    private fun groovyIdentifierEnd(content: String, start: Int, end: Int): Int? {
        val first = content.codePointAt(start)
        if (!Character.isJavaIdentifierStart(first)) return null
        var offset = start + Character.charCount(first)
        while (offset < end) {
            val codePoint = content.codePointAt(offset)
            if (!Character.isJavaIdentifierPart(codePoint)) break
            offset += Character.charCount(codePoint)
        }
        return offset
    }

    private fun ParserRange.toSourceSpan(): SourceSpan =
        SourceSpan(start.line, start.character, end.line, end.character)
}
