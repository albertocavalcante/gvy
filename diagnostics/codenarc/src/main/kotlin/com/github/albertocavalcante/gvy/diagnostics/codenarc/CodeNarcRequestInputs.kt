// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.codenarc

import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** The ruleset, notices and sanitized properties actually used by one CLI provider invocation. */
class CodeNarcRequestInputs {
    private val checks = mutableListOf<() -> Boolean>()

    fun provider(context: WorkspaceContext, resolver: HierarchicalRulesetResolver): CodeNarcDiagnosticProvider {
        val observed = object : DetailedRulesetResolver by resolver {
            override fun resolveDetailed(context: WorkspaceContext, file: URI?): RulesetResolution = observe {
                val hashes = mutableMapOf<Path, String>()
                val effective = resolver.resolveDetailed(context, file) { path, hash -> hashes[path] = hash }
                effective to hashes
            }.first
        }
        val analyzer = DefaultCodeAnalyzer().apply {
            readProperties = { file ->
                observe {
                    var hash: String? = null
                    val properties = PropertiesSanitizer.sanitize(file) { bytes ->
                        hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    }
                    properties to hash
                }.first
            }
        }
        return CodeNarcDiagnosticProvider(context, rulesetResolver = observed, codeAnalyzer = analyzer)
    }

    fun isCurrent(): Boolean = checks.all { it() }

    private fun <T> observe(read: () -> T): T = read().also { selected -> checks.add { read() == selected } }
}
