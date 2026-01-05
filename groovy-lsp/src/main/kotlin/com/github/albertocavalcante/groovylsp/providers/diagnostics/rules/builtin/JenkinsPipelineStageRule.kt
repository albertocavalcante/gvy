package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.builtin

import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.AbstractDiagnosticRule
import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.DiagnosticAnalysisType
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
        private const val RANGE_END_OFFSET = 1
        private val STEPS_BLOCK_PATTERN = Regex("""\bsteps\s*\{""")
        private val SCRIPT_BLOCK_PATTERN = Regex("""\bscript\s*\{""")
    }

    override val id = "jenkins-stage-structure"

    override val description = "Detect incomplete or malformed Jenkins pipeline stage declarations"

    override val analysisType = DiagnosticAnalysisType.HEURISTIC

    override val defaultSeverity = DiagnosticSeverity.Warning

    override val enabledByDefault = true

    override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext): List<Diagnostic> {
        val path = uri.path ?: return emptyList()
        val fileName = path.substringAfterLast('/')
        // Only analyze Jenkinsfile or files in vars/ directory
        if (fileName != "Jenkinsfile" && !path.contains("/vars/")) {
            return emptyList()
        }

        val diagnostics = mutableListOf<Diagnostic>()
        val lines = content.lines()

        // Find stage declarations
        val stagePattern = Regex("""stage\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\{""")

        lines.forEachIndexed { lineIndex, line ->
            val trimmedLine = line.trimStart()
            if (
                trimmedLine.startsWith("//") ||
                trimmedLine.startsWith("/*") ||
                trimmedLine.startsWith("*")
            ) {
                return@forEachIndexed
            }

            val match = stagePattern.find(line)
            if (match != null) {
                val stageName = match.groupValues[1]

                // Check if the stage has steps (simple heuristic)
                val blockContent = collectStageBlockContent(lines, lineIndex, stagePattern)

                val hasStepsBlock = STEPS_BLOCK_PATTERN.containsMatchIn(blockContent)
                val hasScriptBlock = SCRIPT_BLOCK_PATTERN.containsMatchIn(blockContent)

                if (!hasStepsBlock && !hasScriptBlock) {
                    diagnostics.add(
                        diagnostic(
                            lineIndex,
                            match.range.first,
                            match.range.last + RANGE_END_OFFSET,
                            "Stage '$stageName' may be missing 'steps' or 'script' block",
                            defaultSeverity,
                        ),
                    )
                }
            }
        }

        return diagnostics
    }

    private fun collectStageBlockContent(lines: List<String>, startIndex: Int, stagePattern: Regex): String {
        val blockLines = mutableListOf<String>()
        val state = BlockScanState()

        for (index in startIndex until lines.size) {
            val line = lines[index]
            val shouldStopBeforeConsuming = index != startIndex && state.started && stagePattern.containsMatchIn(line)
            var shouldStop = shouldStopBeforeConsuming

            if (!shouldStopBeforeConsuming) {
                blockLines.add(line)
                state.consume(line)
                shouldStop = shouldStop || (state.started && state.depth <= 0)
            }

            if (shouldStop) {
                break
            }
        }

        if (!state.started) {
            val scanLimit = minOf(startIndex + LOOK_AHEAD_LINES, lines.size)
            return lines.subList(startIndex, scanLimit).joinToString("\n")
        }

        return blockLines.joinToString("\n")
    }

    private class BlockScanState {
        var depth: Int = 0
        var started: Boolean = false

        fun consume(line: String) {
            for (ch in line) {
                when (ch) {
                    '{' -> {
                        depth += 1
                        started = true
                    }
                    '}' -> if (started) {
                        depth -= 1
                    }
                }
            }
        }
    }
}
