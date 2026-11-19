// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.imports

import com.github.albertocavalcante.gvy.gls.providers.diagnostics.UnusedImportDetector
import com.github.albertocavalcante.nativeapi.ParseResult
import org.codehaus.groovy.ast.ImportNode

/**
 * Adapts the compiler-backed [UnusedImportDetector] verdict to the pure [UnusedImport] facts.
 * This is the only place the import actions ask what is unused; there is no second detector.
 */
internal object UnusedImportFacts {

    /**
     * The imports proven unused in [parsed]. Empty when the parse has errors: unusedness is unprovable on a
     * broken AST, so no import is offered for removal.
     */
    fun from(parsed: ParseResult): List<UnusedImport> {
        val module = parsed.ast?.takeIf { parsed.isSuccessful } ?: return emptyList()
        val annotations = parsed.sourceAnnotations
        return UnusedImportDetector.detectUnusedImports(module, annotations).mapNotNull { node ->
            val line = annotations.importRange(node)?.start?.line
            val target = targetOf(node)
            if (line != null && target != null) UnusedImport(target, line) else null
        }
    }

    private fun targetOf(node: ImportNode): ImportTarget? {
        val className = node.className ?: return null
        val fieldName = node.fieldName
        return when {
            node.isStatic && fieldName != null -> ImportTarget.StaticMember.of(className, fieldName, node.alias)
            node.isStatic -> null
            else -> ImportTarget.Type.of(className, node.alias)
        }
    }
}
