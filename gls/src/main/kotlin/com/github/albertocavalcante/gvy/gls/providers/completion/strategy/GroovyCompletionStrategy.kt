package com.github.albertocavalcante.gvy.gls.providers.completion.strategy

import com.github.albertocavalcante.gvy.gls.dsl.completion.CompletionsBuilder
import com.github.albertocavalcante.gvy.gls.dsl.completion.GroovyCompletions
import com.github.albertocavalcante.gvy.gls.dsl.completion.completions
import com.github.albertocavalcante.gvy.gls.providers.completion.CompletionProvider.ContextType
import com.github.albertocavalcante.gvy.semantics.native.ClassSymbol
import com.github.albertocavalcante.gvy.semantics.native.FieldSymbol
import com.github.albertocavalcante.gvy.semantics.native.ImportSymbol
import com.github.albertocavalcante.gvy.semantics.native.MethodSymbol
import com.github.albertocavalcante.gvy.semantics.native.VariableSymbol
import org.eclipse.lsp4j.CompletionItem

/**
 * Completion strategy for core Groovy language features.
 *
 * Provides completions for:
 * - Local symbols (classes, methods, fields, variables from symbol context)
 * - Groovy keywords (def, class, if, for, etc.)
 * - Basic Groovy snippets (println, print)
 *
 * This strategy is always active regardless of mode, providing
 * baseline Groovy language support.
 */
internal class GroovyCompletionStrategy : CompletionStrategy {

    override suspend fun complete(context: CompletionStrategyContext): CompletionResult {
        // Skip Groovy completions in strict declarative Jenkins mode
        // (only declarative directives allowed - this also skips keywords and snippets)
        val skipGroovyCompletions = context.jenkinsBlockContext?.isStrictDeclarative == true

        // Skip keywords when in member access context (e.g., "config.█")
        val isMemberAccess = context.contextType is ContextType.MemberAccess

        val items = buildGroovyCompletions(context, skipGroovyCompletions, isMemberAccess)
        return CompletionStrategy.found(items)
    }

    private fun buildGroovyCompletions(
        context: CompletionStrategyContext,
        skipGroovyCompletions: Boolean,
        isMemberAccess: Boolean,
    ): List<CompletionItem> = completions {
        if (!skipGroovyCompletions) {
            addClasses(context.symbolContext.classes)
            addMethods(context.symbolContext.methods)
            addFields(context.symbolContext.fields)
            addVariables(context.symbolContext.variables)
            addImports(context.symbolContext.imports)
            // Skip keywords in member access context (e.g., "config.abstract" makes no sense)
            if (!isMemberAccess) {
                addKeywords()
            }
            // Only add basic snippets when not in strict declarative mode
            GroovyCompletions.basic().forEach(::add)
        }
    }

    private fun CompletionsBuilder.addClasses(classes: List<ClassSymbol>) {
        classes.forEach { classSymbol ->
            clazz(
                name = classSymbol.name,
                packageName = classSymbol.packageName,
                doc = "Class: ${classSymbol.name}",
            )
        }
    }

    private fun CompletionsBuilder.addMethods(methods: List<MethodSymbol>) {
        methods.forEach { methodSymbol ->
            val paramSignatures = methodSymbol.parameters.map { "${it.type} ${it.name}" }
            method(
                name = methodSymbol.name,
                returnType = methodSymbol.returnType,
                parameters = paramSignatures,
                doc = "Method: ${methodSymbol.name}",
            )
        }
    }

    private fun CompletionsBuilder.addFields(fields: List<FieldSymbol>) {
        fields.forEach { fieldSymbol ->
            field(
                name = fieldSymbol.name,
                type = fieldSymbol.type,
                doc = "Field: ${fieldSymbol.type} ${fieldSymbol.name}",
            )
        }
    }

    private fun CompletionsBuilder.addVariables(variables: List<VariableSymbol>) {
        variables.forEach { varSymbol ->
            val kind = varSymbol.kind.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
            val docString = "$kind: ${varSymbol.type} ${varSymbol.name}"
            variable(
                name = varSymbol.name,
                type = varSymbol.type,
                doc = docString,
            )
        }
    }

    private fun CompletionsBuilder.addImports(imports: List<ImportSymbol>) {
        imports.forEach { importSymbol ->
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
    }

    /**
     * Keywords that have snippet versions in GroovyCompletions.basic().
     * These are excluded from addKeywords() to prevent duplicate completions (#857).
     */
    private val snippetKeywords = setOf("def", "class", "interface", "enum", "if", "for", "while")

    private fun CompletionsBuilder.addKeywords() {
        val allKeywords = listOf(
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
        // Filter out keywords that have snippet versions in GroovyCompletions.basic()
        allKeywords.filterNot { it in snippetKeywords }.forEach { k ->
            keyword(
                keyword = k,
                doc = "Keyword/Type: $k",
            )
        }
    }
}
