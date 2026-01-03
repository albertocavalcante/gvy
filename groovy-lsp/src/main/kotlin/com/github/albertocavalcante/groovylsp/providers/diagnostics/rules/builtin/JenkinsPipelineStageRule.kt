package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.builtin

import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.AbstractDiagnosticRule
import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.RuleContext
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import java.net.URI

/**
 * Detects missing required parameters in Jenkins pipeline stage declarations.
 *
 * Jenkins pipelines require certain parameters in stage blocks, and this rule
 * helps catch common mistakes like missing agent or steps blocks.
 *
 * NOTE: This is a simplified pattern-based check. A full AST-based
 * implementation would provide more accurate detection.
 */
class JenkinsPipelineStageRule : AbstractDiagnosticRule() {

    private companion object {
        private const val LOOK_AHEAD_LINES = 10
    }

    override val id = "jenkins-stage-structure"

    override val description = "Detect incomplete or malformed Jenkins pipeline stage declarations"

    override val analysisType = DiagnosticAnalysisType.HEURISTIC

    override val defaultSeverity = DiagnosticSeverity.Warning

    override val enabledByDefault = true

    override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext): List<Diagnostic> {
        // Only analyze Jenkinsfile or files in vars/ directory
        if (!uri.path.contains("Jenkinsfile") && !uri.path.contains("/vars/")) {
            return emptyList()
        }

        val diagnostics = mutableListOf<Diagnostic>()
        val lines = content.lines()

        // Find stage declarations
        val stagePattern = Regex("""stage\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\{""")

        lines.forEachIndexed { lineIndex, line ->
            val match = stagePattern.find(line)
            if (match != null) {
                val stageName = match.groupValues[1]

                // Check if the stage has steps (simple heuristic)
                // Look ahead a few lines to see if there's a steps block
                val endLine = minOf(lineIndex + LOOK_AHEAD_LINES, lines.size)
                val blockContent = lines.subList(lineIndex, endLine).joinToString("\n")

                if (!blockContent.contains("steps") && !blockContent.contains("script")) {
                    diagnostics.add(
                        diagnostic(
                            lineIndex,
                            match.range.first,
                            match.range.last + 1,
                            "Stage '$stageName' may be missing 'steps' or 'script' block",
                            defaultSeverity,
                        ),
                    )
                }
            }
        }

        return diagnostics
    }
}
