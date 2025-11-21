package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import java.lang.reflect.Modifier

/**
 * Extracts symbols from Groovy AST for IDE features like completion and go-to-definition.
 * This is the core component that enables real language server functionality.
 */
object SymbolExtractor {

    /**
     * Extract all class symbols from a compilation unit.
     */
    fun extractClassSymbols(ast: Any): List<ClassSymbol> {
        if (ast !is ModuleNode) return emptyList()

        return ast.classes.map { classNode ->
            ClassSymbol(
                name = classNode.nameWithoutPackage,
                packageName = classNode.packageName,
                astNode = classNode,
                line = classNode.lineNumber - 1, // Convert to 0-based
                column = classNode.columnNumber - 1,
            )
        }
    }

    /**
     * Extract method symbols from a class node.
     */
    fun extractMethodSymbols(classNode: Any): List<MethodSymbol> {
        if (classNode !is ClassNode) return emptyList()

        return classNode.methods.map { methodNode ->
            val parameters = methodNode.parameters.map { param ->
                ParameterInfo(
                    name = param.name,
                    type = param.type.nameWithoutPackage,
                )
            }

            MethodSymbol(
                name = methodNode.name,
                returnType = methodNode.returnType.nameWithoutPackage,
                parameters = parameters,
                line = methodNode.lineNumber - 1,
                column = methodNode.columnNumber - 1,
            )
        }
    }

    /**
     * Extract field symbols from a class node.
     */
    fun extractFieldSymbols(classNode: Any): List<FieldSymbol> {
        if (classNode !is ClassNode) return emptyList()

        return classNode.fields.map { fieldNode ->
            FieldSymbol(
                name = fieldNode.name,
                type = fieldNode.type.nameWithoutPackage,
                isPrivate = Modifier.isPrivate(fieldNode.modifiers),
                isPublic = Modifier.isPublic(fieldNode.modifiers),
                isProtected = Modifier.isProtected(fieldNode.modifiers),
                isStatic = Modifier.isStatic(fieldNode.modifiers),
                isFinal = Modifier.isFinal(fieldNode.modifiers),
                line = fieldNode.lineNumber - 1,
                column = fieldNode.columnNumber - 1,
            )
        }
    }

    /**
     * Extract import symbols from a compilation unit.
     */
    fun extractImportSymbols(ast: Any): List<ImportSymbol> {
        if (ast !is ModuleNode) return emptyList()

        val imports = mutableListOf<ImportSymbol>()
        imports.addAll(processRegularImports(ast))
        imports.addAll(processStarImports(ast))
        imports.addAll(processStaticImports(ast))
        imports.addAll(processStaticStarImports(ast))

        return imports
    }

    private fun processRegularImports(ast: ModuleNode): List<ImportSymbol> = ast.imports.map { importNode ->
        val className = importNode.className
        val packageName = if (className.contains('.')) {
            className.substringBeforeLast('.')
        } else {
            ""
        }
        val simpleClassName = className.substringAfterLast('.')

        ImportSymbol(
            packageName = packageName,
            className = simpleClassName,
            isStarImport = false,
            isStatic = false,
            line = importNode.lineNumber - 1,
        )
    }

    private fun processStarImports(ast: ModuleNode): List<ImportSymbol> = ast.starImports.map { importNode ->
        ImportSymbol(
            packageName = importNode.packageName.trimEnd('.'),
            className = null,
            isStarImport = true,
            isStatic = false,
            line = importNode.lineNumber - 1,
        )
    }

    private fun processStaticImports(ast: ModuleNode): List<ImportSymbol> = ast.staticImports.map { (_, importNode) ->
        val className = importNode.className
        val packageName = if (className.contains('.')) {
            className.substringBeforeLast('.')
        } else {
            ""
        }

        ImportSymbol(
            packageName = packageName,
            className = className.substringAfterLast('.'),
            isStarImport = false,
            isStatic = true,
            line = importNode.lineNumber - 1,
        )
    }

    private fun processStaticStarImports(ast: ModuleNode): List<ImportSymbol> =
        ast.staticStarImports.map { (className, importNode) ->
            ImportSymbol(
                packageName = className.trimEnd('.'),
                className = null,
                isStarImport = true,
                isStatic = true,
                line = importNode.lineNumber - 1,
            )
        }

    /**
     * Extract all symbols that could be used for code completion at a given position.
     */
    fun extractCompletionSymbols(ast: Any, line: Int, @Suppress("UNUSED_PARAMETER") character: Int): CompletionContext {
        if (ast !is ModuleNode) return CompletionContext.EMPTY

        val classes = extractClassSymbols(ast)
        val imports = extractImportSymbols(ast)

        // Find the class we're currently in (if any)
        val currentClass = classes.find { classSymbol ->
            val classNode = classSymbol.astNode as org.codehaus.groovy.ast.ClassNode
            line >= classSymbol.line && line < classNode.lastLineNumber
        }

        val methods = currentClass?.let { extractMethodSymbols(it.astNode) } ?: emptyList()
        val fields = currentClass?.let { extractFieldSymbols(it.astNode) } ?: emptyList()

        return CompletionContext(
            classes = classes,
            methods = methods,
            fields = fields,
            imports = imports,
            currentClass = currentClass,
        )
    }
}

// Data classes for symbol information
data class ClassSymbol(val name: String, val packageName: String?, val astNode: Any, val line: Int, val column: Int)

data class MethodSymbol(
    val name: String,
    val returnType: String,
    val parameters: List<ParameterInfo>,
    val line: Int,
    val column: Int,
)

data class ParameterInfo(val name: String, val type: String)

data class FieldSymbol(
    val name: String,
    val type: String,
    val isPrivate: Boolean = false,
    val isPublic: Boolean = false,
    val isProtected: Boolean = false,
    val isStatic: Boolean = false,
    val isFinal: Boolean = false,
    val line: Int,
    val column: Int,
)

data class ImportSymbol(
    val packageName: String,
    val className: String?,
    val isStarImport: Boolean = false,
    val isStatic: Boolean = false,
    val line: Int,
)

/**
 * Contains all symbols available for completion at a specific position.
 */
data class CompletionContext(
    val classes: List<ClassSymbol>,
    val methods: List<MethodSymbol>,
    val fields: List<FieldSymbol>,
    val imports: List<ImportSymbol>,
    val currentClass: ClassSymbol?,
) {
    companion object {
        val EMPTY = CompletionContext(emptyList(), emptyList(), emptyList(), emptyList(), null)
    }
}
