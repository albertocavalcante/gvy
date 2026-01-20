package com.github.albertocavalcante.gvy.gls.providers.coverage

/**
 * Parameters for the `groovy/getCoverage` LSP request.
 *
 * @property workspaceUri URI of the workspace root
 */
data class GetCoverageParams(val workspaceUri: String)
