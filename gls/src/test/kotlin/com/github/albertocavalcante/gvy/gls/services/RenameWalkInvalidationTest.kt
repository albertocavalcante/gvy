// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals

/** The rename check's directory walk is only as fresh as the last watched-file event. */
class RenameWalkInvalidationTest {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val compilation = GroovyCompilationService()
    private val textDocuments = GroovyTextDocumentService(scope, compilation)
    private val workspace = GroovyWorkspaceService(compilation, compilation.symbolIndexingService, scope, textDocuments)

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a watched file event invalidates the walk and an empty one does not`() {
        workspace.didChangeWatchedFiles(DidChangeWatchedFilesParams(emptyList()))
        assertEquals(0, textDocuments.renameWalks.invalidations)

        workspace.didChangeWatchedFiles(
            DidChangeWatchedFilesParams(listOf(FileEvent("file:///w/B.groovy", FileChangeType.Created))),
        )
        assertEquals(1, textDocuments.renameWalks.invalidations)
    }
}
