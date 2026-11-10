package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.nativeapi.ParseOutcome
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/** Stable [Diagnostic.code] of the diagnostic that says a file compiled with fewer types than asked for. */
const val DEGRADED_DIAGNOSTIC_CODE = "gvy.degraded"
const val DEGRADED_DIAGNOSTIC_SOURCE = "gvy"

private val PHASE_NAMES = mapOf(
    Phases.INITIALIZATION to "INITIALIZATION",
    Phases.PARSING to "PARSING",
    Phases.CONVERSION to "CONVERSION",
    Phases.SEMANTIC_ANALYSIS to "SEMANTIC_ANALYSIS",
    Phases.CANONICALIZATION to "CANONICALIZATION",
    Phases.INSTRUCTION_SELECTION to "INSTRUCTION_SELECTION",
    Phases.CLASS_GENERATION to "CLASS_GENERATION",
    Phases.OUTPUT to "OUTPUT",
    Phases.FINALIZATION to "FINALIZATION",
)

private fun phaseName(phase: Int) = PHASE_NAMES[phase] ?: phase.toString()

private fun fileName(source: String) = source.trimEnd('/').substringAfterLast('/')

/** One line for the log: the file, requested vs achieved phase, the reason, the excluded and stubbed classes. */
fun ParseOutcome.describe(file: String): String = buildString {
    append("Degraded compile of $file: requested ${phaseName(requestedPhase)}, achieved ${phaseName(achievedPhase)}")
    fallback?.let { append(", fallback $it (${fallbackReason.orEmpty()})") }
    if (excludedSources.isNotEmpty()) {
        append(", excluded ").append(excludedSources.joinToString { "${it.source} (${it.reason})" })
    }
    if (stubbedClasses.isNotEmpty()) {
        append(", stubbed unresolved classes ").append(stubbedClasses.joinToString())
    }
    if (unlocatedClasses.isNotEmpty()) {
        // Which names fall past the cap depends on resolution order: logged so a missing-member report is diagnosable.
        append(", workspace classes past the locator cap ").append(unlocatedClasses.joinToString())
    }
}

/**
 * The Information diagnostic telling the user that types are not resolved in this file, or null when the
 * requested phase was reached. Excluded sources alone do not degrade the file, so they only add detail.
 */
fun ParseOutcome.toDegradationDiagnostic(): Diagnostic? {
    if (!typesUnresolved) return null
    val message = buildString {
        // Worded as of the last compile of this file: a cached result outlives a fix in another file.
        append("Types are not resolved in this file: its last compile fell back to ${phaseName(achievedPhase)}")
        fallbackReason?.let { append(" because $it") }
        if (excludedSources.isNotEmpty()) {
            append(". Excluded from this compile because they fail to compile: ")
            append(excludedSources.joinToString { fileName(it.source) })
        }
    }
    return Diagnostic(
        Range(Position(0, 0), Position(0, 0)),
        message,
        DiagnosticSeverity.Information,
        DEGRADED_DIAGNOSTIC_SOURCE,
        DEGRADED_DIAGNOSTIC_CODE,
    )
}
