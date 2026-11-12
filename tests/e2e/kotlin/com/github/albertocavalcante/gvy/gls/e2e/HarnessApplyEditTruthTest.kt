// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.testing.client.HarnessLanguageClient
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class HarnessApplyEditTruthTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `unimplemented workspace edit is rejected without pretending file changed`() {
        val file = tempDir.resolve("A.groovy")
        Files.writeString(file, "before")
        val textEdit = TextEdit(Range(Position(0, 0), Position(0, 6)), "after")
        val workspaceEdit = WorkspaceEdit().apply { changes = mapOf(file.toUri().toString() to listOf(textEdit)) }
        val request = ApplyWorkspaceEditParams().apply { edit = workspaceEdit }
        val client = HarnessLanguageClient()

        val response = client.applyEdit(request).join()

        assertThat(response.isApplied).isFalse()
        assertThat(response.failureReason).containsIgnoringCase("not supported")
        assertThat(Files.readString(file)).isEqualTo("before")
        val recorded = client.awaitNotificationDetailed("workspace/applyEdit", 0, { it === request })
        assertThat(recorded.envelope?.payload).isSameAs(request)
    }
}
