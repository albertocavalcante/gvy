package com.github.albertocavalcante.gvy.gls.utils

/** UTF-16 offsets for a Java/Groovy identifier inside an explicit source bound. */
internal fun sourceIdentifierEnd(text: String, start: Int, limit: Int = text.length): Int? {
    if (start < 0 || start >= limit || limit > text.length) return null
    val first = text.codePointAt(start)
    if (!Character.isJavaIdentifierStart(first) || Character.charCount(first) > limit - start) return null
    var at = start
    while (at < limit) {
        val point = text.codePointAt(at)
        val width = Character.charCount(point)
        if (!Character.isJavaIdentifierPart(point) || width > limit - at) break
        at += width
    }
    return at
}

/** Skips only whitespace and closed/remaining comments, without crossing [limit]. */
internal fun skipSourceTrivia(text: String, start: Int, limit: Int = text.length): Int {
    var at = start
    while (at < limit) {
        at = when {
            text[at].isWhitespace() -> at + 1
            text.startsWith("/*", at) -> blockCommentEnd(text, at, limit)
            text.startsWith("//", at) -> lineCommentEnd(text, at, limit)
            else -> return at
        }
    }
    return at
}

private fun blockCommentEnd(text: String, start: Int, limit: Int): Int {
    val end = text.indexOf("*/", start + 2)
    return if (end < 0 || end + 2 > limit) limit else end + 2
}

private fun lineCommentEnd(text: String, start: Int, limit: Int): Int {
    val end = text.indexOfAny(charArrayOf('\r', '\n'), start + 2)
    return if (end < 0 || end >= limit) limit else end
}
