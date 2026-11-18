// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.imports

import com.github.albertocavalcante.gvy.source.Utf16LineIndex

/** Replaces the UTF-16 offsets `[start, end)` of the source text with [newText]. */
internal data class TextReplacement(val start: Int, val end: Int, val newText: String)

/**
 * Plans the removal of imports that the compiler-backed detector proved unused.
 *
 * Pure: it reads the text and the detector's verdict and returns offset edits. Removal is deliberately narrower than
 * the detector. An import is removed only when its whole statement can be recognized on one line, and nothing the
 * author may have meant to keep goes with it:
 *
 * - a star import is never removed;
 * - a comment on the import's line, or a comment line directly above it, keeps the import;
 * - an import whose binding name appears anywhere else in the file (code, strings, Groovydoc, comments) is kept,
 *   because the detector cannot see dynamic or documentation use.
 */
internal object UnusedImportRemoval {

    /** Non-overlapping edits in ascending offset order; empty when nothing can safely be removed. */
    fun plan(text: String, unused: Collection<UnusedImport>): List<TextReplacement> {
        val index = Utf16LineIndex(text)
        return unused
            .sortedBy { it.line }
            .distinctBy { it.line }
            .filter { candidate -> isSafeToRemove(text, index, candidate) }
            .map { candidate -> removalOf(text, index, candidate.line) }
    }

    private fun isSafeToRemove(text: String, index: Utf16LineIndex, candidate: UnusedImport): Boolean {
        val names = bindingNames(candidate.target) ?: return false
        val parsed = lineText(text, index, candidate.line)?.let(ImportLineParser::parse) ?: return false
        return parsed.target == candidate.target &&
            parsed.trailing.isEmpty() &&
            !hasCommentAbove(text, index, candidate.line) &&
            names.none { name -> isMentionedOutsideLine(text, index, candidate.line, name) }
    }

    /** The names code may use to refer to the import; null for imports that are never removed. */
    private fun bindingNames(target: ImportTarget): Set<String>? = when (target) {
        is ImportTarget.Type -> setOf(target.bindingName, target.qualifiedName.substringAfterLast('.'))
        is ImportTarget.StaticMember -> setOf(target.bindingName, target.member)
        is ImportTarget.Star,
        is ImportTarget.StaticStar,
        -> null
    }

    private fun lineText(text: String, index: Utf16LineIndex, line: Int): String? {
        val length = if (line >= 0) index.lineLength(line) else null
        return length?.let { text.substring(index.lineStart(line), index.lineStart(line) + it) }
    }

    private fun hasCommentAbove(text: String, index: Utf16LineIndex, line: Int): Boolean {
        val above = if (line > 0) lineText(text, index, line - 1)?.trim() else null
        return above != null && (above.startsWith("//") || above.startsWith("/*") || above.startsWith("*"))
    }

    private fun isMentionedOutsideLine(text: String, index: Utf16LineIndex, line: Int, name: String): Boolean {
        val lineStart = index.lineStart(line)
        val lineEnd = lineStart + (index.lineLength(line) ?: 0)
        val word = Regex("(?<![\\p{L}\\p{N}_$])${Regex.escape(name)}(?![\\p{L}\\p{N}_$])")
        return word.findAll(text).any { match -> match.range.first < lineStart || match.range.first >= lineEnd }
    }

    /**
     * Deletes the whole line with its line break. When that would leave two blank lines in a row, or a blank line
     * at the top of the file, the blank line that follows goes too.
     */
    private fun removalOf(text: String, index: Utf16LineIndex, line: Int): TextReplacement {
        val start = index.lineStart(line)
        var lastRemoved = line
        val followedByBlank = lineText(text, index, line + 1)?.isBlank() == true
        val precededByBlankOrTop = line == 0 || lineText(text, index, line - 1)?.isBlank() == true
        if (followedByBlank && precededByBlankOrTop) lastRemoved++
        val end = if (index.lineLength(lastRemoved + 1) != null) index.lineStart(lastRemoved + 1) else text.length
        return TextReplacement(start, end, "")
    }
}
