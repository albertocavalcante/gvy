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

    /**
     * The lines of [unused] imports that can safely be removed, by the rules above. Lines that go anyway
     * ([alreadyGone], and the removable lines themselves) do not count as mentions, so two imports that only
     * mention each other are removed together and a second pass has nothing left to remove.
     */
    fun removableLines(text: String, unused: Collection<UnusedImport>, alreadyGone: Set<Int> = emptySet()): Set<Int> {
        val source = SourceLines(text)
        var removable = emptySet<Int>()
        do {
            val previous = removable
            val ignored = alreadyGone + previous
            removable = unused
                .filter { candidate -> isSafeToRemove(source, candidate, ignored) }
                .map { it.line }
                .toSet()
        } while (removable != previous)
        return removable
    }

    /**
     * Non-overlapping edits in ascending offset order; empty when nothing can safely be removed. Consecutive removed
     * lines become one edit. Each deletes whole lines with their line breaks; when that would leave a blank line at
     * the top of the file or two in a row, the blank line that follows goes too.
     */
    fun plan(
        text: String,
        unused: Collection<UnusedImport>,
        alreadyGone: Set<Int> = emptySet(),
    ): List<TextReplacement> {
        val source = SourceLines(text)
        val removable = removableLines(text, unused, alreadyGone).toSortedSet()
        val gone = removable.toMutableSet()
        for (line in removable) {
            if (shouldDropFollowingBlank(source, gone, line)) gone += line + 1
        }
        return runsOf(gone.sorted()).map { run -> source.deletion(run.first, run.last) }
    }

    /** A blank line is dropped when the lines around it would leave two blank lines, or one at the top. */
    private fun shouldDropFollowingBlank(source: SourceLines, gone: Set<Int>, line: Int): Boolean {
        val endsRun = line + 1 !in gone
        var above = line - 1
        while (above in gone) above--
        val blankOrTopAbove = above < 0 || source.isBlank(above)
        return endsRun && blankOrTopAbove && source.isBlank(line + 1) && source.exists(line + 2)
    }

    private fun runsOf(sortedLines: List<Int>): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        for (line in sortedLines) {
            val last = runs.lastOrNull()
            if (last != null && last.last + 1 == line) {
                runs[runs.lastIndex] = last.first..line
            } else {
                runs += line..line
            }
        }
        return runs
    }

    private fun isSafeToRemove(source: SourceLines, candidate: UnusedImport, ignoredLines: Set<Int>): Boolean {
        val parsed = source.lineText(candidate.line)?.let(ImportLineParser::parse) ?: return false
        val names = bindingNames(candidate.target, parsed) ?: return false
        return parsed.target == candidate.target &&
            parsed.trailing.isEmpty() &&
            !source.hasCommentAbove(candidate.line) &&
            names.none { name -> source.isMentionedOutsideLine(name, candidate.line, ignoredLines) }
    }

    /** The names code may use to refer to the import; null for imports that are never removed. */
    private fun bindingNames(target: ImportTarget, parsed: ImportLine): Set<String>? = when (target) {
        is ImportTarget.Type -> setOf(target.bindingName) + parsed.writtenNames
        is ImportTarget.StaticMember -> setOf(target.bindingName, target.member) + propertyNameOf(target.member)
        is ImportTarget.Star,
        is ImportTarget.StaticStar,
        -> null
    }

    /** Groovy resolves a bare `foo` to a statically imported `getFoo`, `isFoo` or `setFoo`. */
    private fun propertyNameOf(member: String): Set<String> {
        val accessor = ACCESSOR.matchEntire(member) ?: return emptySet()
        val property = accessor.groupValues[1]
        return setOf(property, property.replaceFirstChar { it.lowercaseChar() })
    }

    private val ACCESSOR = Regex("(?:get|is|set)(\\p{Lu}.*)")
}

/** A text seen line by line, with the questions removal asks of it. */
private class SourceLines(private val text: String) {
    private val index = Utf16LineIndex(text)

    /** Where each identifier occurs, so a name is looked up instead of scanning the text again. */
    private val identifiers: Map<String, List<Int>> by lazy {
        val found = mutableMapOf<String, MutableList<Int>>()
        IDENTIFIER.findAll(text).forEach { found.getOrPut(it.value) { mutableListOf() } += it.range.first }
        found
    }

    fun exists(line: Int): Boolean = line >= 0 && index.lineLength(line) != null

    fun lineText(line: Int): String? {
        val length = if (line >= 0) index.lineLength(line) else null
        return length?.let { text.substring(index.lineStart(line), index.lineStart(line) + it) }
    }

    fun isBlank(line: Int): Boolean = lineText(line)?.isBlank() == true

    fun hasCommentAbove(line: Int): Boolean {
        val above = lineText(line - 1)?.trim() ?: return false
        val endsBlockComment = above.endsWith("*/") && ImportLineParser.parse(above) == null
        return above.startsWith("//") || above.startsWith("/*") || above.startsWith("*") || endsBlockComment
    }

    fun isMentionedOutsideLine(name: String, line: Int, ignoredLines: Set<Int>): Boolean {
        val lineStart = index.lineStart(line)
        val lineEnd = lineStart + (index.lineLength(line) ?: 0)
        return identifiers[name].orEmpty().any { offset ->
            val outsideOwnLine = offset < lineStart || offset >= lineEnd
            outsideOwnLine && index.lineAt(offset) !in ignoredLines
        }
    }

    /** Deletes lines [first]..[last] with their line breaks. */
    fun deletion(first: Int, last: Int): TextReplacement {
        val end = if (exists(last + 1)) index.lineStart(last + 1) else text.length
        return TextReplacement(index.lineStart(first), end, "")
    }

    private companion object {
        val IDENTIFIER = Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*")
    }
}
