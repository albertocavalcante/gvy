// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.imports

import com.github.albertocavalcante.gvy.source.Utf16LineIndex

/**
 * Organizes a file's leading import block: drops provably unused imports, drops exact duplicates, sorts, groups by
 * an [ImportLayout] and separates groups with one blank line. Pure: text and the detector's verdict in, offset
 * edits out. The block keeps the file's own line break.
 *
 * Safety rules, on top of those of [UnusedImportRemoval]:
 *
 * - a comment that belongs to no single import disables reordering; only provably unused imports go;
 * - a duplicate that carries a comment is kept, so no comment is lost;
 * - imports after the first non-import statement stay where they are, and are removed only when provably unused.
 *
 * Applying the edits and organizing again yields no edits.
 */
internal object ImportOrganizer {

    /** Non-overlapping edits in ascending offset order; empty when the text is already organized. */
    fun organize(
        text: String,
        unused: Collection<UnusedImport>,
        layout: ImportLayout = ImportLayout.DEFAULT,
    ): List<TextReplacement> {
        val block = ImportBlock.parse(text)
        if (block == null || block.hasUnattributableComment) return UnusedImportRemoval.plan(text, unused)

        val region = BlockRegion.around(text, block)
        // Duplicates are syntactic, so they go first; their lines then no longer count as mentions of a name.
        val (survivors, duplicates) = splitDuplicates(sorted(block.entries, layout))
        val goneAnyway = duplicates.map { it.entry.line }.toSet()
        val removable = UnusedImportRemoval.removableLines(text, unused, alreadyGone = goneAnyway)
        val kept = survivors.filter { it.entry.line !in removable || it.entry.hasComment }
        val sections = sections(kept, layout)

        val rewritten = region.rewrite(sections)
        val blockEdit = TextReplacement(region.startOffset, region.endOffset, rewritten)
            .takeIf { edit -> text.substring(edit.start, edit.end) != edit.newText }
        val laterImports = unused.filter { it.line < region.firstLine || it.line > region.lastLine }
        val laterRemovals = UnusedImportRemoval.plan(text, laterImports, alreadyGone = goneAnyway + removable)
        return (listOfNotNull(blockEdit) + laterRemovals).sortedBy { it.start }
    }

    private data class SortKey(val owner: String, val member: String, val alias: String)

    private class Placed(val group: Int, val entry: ImportEntry, val key: SortKey)

    private fun sortKey(target: ImportTarget): SortKey = when (target) {
        is ImportTarget.Type -> SortKey(target.qualifiedName, "", target.alias.orEmpty())
        // A star import sorts with its package, before the types in it.
        is ImportTarget.Star -> SortKey(target.packageName + ".", "", "")
        is ImportTarget.StaticMember -> SortKey(target.className, target.member, target.alias.orEmpty())
        is ImportTarget.StaticStar -> SortKey(target.className, "", "")
    }

    /** Case-sensitive by fully qualified name; an alias sorts by its target. Ties keep their original order. */
    private fun sorted(entries: List<ImportEntry>, layout: ImportLayout): List<Placed> = entries
        .map { entry -> Placed(layout.groupIndexOf(entry.target) ?: layout.entries.size, entry, sortKey(entry.target)) }
        .sortedWith(compareBy({ it.group }, { it.key.owner }, { it.key.member }, { it.key.alias }))

    /**
     * Splits off exact duplicates: the same binding, written the same way. A duplicate that carries a comment is
     * kept, so no comment is lost. `Map.Entry` and `Map$Entry` bind different names and are not duplicates.
     */
    private fun splitDuplicates(placed: List<Placed>): Pair<List<Placed>, List<Placed>> {
        val seen = mutableSetOf<Pair<ImportTarget, Set<String>>>()
        return placed.partition { seen.add(it.entry.target to it.entry.parsed.writtenNames) || it.entry.hasComment }
    }

    /** Groups sit in layout order; groups with no blank line between them form one section. */
    private fun sections(placed: List<Placed>, layout: ImportLayout): List<List<String>> {
        fun linesOf(group: Int) = placed
            .filter { it.group == group }
            .flatMap { it.entry.leadingComments + it.entry.statement }

        val sections = mutableListOf<List<String>>()
        var current = emptyList<String>()
        layout.entries.forEachIndexed { position, layoutEntry ->
            when (layoutEntry) {
                is LayoutEntry.Group -> current = current + linesOf(position)
                LayoutEntry.BlankLine -> {
                    sections += current
                    current = emptyList()
                }
            }
        }
        sections += current
        sections += linesOf(layout.entries.size)
        return sections.filter { it.isNotEmpty() }
    }
}

/**
 * The span an organized block replaces: the block's lines plus the blank lines around it, so the result has exactly
 * one blank line before it (after `package` or a header) and one after it (before code).
 */
private data class BlockRegion(
    val firstLine: Int,
    val lastLine: Int,
    val startOffset: Int,
    val endOffset: Int,
    private val hasContentBefore: Boolean,
    private val hasContentAfter: Boolean,
    /** The line break that ended the region's last line; empty at the end of a file without a final break. */
    private val lastBreak: String,
    /** Joins the lines of the new block: the break before the region, so the file's own breaks are never merged. */
    private val eol: String,
) {
    /** The text that replaces the region for the given non-empty sections of import lines. */
    fun rewrite(sections: List<List<String>>): String {
        if (sections.isEmpty()) return if (hasContentBefore && hasContentAfter) eol else ""
        val body = sections.joinToString(eol + eol) { it.joinToString(eol) }
        val before = if (hasContentBefore) eol else ""
        // With code after the block, a blank line separates them; otherwise the block keeps its own final break.
        val closing = if (hasContentAfter) eol + eol else lastBreak
        return before + body + closing
    }

    companion object {
        fun around(text: String, block: ImportBlock): BlockRegion {
            val index = Utf16LineIndex(text)
            fun exists(line: Int) = index.lineLength(line) != null
            fun isBlank(line: Int): Boolean {
                val length = index.lineLength(line) ?: return false
                return text.substring(index.lineStart(line), index.lineStart(line) + length).isBlank()
            }
            fun breakAfter(line: Int): String {
                val end = index.lineStart(line) + (index.lineLength(line) ?: 0)
                return if (exists(line + 1)) text.substring(end, index.lineStart(line + 1)) else ""
            }

            var first = block.firstLine
            while (first > 0 && isBlank(first - 1)) first--
            var next = block.lastLine + 1
            while (isBlank(next)) next++
            val hasContentAfter = exists(next)
            val last = if (hasContentAfter) next - 1 else block.lastLine

            val eol = if (first > 0) {
                breakAfter(first - 1)
            } else {
                (first..last).map(::breakAfter).firstOrNull { it.isNotEmpty() }
                    ?: (0..block.lastLine).map(::breakAfter).firstOrNull { it.isNotEmpty() }
                    ?: "\n"
            }
            return BlockRegion(
                firstLine = first,
                lastLine = last,
                startOffset = index.lineStart(first),
                endOffset = if (exists(last + 1)) index.lineStart(last + 1) else text.length,
                hasContentBefore = first > 0,
                hasContentAfter = hasContentAfter,
                lastBreak = breakAfter(last),
                eol = eol,
            )
        }
    }
}
