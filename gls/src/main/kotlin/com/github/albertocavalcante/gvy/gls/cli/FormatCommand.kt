package com.github.albertocavalcante.gvy.gls.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.mordant.terminal.Terminal
import com.github.albertocavalcante.gvy.fmt.FormatOutcome
import com.github.albertocavalcante.gvy.fmt.OpenRewriteFormatter
import java.io.File

/**
 * Formats Groovy source files using OpenRewrite.
 */
class FormatCommand : CliktCommand(name = "format") {
    override fun help(context: Context) = "Format Groovy files"

    private val files by argument()
        .file(mustExist = true, canBeDir = false)
        .multiple(required = true)

    private val terminal by requireObject<Terminal>()

    override fun run() {
        val formatter = OpenRewriteFormatter()
        for (file in files) {
            formatFile(file, formatter)
        }
    }

    private fun formatFile(file: File, formatter: OpenRewriteFormatter) {
        val input = file.readText()
        val outcome = formatter.formatChecked(input)
        if (outcome is FormatOutcome.Refused) {
            // Exit status stays 0: the printed text is the unchanged input, which is still valid output.
            echo(
                "warning: ${file.path} left unformatted: the result would change the program (${outcome.reason})",
                err = true,
            )
        }
        val formatted = outcome.text
        terminal.print(formatted)
        if (!formatted.endsWith("\n")) terminal.println()
    }
}
