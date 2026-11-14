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
            parsed.ast?.classes.orEmpty().singleOrNull { it.name == binaryName }?.let { owner ->
                inspectOwnerMember(owner, binaryName, memberName, content, checkCancelled)
            }
        } finally {
            // Only detached spans escape. The separate shared transform loader remains owned by the native parser.
            parsed.compilationUnit.classLoader.close()
        }
    }

    private fun inspectOwnerMember(
        owner: org.codehaus.groovy.ast.ClassNode,
        binaryName: String,
        memberName: String,
        content: String,
        checkCancelled: () -> Unit,
    ): SourceDefinition? {
        val methods = owner.methods.filter { method ->
            checkCancelled()
            method.name == memberName && method.declaringClass.name == binaryName && !method.isSynthetic
        }
        if (hasSameNameStaticFieldOrProperty(owner, binaryName, memberName, checkCancelled)) return null

        return methods.singleOrNull()?.let { method ->
            val ranges = CompilerSourceRanges(content)
            ranges.range(method)?.let { declarationRange ->
                methodNameRange(method, content, ranges, declarationRange, checkCancelled)?.let { nameRange ->
                    SourceDefinition(declarationRange.toSourceSpan(), nameRange.toSourceSpan())
                }
            }
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

    private fun hasSameNameStaticFieldOrProperty(
        owner: org.codehaus.groovy.ast.ClassNode,
        binaryName: String,
        memberName: String,
        checkCancelled: () -> Unit,
    ): Boolean {
        val hasField = owner.fields.any { field ->
            checkCancelled()
            field.name == memberName && field.isStatic && field.declaringClass.name == binaryName && !field.isSynthetic
        }
        if (hasField) return true
        return owner.properties.any { property ->
            checkCancelled()
            property.name == memberName && property.field.isStatic && property.declaringClass.name == binaryName
        }
    }

    private fun methodNameRange(
        method: MethodNode,
        content: String,
        ranges: CompilerSourceRanges,
        declarationRange: ParserRange,
        checkCancelled: () -> Unit,
    ): ParserRange? {
        val searchRange = methodNameSearchRange(method, ranges, declarationRange) ?: return null
        return findMethodName(content, ranges, method.name, searchRange, checkCancelled)
    }

    private fun methodNameSearchRange(
        method: MethodNode,
        ranges: CompilerSourceRanges,
        declarationRange: ParserRange,
    ): OffsetRange? {
        var latestAnnotationEnd: Int? = null
        for (annotation in method.annotations) {
            val annotationEnd = ranges.range(annotation)?.end ?: return null
            val annotationEndOffset = ranges.offset(annotationEnd)
            latestAnnotationEnd = latestAnnotationEnd?.let { maxOf(it, annotationEndOffset) } ?: annotationEndOffset
        }
        val start = latestAnnotationEnd ?: ranges.offset(declarationRange.start)
        val bodyStart = method.code?.let { code -> ranges.range(code)?.let { ranges.offset(it.start) } }
        if (method.code != null && bodyStart == null) return null
        val end = bodyStart ?: ranges.offset(declarationRange.end)
        return OffsetRange(start, end).takeIf { it.start < it.endExclusive }
    }

    private fun findMethodName(
        content: String,
        ranges: CompilerSourceRanges,
        expectedName: String,
        searchRange: OffsetRange,
        checkCancelled: () -> Unit,
    ): ParserRange? {
        var offset = searchRange.start
        var lastIdentifier: ParserRange? = null
        var result: ParserRange? = null
        var searching = true
        while (offset < searchRange.endExclusive && searching) {
            checkCancelled()
            when {
                content[offset].isWhitespace() -> offset++
                content.startsWith("/*", offset) -> {
                    val commentEnd = content.indexOf("*/", offset + 2)
                    if (commentEnd < 0 || commentEnd + 2 > searchRange.endExclusive) {
                        searching = false
                    } else {
                        offset = commentEnd + 2
                    }
                }
                content.startsWith("//", offset) -> {
                    offset = lineCommentEnd(content, offset, searchRange.endExclusive)
                }
                content[offset] == '(' -> {
                    result = lastIdentifier?.takeIf { ranges.text(it) == expectedName }
                    searching = false
                }
                content[offset] == '\'' || content[offset] == '"' -> searching = false
                else -> {
                    val identifierEnd = groovyIdentifierEnd(content, offset, searchRange.endExclusive)
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
        return result
    }

    private fun lineCommentEnd(content: String, start: Int, endExclusive: Int): Int {
        var offset = start
        while (offset < endExclusive && content[offset] != '\r' && content[offset] != '\n') offset++
        return offset
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

    private data class OffsetRange(val start: Int, val endExclusive: Int)
}
