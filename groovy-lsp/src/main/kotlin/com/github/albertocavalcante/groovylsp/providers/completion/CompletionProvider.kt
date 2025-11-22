package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.dsl.completion.GroovyCompletions
import com.github.albertocavalcante.groovylsp.dsl.completion.completions
import com.github.albertocavalcante.groovyparser.ast.SymbolExtractor
import org.codehaus.groovy.control.CompilationFailedException
import org.eclipse.lsp4j.CompletionItem
import org.slf4j.LoggerFactory

/**
 * Provides completion items for Groovy language constructs using clean DSL.
 */
object CompletionProvider {
    private val logger = LoggerFactory.getLogger(CompletionProvider::class.java)

    /**
     * Get basic Groovy language completion items using DSL.
     */
    fun getBasicCompletions(): List<CompletionItem> = GroovyCompletions.basic()

    /**
     * Get contextual completions based on AST analysis.
     */
    fun getContextualCompletions(
        uri: String,
        line: Int,
        character: Int,
        compilationService: GroovyCompilationService,
    ): List<CompletionItem> = try {
        // Get the AST for the current file
        val ast = compilationService.getAst(java.net.URI.create(uri))
        if (ast == null) {
            emptyList()
        } else {
            buildCompletionsList(ast, line, character)
        }
    } catch (e: CompilationFailedException) {
        // If AST analysis fails, log and return empty list
        logger.debug("AST analysis failed for completion at {}:{}: {}", line, character, e.message)
        emptyList()
    }

    private fun buildCompletionsList(
        ast: org.codehaus.groovy.ast.ASTNode,
        line: Int,
        character: Int,
    ): List<CompletionItem> {
        // Extract completion context
        val context = SymbolExtractor.extractCompletionSymbols(ast, line, character)

        return completions {
            // Add class completions
            context.classes.forEach { classSymbol ->
                clazz(
                    name = classSymbol.name,
                    packageName = classSymbol.packageName,
                    doc = "Class: ${classSymbol.name}",
                )
            }

            // Add method completions (if we're inside a class)
            context.methods.forEach { methodSymbol ->
                val paramSignatures = methodSymbol.parameters.map { "${it.type} ${it.name}" }
                method(
                    name = methodSymbol.name,
                    returnType = methodSymbol.returnType,
                    parameters = paramSignatures,
                    doc = "Method: ${methodSymbol.name}",
                )
            }

            // Add field completions (if we're inside a class)
            context.fields.forEach { fieldSymbol ->
                field(
                    name = fieldSymbol.name,
                    type = fieldSymbol.type,
                    doc = "Field: ${fieldSymbol.type} ${fieldSymbol.name}",
                )
            }

            // Add variable completions (parameters and local variables)
            context.variables.forEach { varSymbol ->
                val kind = varSymbol.kind.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
                val docString = "$kind: ${varSymbol.type} ${varSymbol.name}"
                variable(
                    name = varSymbol.name,
                    type = varSymbol.type,
                    doc = docString,
                )
            }

            // Add imported classes
            context.imports.forEach { importSymbol ->
                if (!importSymbol.isStarImport) {
                    val name = importSymbol.className
                        ?: importSymbol.packageName.substringAfterLast('.')

                    clazz(
                        name = name,
                        packageName = importSymbol.packageName,
                        doc = "Imported: ${importSymbol.packageName}.$name",
                    )
                }
            }

            // Add common Groovy keywords/types
            // List includes types, control flow, modifiers, and special values
            val keywords = listOf(
                // Types
                "def", "void", "int", "boolean", "char", "byte",
                "short", "long", "float", "double", "String", "Object",
                // Control flow
                "if", "else", "for", "while", "do", "switch", "case", "default",
                "break", "continue", "return", "try", "catch", "finally", "throw",
                // Structure
                "class", "interface", "trait", "enum", "package", "import",
                // Modifiers
                "public", "protected", "private", "static", "final", "abstract",
                "synchronized", "transient", "volatile", "native",
                // Values/Other
                "true", "false", "null", "this", "super", "new", "in", "as", "assert",
            )
            keywords.forEach { k ->
                keyword(
                    keyword = k,
                    doc = "Keyword/Type: $k",
                )
            }
        }
    }
}
