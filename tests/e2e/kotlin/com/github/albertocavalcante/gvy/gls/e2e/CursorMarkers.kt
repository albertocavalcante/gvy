package com.github.albertocavalcante.gvy.gls.e2e

/** A cursor marker found in scenario text; [name] is null for the anonymous `|^|` marker. */
data class CursorMarker(val name: String?, val line: Int, val character: Int) {
    val label: String get() = "|^${name.orEmpty()}|"
}

/** Text with its cursor markers removed, plus where each marker was. */
data class MarkedText(val text: String, val markers: List<CursorMarker>)

/**
 * Cursor markers let a scenario point at a position by writing `|^|` or `|^name|` in the text
 * instead of counting lines and columns by hand.
 *
 * Positions refer to the text after every marker is removed. Lines split on `\n`, `\r\n` and `\r`;
 * characters are UTF-16 code units (Kotlin string indices), the LSP default when no
 * `positionEncoding` is negotiated.
 */
object CursorMarkers {
    /** Names that would clash with the fields of a cursor (`{{cursor.line}}`, `{{cursor.uri}}`, ...). */
    val RESERVED_NAMES: Set<String> = setOf("line", "character", "uri", "path")

    private val MARKER = Regex("""\|\^([A-Za-z_][\w-]*)?\|""")

    fun strip(text: String): MarkedText {
        val matches = MARKER.findAll(text).toList()
        if (matches.isEmpty()) return MarkedText(text, emptyList())

        val stripped = StringBuilder(text.length)
        val offsets = mutableListOf<Pair<String?, Int>>()
        var copiedUpTo = 0
        matches.forEach { match ->
            stripped.append(text, copiedUpTo, match.range.first)
            copiedUpTo = match.range.last + 1
            val name = match.groups[1]?.value
            require(name !in RESERVED_NAMES) {
                "Cursor marker '${match.value}' uses a reserved name; reserved names are $RESERVED_NAMES"
            }
            offsets += name to stripped.length
        }
        stripped.append(text, copiedUpTo, text.length)

        offsets.groupBy { it.first }.entries.firstOrNull { it.value.size > 1 }?.let { (duplicate, _) ->
            throw IllegalArgumentException(
                "Cursor marker '|^${duplicate.orEmpty()}|' appears more than once in one text",
            )
        }

        val lineStarts = lineStarts(stripped)
        val markers = offsets.map { (name, offset) ->
            val line = lineStarts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
            CursorMarker(name = name, line = line, character = offset - lineStarts[line])
        }
        return MarkedText(stripped.toString(), markers)
    }

    private fun lineStarts(text: CharSequence): List<Int> {
        val starts = mutableListOf(0)
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '\n' -> starts += index + 1
                '\r' -> {
                    if (index + 1 < text.length && text[index + 1] == '\n') index++
                    starts += index + 1
                }
            }
            index++
        }
        return starts
    }
}
