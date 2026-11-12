// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.testing

/**
 * Parameters for the `groovy/getTestResults` LSP request.
 *
 * @property workspaceUri URI of the workspace root
 */
data class GetTestResultsParams(val workspaceUri: String)
