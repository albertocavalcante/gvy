package com.github.albertocavalcante.groovylsp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.terminal.Terminal
import com.github.albertocavalcante.groovylsp.Version

/**
 * Prints the gls version.
 */
class VersionCommand : CliktCommand(name = "version") {
    override fun help(context: Context) = "Print the version"

    private val terminal by requireObject<Terminal>()

    override fun run() {
        terminal.println(brightGreen("gls") + " version ${Version.current}")
    }
}
