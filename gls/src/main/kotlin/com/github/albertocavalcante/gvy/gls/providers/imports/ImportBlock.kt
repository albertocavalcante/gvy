// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.imports

import com.github.albertocavalcante.gvy.source.Utf16LineIndex

/** One import statement of the block with the comment lines that travel with it. */
internal data class ImportEntry(
    val parsed: ImportLine,
    /** 0-based line of the statement. */
    val line: Int,
    /** Comment lines directly above the statement, with no blank line between. */
    val leadingComments: List<String>,
    /** The statement's line exactly as written, without its line break. */
    val statement: String,
) {
    val target: ImportTarget get() = parsed.target

    /** True when moving or dropping the statement would move or drop a comment. */
    val hasComment: Boolean get() = leadingComments.isNotEmpty() || parsed.trailing.isNotEmpty()
}

/**
 * The leading import region of a file: the imports after the optional shebang, header comments and `package`, up to
 * the first line that is neither an import, a comment nor blank. Later imports (scripts accept them after other
 * statements) are not part of the block.
 */
internal class ImportBlock(
    val entries: List<ImportEntry>,
    /** First line of the block, including the comments attached to its first import. */
    val firstLine: Int,
    val lastLine: Int,
    /** A comment between imports that belongs to no single import: reordering could separate it from its meaning. */
    val hasUnattributableComment: Boolean,
) {
    companion object {
        /** The block of [text], or null when it has no leading import. */
        fun parse(text: String): ImportBlock? = BlockScanner(text).scan()
    }
}

private class BlockScanner(private val text: String) {
    private val index = Utf16LineIndex(text)
    private val entries = mutableListOf<ImportEntry>()
    private val pendingComments = mutableListOf<String>()
    private var pendingStart = 0
    private var inBlockComment = false
    private var danglingComment = false
    private var unattributable = false
    private var firstLine = 0

    fun scan(): ImportBlock? {
        var line = 0
        while (index.lineLength(line) != null && accept(line)) line++
        return if (entries.isEmpty()) {
            null
        } else {
            ImportBlock(entries.toList(), firstLine, entries.last().line, unattributable)
        }
    }

    /** Reads one line; false stops the scan at it. */
    private fun accept(line: Int): Boolean {
        val start = index.lineStart(line)
        val raw = text.substring(start, start + checkNotNull(index.lineLength(line)))
        val trimmed = raw.trim()
        return when {
            inBlockComment -> acceptInsideBlockComment(line, raw, trimmed)
            trimmed.isEmpty() -> acceptBlank()
            line == 0 && trimmed.startsWith("#!") -> true
            trimmed.startsWith("//") -> acceptComment(line, raw)
            trimmed.startsWith("/*") -> acceptBlockCommentStart(line, raw, trimmed)
            entries.isEmpty() && PACKAGE.matches(trimmed) -> acceptPackage()
            else -> acceptImport(line, raw)
        }
    }

    private fun acceptBlank(): Boolean {
        if (pendingComments.isNotEmpty() && entries.isNotEmpty()) danglingComment = true
        pendingComments.clear()
        return true
    }

    private fun acceptPackage(): Boolean {
        pendingComments.clear()
        return true
    }

    private fun acceptComment(line: Int, raw: String): Boolean {
        if (pendingComments.isEmpty()) pendingStart = line
        pendingComments += raw
        return true
    }

    private fun acceptBlockCommentStart(line: Int, raw: String, trimmed: String): Boolean {
        val closesHere = trimmed.contains("*/")
        // Code after the closing marker shares the line with the comment; that is not an import block line.
        val endsWithComment = !closesHere || trimmed.endsWith("*/")
        if (!endsWithComment) return false
        acceptComment(line, raw)
        inBlockComment = !closesHere
        return true
    }

    private fun acceptInsideBlockComment(line: Int, raw: String, trimmed: String): Boolean {
        val closesHere = trimmed.contains("*/")
        if (closesHere && !trimmed.endsWith("*/")) return false
        acceptComment(line, raw)
        inBlockComment = !closesHere
        return true
    }

    private fun acceptImport(line: Int, raw: String): Boolean {
        val parsed = ImportLineParser.parse(raw) ?: return false
        if (danglingComment) unattributable = true
        if (entries.isEmpty()) firstLine = if (pendingComments.isEmpty()) line else pendingStart
        entries += ImportEntry(parsed, line, pendingComments.toList(), raw)
        pendingComments.clear()
        return true
    }

    private companion object {
        val PACKAGE = Regex("^package\\s+[\\p{L}\\p{N}_$.\\s]+;?\\s*(//.*)?$")
    }
}
