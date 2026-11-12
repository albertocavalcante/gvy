// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.semantictokens

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.ModuleNode
import java.net.URI

/** Source and AST inputs supplied together by the request boundary. */
internal data class SemanticTokenInput(
    val astModel: GroovyAstModel,
    val uri: URI,
    val unusedImports: Set<ImportNode> = emptySet(),
    val moduleNode: ModuleNode? = null,
    val sourceText: String? = null,
) {
    val sourceRanges: SemanticSourceRanges? by lazy {
        sourceText?.let { text -> SemanticSourceRanges(TextIndex(text)) { astModel.getUri(it) == uri } }
    }
}
