// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.CreateFile
import org.eclipse.lsp4j.DeleteFile
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceEditTargetsTest {
    @Test
    fun `all workspace edit document and resource targets remain visible including both rename ends`() {
        val changes = WorkspaceEdit(mapOf("file:///workspace/A.groovy" to emptyList()))
        assertEquals(
            listOf(SourceEditTarget(URI.create("file:///workspace/A.groovy"), SourceEditTargetKind.DOCUMENT)),
            sourceEditTargets(changes),
        )
        val operations = WorkspaceEdit(
            listOf(
                Either.forLeft<TextDocumentEdit, ResourceOperation>(
                    TextDocumentEdit(VersionedTextDocumentIdentifier("untitled:New", 1), emptyList()),
                ),
                Either.forRight<TextDocumentEdit, ResourceOperation>(CreateFile("file:///workspace/New.groovy")),
                Either.forRight<TextDocumentEdit, ResourceOperation>(DeleteFile("file:///workspace/Old.groovy")),
                Either.forRight<TextDocumentEdit, ResourceOperation>(
                    RenameFile("file:///workspace/Before.groovy", "file:///managed/After.groovy"),
                ),
            ),
        )

        val targets = assertNotNull(sourceEditTargets(operations))
        assertEquals(
            listOf(
                SourceEditTargetKind.DOCUMENT,
                SourceEditTargetKind.CREATE,
                SourceEditTargetKind.DELETE,
                SourceEditTargetKind.RENAME,
                SourceEditTargetKind.RENAME,
            ),
            targets.map { it.kind },
        )
        assertEquals(URI.create("file:///managed/After.groovy"), targets.last().uri)
    }

    @Test
    fun `malformed or unknown resource operations fail closed instead of hiding a possible managed target`() {
        val invalid = listOf<ResourceOperation>(
            CreateFile("relative.groovy"),
            DeleteFile("gvy-source://view/Type.java"),
            RenameFile("file:///workspace/Before.groovy", "file:relative"),
            CreateFile("file:///workspace/A.groovy").apply { kind = "rename" },
            object : ResourceOperation("unknown") {},
        )
        for (operation in invalid) {
            val edit = WorkspaceEdit(listOf(Either.forRight<TextDocumentEdit, ResourceOperation>(operation)))
            assertNull(sourceEditTargets(edit))
        }
        assertNull(sourceEditTargets(WorkspaceEdit(mapOf("not a uri" to emptyList()))))
        assertNull(sourceEditTargets(WorkspaceEdit(mapOf("FILE:relative" to emptyList()))))
    }

    @Test
    fun `code action edits are inspected while command-only arguments are not guessed to be edits`() {
        val command = Command("Opaque", "extension.command", listOf("file:///managed/opaque.groovy"))
        val action = CodeAction("Edit").apply {
            edit = WorkspaceEdit(mapOf("gvy-source://view/Type.java" to emptyList()))
        }
        val actions = listOf(Either.forLeft<Command, CodeAction>(command), Either.forRight<Command, CodeAction>(action))

        assertEquals(
            listOf(SourceEditTarget(URI.create("gvy-source://view/Type.java"), SourceEditTargetKind.DOCUMENT)),
            sourceEditTargets(actions),
        )
        assertTrue(assertNotNull(sourceEditTargets(listOf(Either.forLeft<Command, CodeAction>(command)))).isEmpty())
    }

    @Test
    fun `target admission is bounded across an entire edit`() {
        val edits = (0..16_384).associate { "file:///workspace/F$it.groovy" to emptyList<org.eclipse.lsp4j.TextEdit>() }
        assertNull(sourceEditTargets(WorkspaceEdit(edits)))
    }
}
