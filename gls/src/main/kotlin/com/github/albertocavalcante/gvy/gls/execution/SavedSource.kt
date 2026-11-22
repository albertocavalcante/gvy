// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.execution

import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.SourceUnit
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

internal data class SavedSource(val path: Path, val content: String, val entries: List<FileEntryPoint>)

internal fun savedSource(uriText: String, content: String): ExecutionStep<SavedSource> =
    sourcePath(uriText).flatMap { path -> readSource(path, content).flatMap { parseSource(path, it) } }

private fun sourcePath(uriText: String): ExecutionStep<Path> = try {
    val uri = URI.create(uriText)
    require(uri.scheme == "file")
    ExecutionStep.Value(executionPath(Path.of(uri)))
} catch (_: IllegalArgumentException) {
    executionStop("Save the document to a local file first.")
}

private fun readSource(path: Path, content: String): ExecutionStep<String> = try {
    val saved = Files.readString(path)
    val hosted = hostedDslResponse(path.fileName.toString())
    when {
        saved != content -> executionStop("Save the current document before execution.")
        hosted != null -> ExecutionStep.Stop(hosted)
        else -> ExecutionStep.Value(saved)
    }
} catch (_: java.io.IOException) {
    executionStop("Cannot read the saved file.")
}

private fun parseSource(path: Path, saved: String): ExecutionStep<SavedSource> = try {
    val entries = SourceUnit.create(path.fileName.toString(), saved).run {
        parse()
        completePhase()
        convert()
        discoverEntryPoints(ast)
    }
    ExecutionStep.Value(SavedSource(path, saved, entries))
} catch (_: CompilationFailedException) {
    executionStop("Fix syntax errors before running or compiling this file.")
}
