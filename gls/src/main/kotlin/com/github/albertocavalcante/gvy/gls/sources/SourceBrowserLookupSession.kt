// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

/** One request's held compilation context and accumulated selected-origin guards; never cached or persisted. */
class SourceBrowserLookupSession(
    val locateType: suspend (String) -> SourceLookupResult,
    val isCurrent: () -> Boolean,
    val validateCurrent: suspend () -> Boolean = { isCurrent() },
)
