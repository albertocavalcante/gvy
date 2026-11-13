// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.CreateFile
import org.eclipse.lsp4j.DeleteFile
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.net.URI
import java.util.Collections

internal enum class SourceEditTargetKind { DOCUMENT, CREATE, DELETE, RENAME }
internal data class SourceEditTarget(val uri: URI, val kind: SourceEditTargetKind)

/** Pure target inventory. Null refuses malformed or unsupported edits; commands' opaque arguments are not edits. */
internal fun sourceEditTargets(edit: WorkspaceEdit): List<SourceEditTarget>? {
    val collector = SourceEditTargetCollector()
    return if (collector.append(edit)) collector.snapshot() else null
}

internal fun sourceEditTargets(actions: List<Either<Command, CodeAction>>): List<SourceEditTarget>? {
    if (actions.size > MAX_SOURCE_EDIT_TARGETS) return null
    val collector = SourceEditTargetCollector()
    for (action in actions) {
        when {
            action.isLeft -> if (action.left == null) return null
            action.isRight -> {
                val codeAction = action.right ?: return null
                if (codeAction.edit != null && !collector.append(codeAction.edit)) return null
            }
            else -> return null
        }
    }
    return collector.snapshot()
}

private class SourceEditTargetCollector {
    private val targets = mutableListOf<SourceEditTarget>()

    fun append(edit: WorkspaceEdit): Boolean {
        val changes = edit.changes
        if (changes != null) {
            if (changes.size > MAX_SOURCE_EDIT_TARGETS) return false
            for (raw in changes.keys) if (!add(raw, SourceEditTargetKind.DOCUMENT)) return false
        }
        val operations = edit.documentChanges ?: return true
        if (operations.size > MAX_SOURCE_EDIT_TARGETS) return false
        for (operation in operations) {
            when {
                operation.isLeft -> {
                    val document = operation.left?.textDocument ?: return false
                    if (!add(document.uri, SourceEditTargetKind.DOCUMENT)) return false
                }
                operation.isRight -> if (!resource(operation.right)) return false
                else -> return false
            }
        }
        return true
    }

    fun snapshot(): List<SourceEditTarget> = Collections.unmodifiableList(targets.toList())

    private fun resource(operation: ResourceOperation?): Boolean = when (operation) {
        is CreateFile ->
            operation.javaClass == CreateFile::class.java &&
                operation.kind == "create" &&
                add(operation.uri, SourceEditTargetKind.CREATE)
        is DeleteFile ->
            operation.javaClass == DeleteFile::class.java &&
                operation.kind == "delete" &&
                add(operation.uri, SourceEditTargetKind.DELETE)
        is RenameFile ->
            operation.javaClass == RenameFile::class.java &&
                operation.kind == "rename" &&
                add(operation.oldUri, SourceEditTargetKind.RENAME) &&
                add(operation.newUri, SourceEditTargetKind.RENAME)
        else -> false
    }

    private fun add(raw: String?, kind: SourceEditTargetKind): Boolean {
        if (targets.size >= MAX_SOURCE_EDIT_TARGETS || raw.isNullOrBlank()) return false
        val uri = try {
            URI.create(raw)
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (!uri.isAbsolute || uri.rawSchemeSpecificPart.isNullOrBlank()) return false
        val isFileUri = uri.scheme.equals("file", ignoreCase = true)
        if (isFileUri) {
            if (
                uri.isOpaque ||
                uri.path?.startsWith('/') != true ||
                uri.query != null ||
                uri.fragment != null
            ) {
                return false
            }
        }
        if (kind != SourceEditTargetKind.DOCUMENT && !isFileUri) return false
        targets.add(SourceEditTarget(uri, kind))
        return true
    }
}

private const val MAX_SOURCE_EDIT_TARGETS = 16_384
