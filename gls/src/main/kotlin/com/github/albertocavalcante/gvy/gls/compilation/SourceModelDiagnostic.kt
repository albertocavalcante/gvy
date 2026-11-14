// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

const val SOURCE_MODEL_DIAGNOSTIC_CODE = "gvy.sourceModel"

/** Null observations are inapplicable, rather than evidence that a build-model operation failed. */
fun CompilationResult.sourceModelMessage(): String? = when {
    sourceMembershipKnown == false ->
        "Project source membership is not yet known. Analysis uses inferred sources; refresh dependencies to retry."
    sourceVisibilityKnown == false ->
        "Project dependency source ownership is not fully known. " +
            "Source lookup retains recovery for unknown dependencies."
    else -> null
}

fun CompilationResult.toSourceModelDiagnostic(): Diagnostic? {
    val message = sourceModelMessage() ?: return null
    return Diagnostic(
        Range(Position(0, 0), Position(0, 0)),
        message,
        DiagnosticSeverity.Information,
        "gvy",
        SOURCE_MODEL_DIAGNOSTIC_CODE,
    )
}
