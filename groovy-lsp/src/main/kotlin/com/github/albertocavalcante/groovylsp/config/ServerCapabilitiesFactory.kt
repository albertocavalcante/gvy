package com.github.albertocavalcante.groovylsp.config

import com.github.albertocavalcante.groovylsp.Version
import com.github.albertocavalcante.groovylsp.providers.semantictokens.JenkinsSemanticTokenProvider
import org.eclipse.lsp4j.CodeLensOptions
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.SignatureHelpOptions
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Factory for creating LSP ServerCapabilities.
 * Encapsulates all capability registration logic to keep the main server class clean.
 */
object ServerCapabilitiesFactory {

    fun createInitializeResult(): InitializeResult {
        val capabilities = ServerCapabilities().apply {
            // Text synchronization
            textDocumentSync = Either.forLeft(TextDocumentSyncKind.Full)

            // Completion support
            completionProvider = CompletionOptions().apply {
                resolveProvider = false
                triggerCharacters = listOf(".", ":", "=", "*")
            }

            // Hover support
            hoverProvider = Either.forLeft(true)

            // Definition support
            definitionProvider = Either.forLeft(true)

            // Document symbols
            documentSymbolProvider = Either.forLeft(true)

            // Workspace symbols
            workspaceSymbolProvider = Either.forLeft(true)

            // Document formatting
            documentFormattingProvider = Either.forLeft(true)

            // References
            referencesProvider = Either.forLeft(true)

            // Document highlight support
            documentHighlightProvider = Either.forLeft(true)

            // Type definition support
            typeDefinitionProvider = Either.forLeft(true)

            // Signature help support
            signatureHelpProvider = SignatureHelpOptions().apply {
                triggerCharacters = listOf("(", ",")
            }

            // Rename support
            renameProvider = Either.forLeft(true)

            // Code actions
            codeActionProvider = Either.forLeft(true)

            // Semantic tokens support
            semanticTokensProvider = createSemanticTokensOptions()

            // CodeLens support for test run/debug buttons
            codeLensProvider = CodeLensOptions().apply {
                resolveProvider = false
            }

            // Folding range support
            foldingRangeProvider = Either.forLeft(true)

            // Call hierarchy support
            callHierarchyProvider = Either.forLeft(true)

            // Inlay hints support
            inlayHintProvider = Either.forLeft(true)
        }

        val serverInfo = ServerInfo().apply {
            name = "Groovy Language Server"
            version = Version.current
        }

        return InitializeResult(capabilities, serverInfo)
    }

    private fun createSemanticTokensOptions(): SemanticTokensWithRegistrationOptions =
        SemanticTokensWithRegistrationOptions().apply {
            legend = SemanticTokensLegend().apply {
                // Token types - MUST match indices in JenkinsSemanticTokenProvider.TokenTypes
                // Source of truth is now in JenkinsSemanticTokenProvider to ensure indices stay in sync
                tokenTypes = JenkinsSemanticTokenProvider.LEGEND_TOKEN_TYPES

                // Token modifiers (bitfield)
                tokenModifiers = JenkinsSemanticTokenProvider.LEGEND_TOKEN_MODIFIERS
            }

            // Support full document semantic tokens (no delta updates yet)
            full = Either.forLeft(true)
        }
}
