package com.github.albertocavalcante.groovylsp.version

/**
 * Inclusive Groovy version range for worker compatibility checks.
 */
data class GroovyVersionRange(val minInclusive: GroovyVersion, val maxInclusive: GroovyVersion? = null) {
    init {
        require(maxInclusive == null || maxInclusive >= minInclusive) {
            "maxInclusive must be >= minInclusive"
        }
    }

    fun contains(version: GroovyVersion): Boolean =
        version >= minInclusive && (maxInclusive == null || version <= maxInclusive)
}
