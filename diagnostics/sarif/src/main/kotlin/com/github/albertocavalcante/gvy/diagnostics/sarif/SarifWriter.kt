// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.sarif

import com.github.albertocavalcante.gvy.findings.Finding
import com.github.albertocavalcante.gvy.findings.FindingCode
import com.github.albertocavalcante.gvy.findings.FindingSeverity
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.CharacterCodingException

/**
 * Builds SARIF from findings, with a compatibility entry point for LSP diagnostics.
 *
 * Supports both CodeNarc diagnostics (identified by source="codenarc") and
 * compiler diagnostics (source="groovy-compiler" or others).
 */
class SarifWriter(
    private val toolName: String = "groovy-lsp",
    private val toolVersion: String? = null,
    private val toolUri: String = "https://github.com/albertocavalcante/gvy",
) {
    private val results = mutableListOf<SarifResult>()
    private val rules = mutableMapOf<String, SarifRule>()
    private val ruleIndices = mutableMapOf<String, Int>()

    /**
     * Adds a diagnostic for a file to the SARIF output.
     *
     * @param filePath Relative or absolute path to the file
     * @param diagnostic The LSP diagnostic
     */
    fun addDiagnostic(filePath: String, diagnostic: Diagnostic) {
        // The legacy caller supplies a filesystem path, not a URI reference. Preserve it until
        // its host-specific path adapter is migrated; do not reinterpret it as a URI here.
        val locations = listOf(
            SarifLocation(
                physicalLocation = SarifPhysicalLocation(
                    artifactLocation = SarifArtifactLocation(uri = filePath),
                    region = SarifRegion(
                        startLine = diagnostic.range.start.line.toLong() + 1,
                        startColumn = diagnostic.range.start.character.toLong() + 1,
                        endLine = diagnostic.range.end.line.toLong() + 1,
                        endColumn = diagnostic.range.end.character.toLong() + 1,
                    ),
                ),
            ),
        )
        val code = (diagnostic.code?.get() as? String)?.let { FindingCode.Text(it) }
        val finding = Finding(
            null,
            diagnostic.message,
            diagnostic.severity.toFindingSeverity(),
            diagnostic.source,
            code,
        )
        appendFinding(finding, locations)
    }

    /**
     * Adds a finding. The location URI is interpreted as a URI reference, including an empty one;
     * non-ASCII characters are encoded as UTF-8. Invalid references fail before the writer changes.
     * Existing escapes and the supplied Unicode normalization form are preserved.
     * SARIF rule IDs are strings, so numeric and textual codes with the same spelling coincide.
     * An unspecified severity is projected as a warning.
     *
     * @throws IllegalArgumentException if the reference has invalid syntax or malformed UTF-16.
     */
    fun addFinding(finding: Finding) {
        val locations = finding.location?.let { location ->
            val uri = asciiUriReference(location.uri)
            val region = location.range?.let { range ->
                SarifRegion(
                    startLine = range.start.line.toLong() + 1,
                    startColumn = range.start.character.toLong() + 1,
                    endLine = range.endExclusive.line.toLong() + 1,
                    endColumn = range.endExclusive.character.toLong() + 1,
                )
            }
            listOf(SarifLocation(physicalLocation = SarifPhysicalLocation(SarifArtifactLocation(uri), region)))
        }
        appendFinding(finding, locations)
    }

    private fun asciiUriReference(reference: String): String {
        val bytes = try {
            URI(reference) // Validate syntax without normalizing the supplied reference.
            if (reference.all { it.code < ASCII_LIMIT }) return reference
            reference.encodeToByteArray(throwOnInvalidSequence = true)
        } catch (error: URISyntaxException) {
            throw IllegalArgumentException("Invalid source URI reference", error)
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("Invalid source URI reference", error)
        }
        val hex = "0123456789ABCDEF"
        return buildString {
            for (byte in bytes) {
                val value = byte.toInt() and UNSIGNED_BYTE_MASK
                if (value < ASCII_LIMIT) {
                    append(value.toChar())
                } else {
                    append('%')
                    append(hex[value ushr HEX_DIGIT_BITS])
                    append(hex[value and HEX_DIGIT_MASK])
                }
            }
        }
    }

    private fun appendFinding(finding: Finding, locations: List<SarifLocation>?) {
        val ruleId = when (val code = finding.code) {
            is FindingCode.Text -> code.value.takeIf { it.isNotBlank() }
            is FindingCode.Number -> code.value.toString()
            null -> null
        } ?: fallbackRuleId(finding.source)
        val level = finding.severity.toSarifLevel()
        val ruleIndex = getOrCreateRuleIndex(ruleId, level)
        results.add(
            SarifResult(
                ruleId = ruleId,
                ruleIndex = ruleIndex,
                level = level,
                message = SarifMessage(text = finding.message),
                locations = locations,
            ),
        )
    }

    /**
     * Adds multiple diagnostics for a file.
     */
    fun addDiagnostics(filePath: String, diagnostics: List<Diagnostic>) {
        diagnostics.forEach { addDiagnostic(filePath, it) }
    }

    /**
     * Registers a rule with full metadata.
     * Call this before adding diagnostics if you have rule details.
     */
    fun registerRule(rule: SarifRule) {
        if (rule.id !in rules) {
            rules[rule.id] = rule
            ruleIndices[rule.id] = rules.size - 1
        }
    }

    /**
     * Builds the SARIF output structure.
     */
    fun build(): SarifOutput {
        val driver = SarifDriver(
            name = toolName,
            version = toolVersion,
            informationUri = toolUri,
            rules = rules.values.toList().ifEmpty { null },
        )

        val run = SarifRun(
            tool = SarifTool(driver = driver),
            results = results,
            // Source ranges and legacy LSP positions count UTF-16 code units.
            columnKind = "utf16CodeUnits",
        )

        return SarifOutput(runs = listOf(run))
    }

    /**
     * Writes SARIF output to a string.
     */
    fun toJson(prettyPrint: Boolean = true): String = createJson(prettyPrint).encodeToString(build())

    /**
     * Writes SARIF output to a file.
     */
    fun writeTo(file: File, prettyPrint: Boolean = true) {
        file.writeText(toJson(prettyPrint))
    }

    /**
     * Writes SARIF output to an output stream.
     */
    fun writeTo(outputStream: OutputStream, prettyPrint: Boolean = true) {
        outputStream.write(toJson(prettyPrint).toByteArray())
    }

    private fun fallbackRuleId(source: String?): String = when (source) {
        "codenarc" -> "CodeNarcRule"
        "groovy-compiler" -> "CompilationError"
        null -> "UnknownRule"
        else -> "${source}Error"
    }

    private fun getOrCreateRuleIndex(ruleId: String, level: SarifLevel): Int? {
        if (ruleId in ruleIndices) {
            return ruleIndices[ruleId]
        }

        // Create a basic rule entry if we don't have one
        val rule = SarifRule(
            id = ruleId,
            name = ruleId,
            shortDescription = createRuleDescription(),
            defaultConfiguration = SarifRuleConfiguration(
                level = level,
            ),
        )
        rules[ruleId] = rule
        ruleIndices[ruleId] = rules.size - 1
        return ruleIndices[ruleId]
    }

    /**
     * Rule descriptions are provided by SarifRuleRegistry, not from individual diagnostics.
     */
    @Suppress("FunctionOnlyReturningConstant")
    private fun createRuleDescription(): SarifMessage? = null

    companion object {
        private const val ASCII_LIMIT = 0x80
        private const val UNSIGNED_BYTE_MASK = 0xff
        private const val HEX_DIGIT_MASK = 0x0f
        private const val HEX_DIGIT_BITS = 4

        @OptIn(ExperimentalSerializationApi::class)
        private fun createJson(prettyPrint: Boolean): Json = Json {
            this.prettyPrint = prettyPrint
            prettyPrintIndent = "    "
            // Encode default values for root-level fields like $schema and version
            encodeDefaults = true
            // Don't include nulls in the output to keep it clean
            explicitNulls = false
        }
    }
}

/**
 * Extension function to convert LSP DiagnosticSeverity to SARIF level.
 */
fun DiagnosticSeverity?.toSarifLevel(): SarifLevel = toFindingSeverity().toSarifLevel()

/** An absent producer severity is represented as a SARIF warning. */
private fun FindingSeverity?.toSarifLevel(): SarifLevel = when (this) {
    FindingSeverity.ERROR -> SarifLevel.ERROR
    FindingSeverity.WARNING -> SarifLevel.WARNING
    FindingSeverity.INFORMATION, FindingSeverity.HINT -> SarifLevel.NOTE
    null -> SarifLevel.WARNING
}

private fun DiagnosticSeverity?.toFindingSeverity(): FindingSeverity? = when (this) {
    DiagnosticSeverity.Error -> FindingSeverity.ERROR
    DiagnosticSeverity.Warning -> FindingSeverity.WARNING
    DiagnosticSeverity.Information -> FindingSeverity.INFORMATION
    DiagnosticSeverity.Hint -> FindingSeverity.HINT
    null -> null
}
