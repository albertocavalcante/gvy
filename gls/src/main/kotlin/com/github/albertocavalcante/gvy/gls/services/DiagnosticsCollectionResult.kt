// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import org.eclipse.lsp4j.Diagnostic

/** Result after every enabled provider has finished, retaining diagnostics from failed providers. */
data class DiagnosticsCollectionResult(
    val diagnostics: List<Diagnostic>,
    val providerIds: List<String>,
    val failures: List<DiagnosticProviderFailure>,
) {
    /** Whether the enabled providers all finished without an internal exception. */
    val isSuccessful: Boolean
        get() = failures.isEmpty()
}

/** An ordinary provider exception; cancellation and fatal errors propagate to the caller. */
data class DiagnosticProviderFailure(val providerId: String, val exceptionType: String, val message: String?)
