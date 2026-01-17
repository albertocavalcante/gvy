package com.github.albertocavalcante.diagnostics.sarif

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import java.io.File
import java.io.OutputStream

/**
 * Converts LSP Diagnostics to SARIF format.
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
        val ruleId = extractRuleId(diagnostic)
        val ruleIndex = getOrCreateRuleIndex(ruleId, diagnostic)

        val result = SarifResult(
            ruleId = ruleId,
            ruleIndex = ruleIndex,
            level = diagnostic.severity.toSarifLevel(),
            message = SarifMessage(text = diagnostic.message),
            locations = listOf(
                SarifLocation(
                    physicalLocation = SarifPhysicalLocation(
                        artifactLocation = SarifArtifactLocation(uri = filePath),
                        region = SarifRegion(
                            // SARIF uses 1-based lines, LSP uses 0-based
                            startLine = diagnostic.range.start.line + 1,
                            startColumn = diagnostic.range.start.character + 1,
                            endLine = diagnostic.range.end.line + 1,
                            endColumn = diagnostic.range.end.character + 1,
                        ),
                    ),
                ),
            ),
        )

        results.add(result)
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

    private fun extractRuleId(diagnostic: Diagnostic): String {
        // CodeNarc diagnostics have the rule name in the code field
        val code = diagnostic.code?.get()
        return when {
            code is String && code.isNotBlank() -> code
            diagnostic.source == "codenarc" -> "CodeNarcRule"
            diagnostic.source == "groovy-compiler" -> "CompilationError"
            diagnostic.source != null -> "${diagnostic.source}Error"
            else -> "UnknownRule"
        }
    }

    private fun getOrCreateRuleIndex(ruleId: String, diagnostic: Diagnostic): Int? {
        if (ruleId in ruleIndices) {
            return ruleIndices[ruleId]
        }

        // Create a basic rule entry if we don't have one
        val rule = SarifRule(
            id = ruleId,
            name = ruleId,
            shortDescription = createRuleDescription(),
            defaultConfiguration = SarifRuleConfiguration(
                level = diagnostic.severity.toSarifLevel(),
            ),
        )
        rules[ruleId] = rule
        ruleIndices[ruleId] = rules.size - 1
        return ruleIndices[ruleId]
    }

    /**
     * Rule descriptions are provided by SarifRuleRegistry, not from individual diagnostics.
     */
    @Suppress("UnusedPrivateMember", "FunctionOnlyReturningConstant")
    private fun createRuleDescription(): SarifMessage? = null

    companion object {
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
fun DiagnosticSeverity?.toSarifLevel(): SarifLevel = when (this) {
    DiagnosticSeverity.Error -> SarifLevel.ERROR
    DiagnosticSeverity.Warning -> SarifLevel.WARNING
    DiagnosticSeverity.Information -> SarifLevel.NOTE
    DiagnosticSeverity.Hint -> SarifLevel.NOTE
    null -> SarifLevel.WARNING
}
