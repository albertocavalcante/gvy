// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.model

import kotlinx.serialization.Serializable

/**
 * Represents a range in source code (from start position to end position).
 * Groovy-derived ranges are provided only when their endpoints are known UTF-16 coordinates.
 *
 * @property startLine The starting line (1-based).
 * @property startColumn The starting column (1-based).
 * @property endLine The ending line (1-based).
 * @property endColumn The ending column (1-based).
 */
@Serializable
data class RangeDto(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int)
