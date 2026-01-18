package com.github.albertocavalcante.groovylsp.providers.testing

/**
 * Parameters for the `groovy/getTestResults` LSP request.
 *
 * @property workspaceUri URI of the workspace root
 */
data class GetTestResultsParams(val workspaceUri: String)
