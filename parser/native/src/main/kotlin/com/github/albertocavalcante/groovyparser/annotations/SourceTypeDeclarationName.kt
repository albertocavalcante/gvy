// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.annotations

import com.github.albertocavalcante.nativeapi.ParserRange
import com.github.albertocavalcante.nativeapi.SourceImportRanges
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode

/** Select the written imported type/member, verified inside an AST-confirmed import statement. */
internal fun sourceImportSelectionRange(
    node: ImportNode,
    source: String,
    ranges: CompilerSourceRanges,
    range: ParserRange,
): SourceImportRanges? {
    val limit = ranges.offset(range.end)
    val offset = node.annotations.mapNotNull { ranges.range(it)?.end?.let(ranges::offset) }
        .maxOrNull() ?: ranges.offset(range.start)
    val cursor = SourceTextCursor(source, ranges, limit, offset)
    val keyword = cursor.identifier("import")
    val staticKeywordMatches = !node.isStatic || (keyword != null && cursor.identifier("static") != null)
    val parts = node.className?.split('.')?.plus(listOfNotNull(node.fieldName.takeIf { node.isStatic }))
    val selection = if (keyword != null && staticKeywordMatches) parts?.let(cursor::qualifiedIdentifier) else null
    return if (keyword != null && selection != null) {
        SourceImportRanges(ParserRange(keyword.start, range.end), selection)
    } else {
        null
    }
}

/** Proves a compiler-declared type's header after its original annotations, never searches the body. */
fun sourceTypeDeclarationName(
    node: ClassNode,
    source: String,
    ranges: CompilerSourceRanges,
    declarationRange: ParserRange,
): ParserRange? {
    val limit = ranges.offset(declarationRange.end)
    val offset = node.annotations.mapNotNull { ranges.range(it)?.end?.let(ranges::offset) }
        .maxOrNull() ?: ranges.offset(declarationRange.start)
    val cursor = SourceTextCursor(source, ranges, limit, offset)
    val keyword = cursor.typeDeclarationKeyword() ?: return null
    if (node.isAnnotationDefinition != keyword.isAnnotation) return null
    val expectedName = node.outerClass?.let { node.name.removePrefix(it.name + '$') } ?: node.nameWithoutPackage
    return cursor.identifier(expectedName)
}

/** Identifier-only pieces of an AST-confirmed qualified type name; comments and separators are never clickable. */
internal fun sourceTypeNameSegments(
    source: String,
    ranges: CompilerSourceRanges,
    range: ParserRange,
): List<ParserRange>? {
    val limit = ranges.offset(range.end)
    var offset = ranges.offset(range.start)
    val result = mutableListOf<ParserRange>()
    var valid = true
    var complete = false
    while (offset < limit && valid && !complete) {
        val start = skipTrivia(source, offset, limit)
        val end = start?.let { identifierEnd(source, it, limit) }
        if (start == null || end == null) {
            valid = false
        } else {
            result.add(ParserRange(ranges.positionAt(start), ranges.positionAt(end)))
            val next = skipTrivia(source, end, limit)
            if (next == null) {
                valid = false
            } else if (next == limit) {
                offset = next
                complete = true
            } else if (source[next] == '.') {
                offset = next + 1
            } else {
                valid = false
            }
        }
    }
    return result.takeIf { valid && complete }
}

private data class TypeDeclarationKeyword(val isAnnotation: Boolean)

private class SourceTextCursor(
    private val source: String,
    private val ranges: CompilerSourceRanges,
    private val limit: Int,
    private var offset: Int,
) {
    fun identifier(expected: String? = null): ParserRange? {
        val start = skipTrivia(source, offset, limit)
        val end = start?.let { identifierEnd(source, it, limit) }
        val matches = start != null && end != null &&
            (expected == null || source.substring(start, end) == expected)
        if (matches) {
            val tokenStart = checkNotNull(start)
            val tokenEnd = checkNotNull(end)
            offset = tokenEnd
            return ParserRange(ranges.positionAt(tokenStart), ranges.positionAt(tokenEnd))
        }
        return null
    }

    fun qualifiedIdentifier(parts: List<String>): ParserRange? {
        var selection: ParserRange? = null
        var valid = true
        for ((index, part) in parts.withIndex()) {
            val hasSeparator = index == 0 || consumeSeparator()
            val identifier = if (hasSeparator) identifier(part) else null
            if (identifier == null) {
                valid = false
                break
            }
            selection = identifier
        }
        return selection.takeIf { valid }
    }

    fun typeDeclarationKeyword(): TypeDeclarationKeyword? {
        var declarationOffset = skipTrivia(source, offset, limit)
        var valid = declarationOffset != null
        var foundTypeKeyword = false
        while (declarationOffset != null && valid) {
            val currentOffset = declarationOffset
            if (currentOffset >= limit || source[currentOffset] == '@' || foundTypeKeyword) break
            val end = identifierEnd(source, currentOffset, limit)
            if (end == null) {
                valid = false
            } else {
                val word = source.substring(currentOffset, end)
                if (word in TYPE_KEYWORDS) {
                    foundTypeKeyword = true
                } else if (word !in TYPE_MODIFIERS) {
                    valid = false
                } else {
                    declarationOffset = skipTrivia(source, end, limit)
                    valid = declarationOffset != null
                }
            }
        }

        val annotation = declarationOffset?.let { currentOffset ->
            currentOffset < limit && source[currentOffset] == '@'
        } == true
        val keywordStart = declarationOffset?.let { start ->
            if (annotation) skipTrivia(source, start + 1, limit) else start
        }
        val keywordEnd = keywordStart?.let { identifierEnd(source, it, limit) }
        val keywordName = if (keywordStart != null && keywordEnd != null) {
            source.substring(keywordStart, keywordEnd)
        } else {
            null
        }
        val recognized = keywordName != null &&
            (annotation && keywordName == "interface" || !annotation && keywordName in TYPE_KEYWORDS)
        if (!valid || keywordEnd == null || !recognized) return null
        offset = keywordEnd
        return TypeDeclarationKeyword(annotation)
    }

    private fun consumeSeparator(): Boolean {
        val separator = skipTrivia(source, offset, limit)
        val found = separator != null && separator < limit && source[separator] == '.'
        if (found) offset = checkNotNull(separator) + 1
        return found
    }
}

private fun identifierEnd(source: String, start: Int, limit: Int): Int? {
    if (start >= limit || !Character.isJavaIdentifierStart(source.codePointAt(start))) return null
    var offset = start + Character.charCount(source.codePointAt(start))
    while (offset < limit && Character.isJavaIdentifierPart(source.codePointAt(offset))) {
        offset += Character.charCount(source.codePointAt(offset))
    }
    return offset
}

private fun skipTrivia(source: String, start: Int, limit: Int): Int? {
    var offset = start
    while (offset < limit) {
        when {
            source[offset].isWhitespace() -> offset++
            source.startsWith("/*", offset) -> {
                val end = source.indexOf("*/", offset + 2)
                if (end < 0 || end + 2 > limit) return null
                offset = end + 2
            }
            source.startsWith("//", offset) -> {
                while (offset < limit && source[offset] != '\r' && source[offset] != '\n') offset++
            }
            else -> return offset
        }
    }
    return offset
}

private val TYPE_MODIFIERS = setOf(
    "public",
    "protected",
    "private",
    "static",
    "abstract",
    "final",
    "strictfp",
    "sealed",
)
private val TYPE_KEYWORDS = setOf("class", "interface", "trait", "enum", "record")
