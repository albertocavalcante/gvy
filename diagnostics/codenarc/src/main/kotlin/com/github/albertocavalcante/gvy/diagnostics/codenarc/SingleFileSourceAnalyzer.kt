// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.codenarc

import org.codenarc.analyzer.AbstractSourceAnalyzer
import org.codenarc.results.DirectoryResults
import org.codenarc.results.FileResults
import org.codenarc.results.Results
import org.codenarc.ruleset.RuleSet
import org.codenarc.source.SourceFile
import java.nio.file.Path

/** Keeps SDK rule semantics and file results while allowing engine failures to propagate (#1740). */
internal class SingleFileSourceAnalyzer(private val baseDirectory: Path, private val fileName: String) :
    AbstractSourceAnalyzer() {
    override fun analyze(ruleSet: RuleSet): Results {
        val source = SourceFile(baseDirectory.resolve(fileName).toFile())
        val file = FileResults(fileName, collectViolations(source, ruleSet), source)
        // Match the SDK's relative result paths and outer container.
        val directory = DirectoryResults("").apply { addFileResultRecursive(file) }
        return DirectoryResults().apply { addChild(directory) }
    }

    override fun getSourceDirectories(): List<String> = listOf(baseDirectory.toString())
}
