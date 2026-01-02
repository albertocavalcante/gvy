package com.github.albertocavalcante.groovylsp.version

/**
 * Parsed Groovy version with a comparison strategy suitable for worker selection.
 *
 * NOTE: We avoid SemVer libraries (e.g., kotlin-semver) because Groovy versions
 * include non-SemVer qualifiers like "-indy", "-SNAPSHOT", and "-rc", plus
 * custom ordering needs (SNAPSHOT > release) that don't match SemVer rules.
 */
data class GroovyVersion(
    val raw: String,
    val major: Int,
    val minor: Int,
    val patch: Int,
    private val parts: List<Int>,
) : Comparable<GroovyVersion> {

    override fun compareTo(other: GroovyVersion): Int {
        val maxSize = maxOf(parts.size, other.parts.size)
        for (index in 0 until maxSize) {
            val left = parts.getOrNull(index) ?: DEFAULT_PART
            val right = other.parts.getOrNull(index) ?: DEFAULT_PART
            if (left != right) {
                return left.compareTo(right)
            }
        }
        return 0
    }

    companion object {
        fun parse(raw: String?): GroovyVersion? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val tokens = trimmed.split(DELIMITER_REGEX)
            if (tokens.isEmpty()) return null

            val numericTokens = tokens.takeWhile { it.toIntOrNull() != null }
            val major = numericTokens.getOrNull(0)?.toIntOrNull() ?: return null
            val minor = numericTokens.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = numericTokens.getOrNull(2)?.toIntOrNull() ?: 0
            val parts = tokens.map(::parsePart)

            return GroovyVersion(trimmed, major, minor, patch, parts)
        }

        private fun parsePart(part: String): Int = part.toIntOrNull() ?: when (part.lowercase()) {
            "snapshot" -> SNAPSHOT_VERSION
            "alpha" -> ALPHA_VERSION
            "beta" -> BETA_VERSION
            "rc" -> RC_VERSION
            else -> DEFAULT_PART
        }

        private const val SNAPSHOT_VERSION = 999
        private const val ALPHA_VERSION = -3
        private const val BETA_VERSION = -2
        private const val RC_VERSION = -1
    }
}

private val DELIMITER_REGEX = Regex("[.-]")
private const val DEFAULT_PART = 0
