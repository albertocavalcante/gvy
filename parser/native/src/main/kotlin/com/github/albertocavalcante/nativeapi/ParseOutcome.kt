package com.github.albertocavalcante.nativeapi

import org.codehaus.groovy.control.Phases

/** Why a parse had to settle for an earlier compile phase than the one requested. */
enum class Fallback {
    /** The compilation failed, so the parser retried at [Phases.CONVERSION]. */
    COMPILATION_FAILED,

    /** A class degraded into a script (typically an unresolved superclass), so the parser retried at CONVERSION. */
    SCRIPT_FALLBACK,
}

/** A source left out of a compile because it fails to compile; [source] is a URI or path, [reason] its first error. */
data class ExcludedSource(val source: String, val reason: String)

/**
 * How a parse got to its [ParseResult]: whether the requested phase was reached and what was set
 * aside on the way. The default is the undegraded outcome.
 */
data class ParseOutcome(
    val requestedPhase: Int = Phases.CANONICALIZATION,
    val achievedPhase: Int = requestedPhase,
    val excludedSources: List<ExcludedSource> = emptyList(),
    val fallback: Fallback? = null,
    /** The error that triggered [fallback], truncated. */
    val fallbackReason: String? = null,
    /** Fully qualified names of the classes that could not be resolved and were compiled as empty stubs. */
    val stubbedClasses: List<String> = emptyList(),
    /**
     * Classes the workspace class locator did not look up because the compile had already added its cap of
     * located files (#1425): a long hierarchy chain is cut there and the rest stays unresolved.
     */
    val unlocatedClasses: List<String> = emptyList(),
) {
    /** True when the result lacks the type resolution that was asked for. */
    val typesUnresolved: Boolean get() = achievedPhase < requestedPhase

    /** True when anything was given up to produce the result. */
    val isDegraded: Boolean get() = typesUnresolved || fallback != null || excludedSources.isNotEmpty() ||
        stubbedClasses.isNotEmpty() || unlocatedClasses.isNotEmpty()

    companion object {
        const val MAX_REASON_LENGTH = 200

        fun truncate(message: String): String = message.trim().lineSequence().first().take(MAX_REASON_LENGTH)
    }
}
