// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.testing

/**
 * Parameters for the `groovy/getTestResults` LSP request.
 *
 * @property workspaceUri URI of the workspace root
 * @property modifiedSinceMillis When set (epoch milliseconds), only reports written at or after it are read, so
 *   a report left by an earlier run cannot be mistaken for the current one
 */
data class GetTestResultsParams(val workspaceUri: String, val modifiedSinceMillis: Long? = null)
