package com.github.albertocavalcante.groovylsp.cli.jenkins

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * Parent command for Jenkins Pipeline development tools.
 *
 * Usage:
 *   gls jenkins extract --plugins-txt plugins.txt
 *   gls jenkins validate plugins.txt
 *
 * TODO: In a future refactor, the CLI layer should be decoupled from the LSP implementation.
 * This would allow shipping `gls` as a standalone CLI tool and `groovy-lsp-server` as a
 * separate JAR. For now, they are bundled together for simplicity.
 */
class JenkinsCommand : CliktCommand(name = "jenkins") {

    override fun help(context: Context) = "Jenkins Pipeline development tools"

    init {
        subcommands(
            ExtractCommand(),
            ValidateCommand(),
        )
    }

    override fun run() {
        // This is just a container command; subcommands do the work
    }
}
