package com.github.albertocavalcante.gvy.gls.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.terminal.Terminal
import com.github.albertocavalcante.gvy.gls.Version

/**
 * Prints the gls version and the commit it was built from.
 */
class VersionCommand : CliktCommand(name = "version") {
    override fun help(context: Context) = "Print the version and build provenance"

    private val terminal by requireObject<Terminal>()

    private val json by option("--json", help = "Print build provenance as JSON").flag()

    override fun run() {
        if (json) {
            writeUtf8Stdout(Version.info.toJson().toString() + System.lineSeparator())
            return
        }
        // First line stays `gls version <token>`: tooling parses it.
        val lines = Version.info.report().lines()
        terminal.println(brightGreen("gls") + lines.first().removePrefix("gls"))
        lines.drop(1).forEach { terminal.println(it) }
    }
}
