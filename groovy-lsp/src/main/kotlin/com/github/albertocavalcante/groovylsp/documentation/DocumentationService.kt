package com.github.albertocavalcante.groovylsp.documentation

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import org.codehaus.groovy.ast.ASTNode
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Service that orchestrates multiple [PluggableDocProvider]s.
 *
 * Providers are checked in priority order (highest first).
 * The first provider that can handle the node and returns non-null
 * documentation wins.
 */
class DocumentationService(providers: List<PluggableDocProvider> = emptyList()) {

    private val logger = LoggerFactory.getLogger(DocumentationService::class.java)

    // Sorted by priority (highest first)
    private val providers: List<PluggableDocProvider> = providers.sortedByDescending { it.priority }

    /**
     * Get documentation for an AST node.
     *
     * @param node The AST node to document
     * @param model The AST model for context
     * @param documentUri The document URI
     * @return Documentation from the highest-priority provider that handles this node
     */
    fun getDocumentation(node: ASTNode, model: GroovyAstModel, documentUri: URI): GroovyDocumentation? {
        for (provider in providers) {
            if (!provider.canHandle(node, documentUri)) {
                continue
            }

            try {
                val doc = provider.generateDoc(node, model, documentUri)
                if (doc != null) {
                    logger.debug("Documentation provided by: {}", provider.name)
                    return doc
                }
            } catch (e: Exception) {
                logger.warn("Provider {} failed for node {}", provider.name, node::class.simpleName, e)
            }
        }

        return null
    }

    /**
     * Get quick navigate info from the first provider that handles this node.
     */
    fun getQuickNavigateInfo(node: ASTNode, documentUri: URI): String? {
        for (provider in providers) {
            if (!provider.canHandle(node, documentUri)) {
                continue
            }

            try {
                val info = provider.getQuickNavigateInfo(node)
                if (info != null) {
                    return info
                }
            } catch (e: Exception) {
                logger.warn("Provider {} failed for quick info", provider.name, e)
            }
        }

        return null
    }

    /**
     * Builder for easy construction with providers.
     */
    class Builder {
        private val providers = mutableListOf<PluggableDocProvider>()

        fun addProvider(provider: PluggableDocProvider): Builder {
            providers.add(provider)
            return this
        }

        fun build(): DocumentationService = DocumentationService(providers)
    }

    companion object {
        fun builder() = Builder()
    }
}
