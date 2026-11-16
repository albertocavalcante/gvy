// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.codenarc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TempTreeCleanupTest {
    @Test
    fun `registered hook removes a non-empty tree`(@TempDir parent: Path) {
        val dir = Files.createDirectory(parent.resolve("codenarc-lsp-x"))
        Files.writeString(Files.createDirectory(dir.resolve("rulesets")).resolve("abc.xml"), "<ruleset/>")
        val registered = mutableListOf<Thread>()

        val hook = registerTreeCleanup(dir) { registered += it }
        assertEquals(listOf(hook), registered)
        assertEquals(true, Files.exists(dir))

        hook.run()

        assertFalse(Files.exists(dir))
    }
}
