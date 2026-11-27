// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.test

import com.github.albertocavalcante.gvy.gls.version.GroovyVersion
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionInfo
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionSource

fun parseGroovyVersion(raw: String): GroovyVersion =
    requireNotNull(GroovyVersion.parse(raw)) { "Failed to parse version: $raw" }

/** A Groovy version for tests that select a worker and do not care which version it serves. */
val TEST_GROOVY_VERSION: GroovyVersionInfo = GroovyVersionInfo(parseGroovyVersion("4.0.0"), GroovyVersionSource.RUNTIME)
