package com.github.albertocavalcante.groovylsp.providers.codeaction

import org.eclipse.lsp4j.Diagnostic

/**
 * Context passed to fix handlers containing all information needed to create a fix.
 *
 * @property diagnostic The LSP diagnostic containing the violation information
 * @property content The full source content of the document
 * @property lines The source content split into lines
 * @property uriString The URI of the document being fixed
 */
data class FixContext(val diagnostic: Diagnostic, val content: String, val lines: List<String>, val uriString: String)
