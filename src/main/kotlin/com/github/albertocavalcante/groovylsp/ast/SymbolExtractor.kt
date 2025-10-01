package com.github.albertocavalcante.groovylsp.ast

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import java.lang.reflect.Modifier

/**
 * Extracts symbols from Groovy AST for IDE features like completion and go-to-definition.
 * This is the core component that enables real language server functionality.
 *
 * TODO: Coordinate Conversion Strategy
 * This file now uses CoordinateSystem for all Groovy->LSP conversions.
 * Future improvement: Make Symbol classes use CoordinateSystem.LspPosition
 * directly instead of separate line/column fields.
 */
object SymbolExtractor {

    /**
     * Extract all class symbols from a compilation unit.
     * Filters out synthetic classes created by Groovy for empty files or scripts.
     */
    fun extractClassSymbols(ast: Any): List<ClassSymbol> {
        if (ast !is ModuleNode) return emptyList()

        return ast.classes.filter { classNode ->
            // Filter out synthetic classes that Groovy creates for empty files or scripts
            // These classes have invalid position information or are compiler-generated
            CoordinateSystem.isValidNodePosition(classNode) && !classNode.isSynthetic
        }.map { classNode ->
            val lspPos = CoordinateSystem.groovyToLsp(classNode.lineNumber, classNode.columnNumber)
            ClassSymbol(
                name = classNode.nameWithoutPackage,
                packageName = classNode.packageName,
                astNode = classNode,
                line = lspPos.line,
                column = lspPos.character,
            )
        }
    }

    /**
     * Extract method symbols from a class node, including constructors.
     */
    fun extractMethodSymbols(classNode: Any): List<MethodSymbol> {
        if (classNode !is ClassNode) return emptyList()

        val methods = mutableListOf<MethodSymbol>()

        // Add regular methods
        methods.addAll(
            classNode.methods.filter { methodNode ->
                // Filter out synthetic methods and ensure valid position
                CoordinateSystem.isValidNodePosition(methodNode) && !methodNode.isSynthetic
            }.map { methodNode ->
                val parameters = methodNode.parameters.map { param ->
                    ParameterInfo(
                        name = param.name,
                        type = param.type.nameWithoutPackage,
                    )
                }

                val lspPos = CoordinateSystem.groovyToLsp(methodNode.lineNumber, methodNode.columnNumber)
                MethodSymbol(
                    name = methodNode.name,
                    returnType = methodNode.returnType.nameWithoutPackage,
                    parameters = parameters,
                    line = lspPos.line,
                    column = lspPos.character,
                )
            },
        )

        // Add constructors
        methods.addAll(
            classNode.declaredConstructors.filter { constructorNode ->
                // Filter out synthetic constructors and ensure valid position
                CoordinateSystem.isValidNodePosition(constructorNode) && !constructorNode.isSynthetic
            }.map { constructorNode ->
                val parameters = constructorNode.parameters.map { param ->
                    ParameterInfo(
                        name = param.name,
                        type = param.type.nameWithoutPackage,
                    )
                }

                val lspPos = CoordinateSystem.groovyToLsp(constructorNode.lineNumber, constructorNode.columnNumber)
                MethodSymbol(
                    name = "<init>", // Use standard constructor name
                    returnType = "void",
                    parameters = parameters,
                    line = lspPos.line,
                    column = lspPos.character,
                )
            },
        )

        return methods
    }

    /**
     * Extract field symbols from a class node.
     */
    fun extractFieldSymbols(classNode: Any): List<FieldSymbol> {
        if (classNode !is ClassNode) return emptyList()

        return classNode.fields.map { fieldNode ->
            val lspPos = CoordinateSystem.groovyToLsp(fieldNode.lineNumber, fieldNode.columnNumber)
            FieldSymbol(
                name = fieldNode.name,
                type = fieldNode.type.nameWithoutPackage,
                isPrivate = Modifier.isPrivate(fieldNode.modifiers),
                isPublic = Modifier.isPublic(fieldNode.modifiers),
                isProtected = Modifier.isProtected(fieldNode.modifiers),
                isStatic = Modifier.isStatic(fieldNode.modifiers),
                isFinal = Modifier.isFinal(fieldNode.modifiers),
                line = lspPos.line,
                column = lspPos.character,
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

        // Handle import aliases: "import java.util.Date as JDate"
        // The alias is stored in importNode.alias, use it if available
        val displayName = importNode.alias ?: className.substringAfterLast('.')

        val lspLine = CoordinateSystem.groovyToLsp(importNode.lineNumber, 1).line
        ImportSymbol(
            packageName = packageName,
            className = displayName,
            isStarImport = false,
            isStatic = false,
            line = lspLine,
        )
    }

    private fun processStarImports(ast: ModuleNode): List<ImportSymbol> = ast.starImports.map { importNode ->
        val lspLine = CoordinateSystem.groovyToLsp(importNode.lineNumber, 1).line
        ImportSymbol(
            packageName = importNode.packageName.trimEnd('.'),
            className = null,
            isStarImport = true,
            isStatic = false,
            line = lspLine,
        )
    }

    private fun processStaticImports(ast: ModuleNode): List<ImportSymbol> = ast.staticImports.map { (_, importNode) ->
        val className = importNode.className
        val packageName = if (className.contains('.')) {
            className.substringBeforeLast('.')
        } else {
            ""
        }

        val lspLine = CoordinateSystem.groovyToLsp(importNode.lineNumber, 1).line
        ImportSymbol(
            packageName = packageName,
            className = className.substringAfterLast('.'),
            isStarImport = false,
            isStatic = true,
            line = lspLine,
        )
    }

    private fun processStaticStarImports(ast: ModuleNode): List<ImportSymbol> =
        ast.staticStarImports.map { (className, importNode) ->
            val lspLine = CoordinateSystem.groovyToLsp(importNode.lineNumber, 1).line
            ImportSymbol(
                packageName = className.trimEnd('.'),
                className = null,
                isStarImport = true,
                isStatic = true,
                line = lspLine,
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
// TODO: Consider using CoordinateSystem.LspPosition instead of separate line/column fields
// This would make the coordinate system explicit in the type system
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
