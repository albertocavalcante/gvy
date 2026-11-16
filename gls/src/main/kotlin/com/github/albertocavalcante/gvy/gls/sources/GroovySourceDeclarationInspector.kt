// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.annotations.CompilerSourceRanges
import com.github.albertocavalcante.groovyparser.annotations.sourceTypeDeclarationName
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParserRange
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.control.Phases
import java.net.URI

private const val MAX_GROOVY_INSPECTION_CHARACTERS = 1_048_576

internal data class SourceMemberQuery(val name: String, val isStatic: Boolean? = null)

internal data class SourceMemberTarget(val binaryName: String, val member: SourceMemberQuery)

private data class InspectionText(val content: String, val checkCancelled: () -> Unit)

private fun matchesMember(
    member: SourceMemberQuery,
    name: String,
    declaringClassName: String,
    isSynthetic: Boolean,
    isStatic: Boolean,
    ownerName: String,
): Boolean {
    if (name != member.name) return false
    if (declaringClassName != ownerName || isSynthetic) return false
    return matchesStaticQuery(member.isStatic, isStatic)
}

private fun matchesProperty(
    member: SourceMemberQuery,
    name: String,
    declaringClassName: String,
    isStatic: Boolean,
    ownerName: String,
): Boolean {
    if (name != member.name) return false
    if (declaringClassName != ownerName) return false
    return matchesStaticQuery(member.isStatic, isStatic)
}

private fun matchesStaticQuery(requested: Boolean?, actual: Boolean): Boolean = requested == null || actual == requested

private data class FieldNameScanState(val offset: Int, val name: ParserRange?, val valid: Boolean = true)

private fun findFieldNameRange(
    fieldName: String,
    content: String,
    ranges: CompilerSourceRanges,
    start: Int,
    end: Int,
    checkCancelled: () -> Unit,
): ParserRange? {
    var state = FieldNameScanState(start, null)
    while (state.offset < end && state.valid) {
        checkCancelled()
        state = advanceFieldNameScan(fieldName, content, ranges, end, state)
    }
    return state.name.takeIf { state.valid }
}

private fun advanceFieldNameScan(
    fieldName: String,
    content: String,
    ranges: CompilerSourceRanges,
    end: Int,
    state: FieldNameScanState,
): FieldNameScanState = when {
    content.startsWith("/*", state.offset) -> {
        val commentEnd = blockCommentEnd(content, state.offset, end)
        if (commentEnd == null) state.copy(valid = false) else state.copy(offset = commentEnd)
    }
    content.startsWith("//", state.offset) ->
        state.copy(offset = lineCommentEnd(content, state.offset, end))
    content[state.offset] == '\'' || content[state.offset] == '"' -> state.copy(valid = false)
    else -> advanceFieldIdentifier(fieldName, content, ranges, end, state)
}

private fun blockCommentEnd(content: String, start: Int, end: Int): Int? =
    content.indexOf("*/", start + 2).takeIf { it >= 0 && it + 2 <= end }?.plus(2)

private fun advanceFieldIdentifier(
    fieldName: String,
    content: String,
    ranges: CompilerSourceRanges,
    end: Int,
    state: FieldNameScanState,
): FieldNameScanState {
    val identifierEnd = groovyIdentifierEnd(content, state.offset, end)
        ?: return state.copy(offset = state.offset + Character.charCount(content.codePointAt(state.offset)))
    return when {
        content.substring(state.offset, identifierEnd) != fieldName -> state.copy(offset = identifierEnd)
        state.name != null -> state.copy(valid = false)
        else -> state.copy(
            offset = identifierEnd,
            name = ParserRange(ranges.positionAt(state.offset), ranges.positionAt(identifierEnd)),
        )
    }
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
        owner: ClassNode,
        member: SourceMemberQuery,
        input: InspectionText,
    ): SourceDefinition? {
        val (content, checkCancelled) = input
        val methods = matchingMethods(owner, member, checkCancelled)
        val fields = matchingFields(owner, member, checkCancelled)
        return when {
            methods.size + fields.size != 1 -> null
            fields.isNotEmpty() -> fieldDefinition(fields.single(), content, checkCancelled)
            else -> methods.singleOrNull()?.let { method -> methodDefinition(method, content, checkCancelled) }
        }
    }

    private fun matchingMethods(
        owner: ClassNode,
        member: SourceMemberQuery,
        checkCancelled: () -> Unit,
    ): List<MethodNode> = owner.methods.filter { method ->
        checkCancelled()
        matchesMember(
            member,
            method.name,
            method.declaringClass.name,
            method.isSynthetic,
            method.isStatic,
            owner.name,
        )
    }

    private fun matchingFields(
        owner: ClassNode,
        member: SourceMemberQuery,
        checkCancelled: () -> Unit,
    ): Set<FieldNode> {
        val fields = owner.fields.filter { field ->
            checkCancelled()
            matchesMember(
                member,
                field.name,
                field.declaringClass.name,
                field.isSynthetic,
                field.isStatic,
                owner.name,
            )
        }.toMutableSet()
        owner.properties.forEach { property ->
            checkCancelled()
            val matches = matchesProperty(
                member,
                property.name,
                property.declaringClass.name,
                property.field.isStatic,
                owner.name,
            )
            if (matches) {
                fields.add(property.field)
            }
        }
        return fields
    }

    private fun methodDefinition(
        method: MethodNode,
        content: String,
        checkCancelled: () -> Unit,
    ): SourceDefinition? {
        val ranges = CompilerSourceRanges(content)
        return ranges.range(method)?.let { declarationRange ->
            methodNameRange(method, content, ranges, declarationRange, checkCancelled)?.let { nameRange ->
                SourceDefinition(declarationRange.toSourceSpan(), nameRange.toSourceSpan())
            }
        }
    }

    private fun fallbackDeclaration(
        classes: List<ClassNode>,
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
        val initialExpression = field.initialExpression
        val initializer = initialExpression?.let(ranges::range)
        if (initialExpression != null && initializer == null) return null
        val start = ranges.offset(declaration.start)
        val end = initializer?.let { ranges.offset(it.start) } ?: ranges.offset(declaration.end)
        return findFieldNameRange(field.name, content, ranges, start, end, checkCancelled)?.let { name ->
            SourceDefinition(declaration.toSourceSpan(), name.toSourceSpan())
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

    private fun ParserRange.toSourceSpan(): SourceSpan =
        SourceSpan(start.line, start.character, end.line, end.character)

    private data class OffsetRange(val start: Int, val endExclusive: Int)
}
