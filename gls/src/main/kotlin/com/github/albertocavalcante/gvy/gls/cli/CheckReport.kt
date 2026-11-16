// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.cli

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import java.io.File
import java.net.URI

/**
 * Builds the SARIF `artifactLocation.uri` for [file].
 *
 * Files inside [workspace] become relative, percent-encoded URI references; files outside it (or any file when no
 * workspace is given and the path is absolute) become absolute `file:` URIs so code scanning never sees a bare
 * filesystem path.
 */
internal fun sarifArtifactUri(file: File, workspace: File?): String {
    val absolute = file.absoluteFile.normalize()
    val relative = workspace
        ?.absoluteFile
        ?.normalize()
        ?.let { runCatching { absolute.relativeTo(it) }.getOrNull() }
        ?: file.takeUnless { it.isAbsolute }?.normalize()
    val path = relative?.invariantSeparatorsPath
    return if (path == null || path == ".." || path.startsWith("../")) {
        absolute.toURI().toString()
    } else {
        // A leading "./" keeps a first segment containing ':' from being read as a URI scheme.
        val safePath = if (path.substringBefore('/').contains(':')) "./$path" else path
        URI(null, null, safePath, null).toASCIIString()
    }
}

/** Writes the plain text report to [output]; returns false (writing nothing) when [output] is null. */
internal fun writeTextReportTo(output: File?, allDiagnostics: Map<File, List<Diagnostic>>): Boolean {
    output ?: return false
    output.writeText(plainTextReport(allDiagnostics))
    return true
}

/** Plain (uncolored) text report written by `gls check -o FILE`. */
internal fun plainTextReport(allDiagnostics: Map<File, List<Diagnostic>>): String = buildString {
    for ((file, diagnostics) in allDiagnostics) {
        if (diagnostics.isEmpty()) {
            append("OK: ").append(file.path).append('\n')
        } else {
            diagnostics.forEach { diagnostic ->
                append(diagnosticLine(file, diagnostic, severityLabel(diagnostic.severity))).append('\n')
            }
        }
    }
}

internal fun diagnosticLine(file: File, diagnostic: Diagnostic, severityString: String): String {
    val line = diagnostic.range.start.line + 1
    val char = diagnostic.range.start.character + 1
    return "${file.path}:$line:$char: [$severityString] ${diagnostic.message}"
}

internal fun severityLabel(severity: DiagnosticSeverity?): String = when (severity) {
    DiagnosticSeverity.Error -> "ERROR"
    DiagnosticSeverity.Warning -> "WARNING"
    DiagnosticSeverity.Information -> "INFO"
    DiagnosticSeverity.Hint -> "HINT"
    else -> "UNKNOWN"
}
