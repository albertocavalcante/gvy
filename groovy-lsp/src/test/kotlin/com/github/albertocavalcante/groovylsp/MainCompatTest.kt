package com.github.albertocavalcante.groovylsp

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.albertocavalcante.groovylsp.cli.GlsCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.ServerSocket

class MainCompatTest {

    private fun runWithContext(vararg args: String) {
        GlsCommand().parse(args.toList())
    }

    @Test
    fun `test stdio command is available`() {
        // verify help works
        assertThrows<CliktError> {
            // --help throws PrintHelpMessage which is a CliktError
            runWithContext("stdio", "--help")
        }
    }

    @Test
    fun `test socket command is available`() {
        assertThrows<CliktError> {
            runWithContext("socket", "--help")
        }
    }

    @Test
    fun `test socket command arg validation`() {
        assertThrows<CliktError> {
            runWithContext("socket", "not-a-number")
        }
    }

    @Test
    fun `test socket command fails if port is bound`() {
        // Find a free port and bind it
        ServerSocket(0).use { serverSocket ->
            val port = serverSocket.localPort

            // Now try to run socket command on same port
            // It should fail with ProgramResult(1) (as per LspCommand.runSocket catch block)
            // or exit.
            assertThrows<ProgramResult> {
                runWithContext("socket", port.toString())
            }
        }
    }
}
