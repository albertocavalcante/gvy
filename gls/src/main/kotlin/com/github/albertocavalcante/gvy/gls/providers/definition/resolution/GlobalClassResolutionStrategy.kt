// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.gvy.gls.compilation.ClassNameQuery
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode

/**
 * Resolves class references across workspace files using symbol index.
 *
 * This strategy handles cross-file navigation within the project:
 * - Class references from other Groovy files
 * - Import statements pointing to project classes
 *
 * **Priority: MEDIUM** - runs after local symbol resolution.
 */
class GlobalClassResolutionStrategy(private val compilationService: GroovyCompilationService) :
    SymbolResolutionStrategy {

    private val logger = KotlinLogging.logger {}

    override suspend fun resolve(context: ResolutionContext): ResolutionResult {
        val className = getClassName(context.targetNode)
            ?: return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)

        logger.debug { "Attempting global lookup for class: $className" }

        // Extract simple name from potentially fully-qualified name for matching
        val simpleClassName = className.substringAfterLast('.')

        // Workspace classes with this simple name, in file order (#1312). A class whose fully qualified name
        // is exactly the reference wins (#1336): `b.Shared` must not open `a.Shared`. Without such a class, the
        // simple name decides and each file offers its first match, as before.
        val matches = compilationService.symbolIndexingService.findClasses(ClassNameQuery.Exact(simpleClassName))
        val exact = if (className.contains('.') || referencedFromDefaultPackage(context.documentUri)) {
            matches.filter { it.fullyQualifiedName == className }.ifEmpty { nestedExactMatches(className) }
        } else {
            emptyList()
        }
        // Exact matches first; if none loads, the simple-name candidates follow as before.
        val candidates = if (context.requireExactClassName) {
            exact
        } else {
            (
                exact + matches.distinctBy {
                    it.uri
                }
                ).distinct()
        }
        for (matchingSymbol in candidates) {
            val uri = matchingSymbol.uri
            // Found it in the index, now load the actual ClassNode from AST
            val classNode = loadClassNodeFromAst(uri, matchingSymbol.fullyQualifiedName)
            if (classNode != null) {
                logger.debug { "Found global class definition for $className at $uri" }
                return SymbolResolutionStrategy.found(
                    DefinitionResolver.DefinitionResult.Source(classNode, uri),
                )
            } else {
                logger.warn { "Symbol found in index but ClassNode not found in AST for $className at $uri" }
            }
        }

        return SymbolResolutionStrategy.notFound(
            "Class $className not found in workspace symbol index",
            STRATEGY_NAME,
        )
    }

    /**
     * A nested class written in source form (`b.Outer.Inner`) is indexed under its binary name
     * (`b.Outer$Inner`): turn trailing dots into `$`, innermost first, until a class matches exactly (#1375).
     */
    private fun nestedExactMatches(className: String): List<Symbol.Class> {
        var candidate = className
        while (candidate.contains('.')) {
            candidate = candidate.substringBeforeLast('.') + '$' + candidate.substringAfterLast('.')
            val name = candidate
            val found = compilationService.symbolIndexingService
                .findClasses(ClassNameQuery.Exact(name.substringAfterLast('.')))
                .filter { it.fullyQualifiedName == name }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /**
     * A bare name is the full name of a default-package class, but only default-package code can use such a
     * class on the JVM. Packaged (or unknown) referencing files keep the URI-order simple-name result.
     */
    private fun referencedFromDefaultPackage(documentUri: java.net.URI): Boolean {
        val module = compilationService.getAst(documentUri) as? ModuleNode ?: return false
        return module.packageName.isNullOrBlank()
    }

    @Suppress("ReturnCount") // Multiple validation checks require early returns
    private suspend fun loadClassNodeFromAst(uri: java.net.URI, className: String): ClassNode? {
        // Ensure the file is compiled (handles case where file is in index but not yet compiled)
        val compilationResult = compilationService.ensureCompiled(uri)
        if (compilationResult == null) {
            logger.warn {
                "Cannot load AST for $uri - file not compiled and no active compilation found. " +
                    "This may indicate the file was deleted or is not accessible."
            }
            return null
        }

        val ast = compilationService.getAst(uri)
        if (ast == null) {
            logger.warn {
                "AST is null for $uri after ensureCompiled returned successfully. " +
                    "This indicates a cache inconsistency."
            }
            return null
        }

        val moduleNode = ast as? ModuleNode
        if (moduleNode == null) {
            logger.warn {
                "AST for $uri is not a ModuleNode (actual type: ${ast.javaClass.simpleName}). " +
                    "This parser may not be supported for cross-file resolution."
            }
            return null
        }

        val classNode = moduleNode.classes.find { it.name == className }
        if (classNode == null) {
            logger.debug {
                "ClassNode $className not found in ModuleNode.classes for $uri. " +
                    "Available classes: [${moduleNode.classes.joinToString { it.name }}]"
            }
        }

        return classNode
    }

    companion object {
        private const val STRATEGY_NAME = "GlobalClass"
    }
}
