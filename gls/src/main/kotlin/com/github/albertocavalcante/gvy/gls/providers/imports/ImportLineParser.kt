// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.imports

/** One physical line read as a single-line import statement. */
internal data class ImportLine(
    val target: ImportTarget,
    /** What follows the statement and its optional `;`: blank, or one complete comment. */
    val trailing: String,
    /** The last name segment as written, and its `$`-separated parts, which target normalization would lose. */
    val writtenNames: Set<String>,
)

/**
 * Lexical reader for single-line `import` statements. It recognizes only what it fully understands, so a
 * statement split over several lines, or followed by code, is not an import to the callers.
 */
internal object ImportLineParser {
    private const val MAX_LINE_LENGTH = 1_000
    private const val IDENT = "[\\p{L}_$][\\p{L}\\p{N}_$]*"
    private val STATEMENT = Regex(
        "^\\s*import\\s+(static\\s+)?($IDENT(?:\\s*\\.\\s*$IDENT)*)(\\s*\\.\\s*\\*)?" +
            "(?:\\s+as\\s+($IDENT))?[ \\t]*(;)?(.*)$",
    )
    private val WHITESPACE = Regex("\\s+")

    /** [line] is one line's content without its line break; null when it is not a complete import statement. */
    fun parse(line: String): ImportLine? {
        val match = if (line.length <= MAX_LINE_LENGTH) STATEMENT.matchEntire(line) else null
        val trailing = match?.groupValues?.get(TRAILING)?.trim().orEmpty()
        val target = if (match != null && isBlankOrOneComment(trailing)) targetOf(match) else null
        return target?.let { ImportLine(it, trailing, writtenNames(match?.groupValues?.get(NAME).orEmpty())) }
    }

    private fun isBlankOrOneComment(trailing: String): Boolean = trailing.isEmpty() ||
        trailing.startsWith("//") ||
        (trailing.startsWith("/*") && trailing.indexOf("*/", startIndex = 2) == trailing.length - 2)

    private fun writtenNames(dottedName: String): Set<String> {
        val last = dottedName.replace(WHITESPACE, "").substringAfterLast('.')
        return setOf(last) + last.split('$').filter { it.isNotEmpty() }
    }

    private fun targetOf(match: MatchResult): ImportTarget? {
        val isStatic = match.groupValues[STATIC].isNotEmpty()
        val name = match.groupValues[NAME].replace(WHITESPACE, "")
        val isStar = match.groupValues[STAR].isNotEmpty()
        val alias = match.groupValues[ALIAS].ifEmpty { null }
        return when {
            isStar && alias != null -> null
            isStar && isStatic -> ImportTarget.StaticStar(name)
            isStar -> ImportTarget.Star(name)
            isStatic -> staticMember(name, alias)
            else -> ImportTarget.Type.of(name, alias)
        }
    }

    private fun staticMember(name: String, alias: String?): ImportTarget? {
        val dot = name.lastIndexOf('.')
        if (dot < 0) return null
        return ImportTarget.StaticMember.of(name.substring(0, dot), name.substring(dot + 1), alias)
    }

    private const val STATIC = 1
    private const val NAME = 2
    private const val STAR = 3
    private const val ALIAS = 4
    private const val TRAILING = 6
}
