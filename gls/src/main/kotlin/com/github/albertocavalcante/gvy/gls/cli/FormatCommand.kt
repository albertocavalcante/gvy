package com.github.albertocavalcante.gvy.gls.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.types.file
import com.github.albertocavalcante.gvy.fmt.FormatOutcome
import com.github.albertocavalcante.gvy.fmt.OpenRewriteFormatter
import java.io.File

/**
 * Formats Groovy source files using OpenRewrite.
 */
class FormatCommand internal constructor(private val formatSource: (String) -> FormatOutcome) :
    CliktCommand(name = "format") {
    constructor() : this(defaultFormatter())

    override fun help(context: Context) = "Format Groovy files"

    private val files by argument()
        .file(mustExist = true, canBeDir = false)
        .multiple(required = true)

    override fun run() {
        for (file in files) {
            formatFile(file)
        }
    }

    private fun formatFile(file: File) {
        val input = file.readText()
        val outcome = formatSource(input)
        if (outcome is FormatOutcome.Refused) {
            // Exit status stays 0: the printed text is the unchanged input, which is still valid output.
            echo(
                "warning: ${file.path} left unformatted: the result would change the program (${outcome.reason})",
                err = true,
            )
        }
        val formatted = outcome.text
        writeUtf8Stdout(if (formatted.endsWith("\n")) formatted else formatted + System.lineSeparator())
    }

    companion object {
        private fun defaultFormatter(): (String) -> FormatOutcome {
            val formatter by lazy { OpenRewriteFormatter() }
            return { formatter.formatChecked(it) }
        }
    }
}
