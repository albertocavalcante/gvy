package com.github.albertocavalcante.groovylsp.documentation

import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import org.codehaus.groovy.ast.ASTNode
import java.net.URI

/**
 * Bridges the existing [DocumentationProvider] into the pluggable documentation system.
 *
 * This allows the original GroovyDoc extraction to work alongside Jenkins-specific
 * documentation providers through a unified interface.
 */
class GroovyDocSource(private val documentProvider: DocumentProvider) : PluggableDocProvider {

    override val name: String = "Groovy Documentation"
    override val priority: Int = 0 // Default priority, Jenkins provider is higher

    private val docProvider by lazy {
        DocumentationProvider.getInstance(documentProvider)
    }

    override fun generateDoc(node: ASTNode, model: GroovyAstModel, documentUri: URI): GroovyDocumentation? {
        val doc = docProvider.getDocumentation(node, documentUri)
        return doc.toGroovyDocumentation()
    }

    override fun getQuickNavigateInfo(node: ASTNode): String? {
        // Quick info not supported by GroovyDoc extraction
        return null
    }
}
