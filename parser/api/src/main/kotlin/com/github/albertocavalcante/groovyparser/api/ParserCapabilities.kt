package com.github.albertocavalcante.groovyparser.api

/**
 * Describes the capabilities of a parser implementation.
 *
 * This allows consumers to choose the right parser for their use case
 * and to gracefully handle missing features.
 */
data class ParserCapabilities(
    /**
     * Whether the parser can recover from syntax errors and return partial ASTs.
     */
    val supportsErrorRecovery: Boolean = false,

    /**
     * Whether comments are preserved and attached to AST nodes.
     */
    val supportsCommentPreservation: Boolean = false,

    /**
     * Whether the parser can resolve symbols and types.
     */
    val supportsSymbolResolution: Boolean = false,

    /**
     * Whether the parser supports lossless refactoring (preserves formatting).
     */
    val supportsRefactoring: Boolean = false,

    /**
     * Whether source positions are tracked for all AST nodes.
     */
    val supportsPositionTracking: Boolean = true,
) {
    companion object {
        /**
         * Default capabilities - basic parsing only.
         */
        val BASIC = ParserCapabilities()

        /**
         * Full-featured parser with all capabilities.
         */
        val FULL = ParserCapabilities(
            supportsErrorRecovery = true,
            supportsCommentPreservation = true,
            supportsSymbolResolution = true,
            supportsRefactoring = true,
        )
    }
}
