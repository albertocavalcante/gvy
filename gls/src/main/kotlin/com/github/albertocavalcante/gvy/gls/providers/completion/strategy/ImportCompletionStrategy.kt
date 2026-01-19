package com.github.albertocavalcante.gvy.gls.providers.completion.strategy

import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.dsl.completion.CompletionsBuilder
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.lang.reflect.Modifier

/**
 * Strategy for handling import statement completions.
 *
 * Provides completions for:
 * - `static` keyword when typing import statements
 * - Class names from classpath (qualified and simple names)
 * - Static members (methods, fields, properties) for static imports
 * - Static members from workspace classes
 *
 * This is a specialized strategy that is typically invoked early in the completion pipeline
 * when an import statement is detected.
 */
internal class ImportCompletionStrategy(private val compilationService: GroovyCompilationService) {
    /**
     * Adds import-specific completions (static keyword and matching class names).
     */
    fun addImportCompletions(ctx: ImportContext, builder: CompletionsBuilder) {
        if (ctx.canSuggestStatic) {
            builder.keyword(
                keyword = "static",
                doc = "Static import",
            )
        }

        val prefix = ctx.prefix.trim()
        if (prefix.isBlank()) {
            // TODO(#575): Provide curated suggestions for empty import prefixes.
            //   See: https://github.com/albertocavalcante/gvy/issues/575
            return
        }

        val classpathService = compilationService.classpathService

        // Handle static member completion (e.g., "import static java.lang.Math.PI")
        if (tryAddStaticMemberCompletions(ctx, builder)) {
            return
        }
        // If className is not found, fall through to normal class completion
        // (it's likely a package path, e.g., "import static org.junit.")

        val candidates = if (prefix.contains('.')) {
            classpathService.findClassesByQualifiedPrefix(prefix, maxResults = MAX_IMPORT_COMPLETION_RESULTS)
        } else {
            classpathService.findClassesByPrefix(prefix, maxResults = MAX_IMPORT_COMPLETION_RESULTS)
        }

        val range = Range(
            Position(ctx.line, ctx.replaceRange.start.character),
            Position(ctx.line, ctx.replaceRange.end.character),
        )
        candidates
            .map { it.fullName }
            .distinct()
            .forEach { fullName ->
                builder.add(
                    CompletionItem().apply {
                        label = fullName
                        kind = CompletionItemKind.Class
                        detail = fullName
                        insertText = fullName
                        textEdit = Either.forLeft(TextEdit(range, fullName))
                    },
                )
            }
    }

    /**
     * Try to add static member completions for import statements.
     *
     * This helper extracts the logic for handling static imports like "import static java.lang.Math.PI".
     *
     * @return true if static member completions were added (indicating no need for further processing)
     */
    private fun tryAddStaticMemberCompletions(ctx: ImportContext, builder: CompletionsBuilder): Boolean {
        // Only if we can actually find the class on the classpath
        val staticClassName = when {
            ctx.isStaticMemberCompletion -> ctx.staticClassName
            ctx.isStatic && ctx.prefix.contains('.') -> ctx.prefix.substringBeforeLast('.')
            else -> null
        }
        val staticMemberPrefix = when {
            ctx.isStaticMemberCompletion -> ""
            ctx.isStatic && ctx.prefix.contains('.') -> ctx.prefix.substringAfterLast('.')
            else -> null
        }

        if (staticClassName == null) {
            return false
        }

        val workspaceFound = addWorkspaceStaticMemberCompletions(
            className = staticClassName,
            ctx = ctx,
            builder = builder,
            memberPrefix = staticMemberPrefix,
        )

        val classpathService = compilationService.classpathService
        val classpathFound = classpathService.loadClass(staticClassName) != null
        if (classpathFound) {
            addStaticMethodCompletions(staticClassName, ctx, staticMemberPrefix, builder)
            addStaticFieldCompletions(staticClassName, ctx, staticMemberPrefix, builder)
        }

        return workspaceFound || classpathFound
    }

    /**
     * Adds static member completions for workspace-defined classes.
     *
     * @return true if any members were added.
     */
    private fun addWorkspaceStaticMemberCompletions(
        className: String,
        ctx: ImportContext,
        builder: CompletionsBuilder,
        memberPrefix: String?,
    ): Boolean {
        val classSymbols = findWorkspaceClassSymbols(className)
            .distinctBy { it.fullyQualifiedName.ifBlank { it.name } }
        if (classSymbols.isEmpty()) return false

        val range = Range(
            Position(ctx.line, ctx.replaceRange.start.character),
            Position(ctx.line, ctx.replaceRange.end.character),
        )

        var added = false
        val staticFieldNames = mutableSetOf<String>()
        classSymbols.forEach { classSymbol ->
            val qualifier = classSymbol.fullyQualifiedName.ifBlank { className }

            if (addStaticMethodCompletions(classSymbol, qualifier, memberPrefix, range, builder)) {
                added = true
            }

            if (addStaticFieldCompletions(classSymbol, qualifier, memberPrefix, range, staticFieldNames, builder)) {
                added = true
            }

            if (addStaticPropertyCompletions(classSymbol, qualifier, memberPrefix, range, staticFieldNames, builder)) {
                added = true
            }
        }
        return added
    }

    /**
     * Add static method completions for a workspace class symbol.
     *
     * @return true if any completions were added
     */
    private fun addStaticMethodCompletions(
        classSymbol: Symbol.Class,
        qualifier: String,
        memberPrefix: String?,
        range: Range,
        builder: CompletionsBuilder,
    ): Boolean {
        var added = false
        classSymbol.methods
            .filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) }
            .filter { memberPrefix.isNullOrEmpty() || it.name.startsWith(memberPrefix) }
            .forEach { method ->
                val returnType = method.returnType?.nameWithoutPackage ?: "def"
                val params = method.parameters.joinToString(", ") { it.type.nameWithoutPackage }
                builder.add(
                    CompletionItem().apply {
                        label = method.name
                        kind = CompletionItemKind.Method
                        detail = "$returnType ${method.name}($params)"
                        insertText = method.name
                        textEdit = Either.forLeft(TextEdit(range, "$qualifier.${method.name}"))
                    },
                )
                added = true
            }
        return added
    }

    /**
     * Add static field completions for a workspace class symbol.
     *
     * @return true if any completions were added
     */
    private fun addStaticFieldCompletions(
        classSymbol: Symbol.Class,
        qualifier: String,
        memberPrefix: String?,
        range: Range,
        staticFieldNames: MutableSet<String>,
        builder: CompletionsBuilder,
    ): Boolean {
        var added = false
        classSymbol.fields
            .filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) }
            .filter { memberPrefix.isNullOrEmpty() || it.name.startsWith(memberPrefix) }
            .forEach { field ->
                val type = field.type?.nameWithoutPackage ?: "def"
                builder.add(
                    CompletionItem().apply {
                        label = field.name
                        kind = if (Modifier.isFinal(field.modifiers)) {
                            CompletionItemKind.Constant
                        } else {
                            CompletionItemKind.Field
                        }
                        detail = "$type ${field.name}"
                        insertText = field.name
                        textEdit = Either.forLeft(TextEdit(range, "$qualifier.${field.name}"))
                    },
                )
                staticFieldNames.add(field.name)
                added = true
            }
        return added
    }

    /**
     * Add static property completions for a workspace class symbol.
     *
     * @return true if any completions were added
     */
    private fun addStaticPropertyCompletions(
        classSymbol: Symbol.Class,
        qualifier: String,
        memberPrefix: String?,
        range: Range,
        staticFieldNames: Set<String>,
        builder: CompletionsBuilder,
    ): Boolean {
        var added = false
        classSymbol.properties
            .filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) }
            .filter { property -> property.name !in staticFieldNames }
            .filter { property -> memberPrefix.isNullOrEmpty() || property.name.startsWith(memberPrefix) }
            .forEach { property ->
                val type = property.type?.nameWithoutPackage ?: "def"
                builder.add(
                    CompletionItem().apply {
                        label = property.name
                        kind = CompletionItemKind.Property
                        detail = "$type ${property.name}"
                        insertText = property.name
                        textEdit = Either.forLeft(TextEdit(range, "$qualifier.${property.name}"))
                    },
                )
                added = true
            }
        return added
    }

    /**
     * Adds completions for static methods when completing static imports (classpath version).
     */
    private fun addStaticMethodCompletions(
        className: String,
        ctx: ImportContext,
        memberPrefix: String?,
        builder: CompletionsBuilder,
    ) {
        val methods = compilationService.classpathService.getMethods(className)
            .filter { it.isStatic && it.isPublic }
            .filter { memberPrefix.isNullOrEmpty() || it.name.startsWith(memberPrefix) }

        val range = Range(
            Position(ctx.line, ctx.replaceRange.start.character),
            Position(ctx.line, ctx.replaceRange.end.character),
        )

        methods.forEach { method ->
            builder.add(
                CompletionItem().apply {
                    label = method.name
                    kind = CompletionItemKind.Method
                    detail = "${method.returnType} ${method.name}(${method.parameters.joinToString(", ")})"
                    insertText = method.name
                    textEdit = Either.forLeft(TextEdit(range, "$className.${method.name}"))
                },
            )
        }
    }

    /**
     * Adds completions for static fields and constants when completing static imports.
     */
    private fun addStaticFieldCompletions(
        className: String,
        ctx: ImportContext,
        memberPrefix: String?,
        builder: CompletionsBuilder,
    ) {
        val fields = compilationService.classpathService.getFields(className)
            .filter { it.isStatic && it.isPublic }
            .filter { memberPrefix.isNullOrEmpty() || it.name.startsWith(memberPrefix) }

        val range = Range(
            Position(ctx.line, ctx.replaceRange.start.character),
            Position(ctx.line, ctx.replaceRange.end.character),
        )

        fields.forEach { field ->
            builder.add(
                CompletionItem().apply {
                    label = field.name
                    kind = if (field.isFinal) CompletionItemKind.Constant else CompletionItemKind.Field
                    detail = "${field.type} ${field.name}"
                    textEdit = Either.forLeft(TextEdit(range, "$className.${field.name}"))
                },
            )
        }
    }

    /**
     * Finds workspace class symbols by fully qualified name or simple name.
     */
    private fun findWorkspaceClassSymbols(className: String): List<Symbol.Class> {
        val matches = mutableListOf<Symbol.Class>()
        val isFqn = className.contains('.')
        compilationService.getAllSymbolStorages().forEach { (uri, index) ->
            index.getSymbols(uri)
                .filterIsInstance<Symbol.Class>()
                .filter { symbol ->
                    if (isFqn) symbol.fullyQualifiedName == className else symbol.name == className
                }
                .forEach { matches.add(it) }
        }
        return matches
    }

    /**
     * Context information for import statement completions.
     *
     * @property prefix The text after "import" or "import static" (e.g., "java.util.L" or "java.lang.Math.")
     * @property isStatic Whether this is a static import
     * @property canSuggestStatic Whether we can suggest the "static" keyword
     * @property line The line number of the import statement
     * @property replaceRange The range to replace when inserting completion
     */
    data class ImportContext(
        val prefix: String,
        val isStatic: Boolean,
        val canSuggestStatic: Boolean,
        val line: Int,
        val replaceRange: Range,
    ) {
        // Static member completion is when we have a fully qualified class name followed by a dot
        // e.g., "import static java.lang.Math." (cursor after dot)
        // NOT "import static java.lang.Math" (cursor at end of class name)
        val isStaticMemberCompletion: Boolean
            get() = isStatic && prefix.endsWith('.') && prefix.substringBeforeLast('.').contains('.')

        val staticClassName: String?
            get() = if (isStaticMemberCompletion) prefix.substringBeforeLast('.') else null
    }

    companion object {
        private const val MAX_IMPORT_COMPLETION_RESULTS = 50
    }
}
