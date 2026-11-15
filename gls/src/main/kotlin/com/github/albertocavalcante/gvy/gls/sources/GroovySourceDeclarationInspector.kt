// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.annotations.CompilerSourceRanges
import com.github.albertocavalcante.groovyparser.annotations.sourceTypeDeclarationName
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParserRange
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.control.Phases
import java.net.URI

private const val MAX_GROOVY_INSPECTION_CHARACTERS = 1_048_576

internal data class SourceMemberQuery(val name: String, val isStatic: Boolean? = null)

internal data class SourceMemberTarget(val binaryName: String, val member: SourceMemberQuery)

private data class InspectionText(val content: String, val checkCancelled: () -> Unit)

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
    ): SourceDefinition? = inspectMember(
        content,
        SourceMemberTarget(binaryName, SourceMemberQuery(memberName)),
        sourceUri,
    )

    fun inspectMember(
        content: String,
        target: SourceMemberTarget,
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
            parsed.ast?.classes.orEmpty().singleOrNull { it.name == target.binaryName }?.let { owner ->
                inspectOwnerMember(owner, target.member, InspectionText(content, checkCancelled))
            }
        } finally {
            // Only detached spans escape. The separate shared transform loader remains owned by the native parser.
            parsed.compilationUnit.classLoader.close()
        }
    }

    private fun inspectOwnerMember(
        owner: org.codehaus.groovy.ast.ClassNode,
        member: SourceMemberQuery,
        input: InspectionText,
    ): SourceDefinition? {
        val (content, checkCancelled) = input
        val binaryName = owner.name
        val memberName = member.name
        val memberIsStatic = member.isStatic
        val methods = owner.methods.filter { method ->
            checkCancelled()
            method.name == memberName && method.declaringClass.name == binaryName && !method.isSynthetic &&
                (memberIsStatic == null || method.isStatic == memberIsStatic)
        }
        val fields = owner.fields.filter { field ->
            checkCancelled()
            field.name == memberName && field.declaringClass.name == binaryName && !field.isSynthetic &&
                (memberIsStatic == null || field.isStatic == memberIsStatic)
        }.toMutableSet()
        owner.properties.forEach { property ->
            checkCancelled()
            if (property.name == memberName && property.declaringClass.name == binaryName &&
                (memberIsStatic == null || property.field.isStatic == memberIsStatic)
            ) fields.add(property.field)
        }
        if (methods.size + fields.size != 1) return null
        if (fields.isNotEmpty()) return fieldDefinition(fields.single(), content, checkCancelled)

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

    private fun fieldDefinition(field: FieldNode, content: String, checkCancelled: () -> Unit): SourceDefinition? {
        val ranges = CompilerSourceRanges(content)
        val declaration = ranges.range(field) ?: return null
        val start = ranges.offset(declaration.start)
        val initializer = field.initialExpression?.let { ranges.range(it) ?: return null }
        val end = initializer?.let { ranges.offset(it.start) } ?: ranges.offset(declaration.end)
        var offset = start
        var name: ParserRange? = null
        while (offset < end) {
            checkCancelled()
            when {
                content.startsWith("/*", offset) -> {
                    val commentEnd = content.indexOf("*/", offset + 2)
                    if (commentEnd < 0 || commentEnd + 2 > end) return null
                    offset = commentEnd + 2
                }
                content.startsWith("//", offset) -> offset = lineCommentEnd(content, offset, end)
                content[offset] == '\'' || content[offset] == '"' -> return null
                else -> {
                    val identifierEnd = groovyIdentifierEnd(content, offset, end)
                    if (identifierEnd == null) {
                        offset += Character.charCount(content.codePointAt(offset))
                    } else {
                        if (content.substring(offset, identifierEnd) == field.name) {
                            if (name != null) return null
                            name = ParserRange(ranges.positionAt(offset), ranges.positionAt(identifierEnd))
                        }
                        offset = identifierEnd
                    }
                }
            }
        }
        return name?.let { SourceDefinition(declaration.toSourceSpan(), it.toSourceSpan()) }
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
