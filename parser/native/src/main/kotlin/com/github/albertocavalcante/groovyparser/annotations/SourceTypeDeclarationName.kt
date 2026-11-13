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
    var offset = node.annotations.mapNotNull { ranges.range(it)?.end?.let(ranges::offset) }
        .maxOrNull() ?: ranges.offset(range.start)
    fun identifier(expected: String): ParserRange? {
        val start = skipTrivia(source, offset, limit) ?: return null
        val end = identifierEnd(source, start, limit) ?: return null
        if (source.substring(start, end) != expected) return null
        offset = end
        return ParserRange(ranges.positionAt(start), ranges.positionAt(end))
    }
    val keyword = identifier("import") ?: return null
    if (node.isStatic && identifier("static") == null) return null
    val owner = node.className ?: return null
    val parts = owner.split('.') + listOfNotNull(node.fieldName.takeIf { node.isStatic })
    var selection: ParserRange? = null
    for ((index, part) in parts.withIndex()) {
        if (index > 0) {
            offset = skipTrivia(source, offset, limit) ?: return null
            if (offset >= limit || source[offset] != '.') return null
            offset++
        }
        selection = identifier(part) ?: return null
    }
    return selection?.let { SourceImportRanges(ParserRange(keyword.start, range.end), it) }
}

/** Proves a compiler-declared type's header after its original annotations, never searches the body. */
fun sourceTypeDeclarationName(
    node: ClassNode,
    source: String,
    ranges: CompilerSourceRanges,
    declarationRange: ParserRange,
): ParserRange? {
    val limit = ranges.offset(declarationRange.end)
    var offset = node.annotations.mapNotNull { ranges.range(it)?.end?.let(ranges::offset) }
        .maxOrNull() ?: ranges.offset(declarationRange.start)
    offset = skipTrivia(source, offset, limit) ?: return null
    while (offset < limit && source[offset] != '@') {
        val end = identifierEnd(source, offset, limit) ?: return null
        if (source.substring(offset, end) in TYPE_KEYWORDS) break
        if (source.substring(offset, end) !in TYPE_MODIFIERS) return null
        offset = skipTrivia(source, end, limit) ?: return null
    }
    if (offset >= limit) return null
    val annotation = source[offset] == '@'
    val keywordStart = if (annotation) skipTrivia(source, offset + 1, limit) ?: return null else offset
    val keywordEnd = identifierEnd(source, keywordStart, limit) ?: return null
    val keyword = source.substring(keywordStart, keywordEnd)
    if ((annotation && keyword != "interface") || (!annotation && keyword !in TYPE_KEYWORDS)) return null
    if (node.isAnnotationDefinition != annotation) return null
    val nameStart = skipTrivia(source, keywordEnd, limit) ?: return null
    val nameEnd = identifierEnd(source, nameStart, limit) ?: return null
    val expectedName = node.outerClass?.let { node.name.removePrefix(it.name + '$') } ?: node.nameWithoutPackage
    if (source.substring(nameStart, nameEnd) != expectedName) return null
    return ParserRange(ranges.positionAt(nameStart), ranges.positionAt(nameEnd))
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
    while (offset < limit) {
        val start = skipTrivia(source, offset, limit) ?: return null
        val end = identifierEnd(source, start, limit) ?: return null
        result.add(ParserRange(ranges.positionAt(start), ranges.positionAt(end)))
        offset = skipTrivia(source, end, limit) ?: return null
        if (offset == limit) return result
        if (source[offset] != '.') return null
        offset++
    }
    return null
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
