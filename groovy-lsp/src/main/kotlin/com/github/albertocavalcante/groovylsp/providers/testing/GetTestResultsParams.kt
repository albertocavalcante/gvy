package com.github.albertocavalcante.groovylsp.providers.testing

import com.github.albertocavalcante.reports.results.model.TestResultItem
import com.github.albertocavalcante.reports.results.model.TestResultStatus
import com.github.albertocavalcante.reports.results.model.TestResultSummary
import com.github.albertocavalcante.reports.results.model.TestResultsResponse

/**
 * Parameters for the `groovy/getTestResults` LSP request.
 *
 * @property workspaceUri URI of the workspace root
 */
data class GetTestResultsParams(val workspaceUri: String)
