package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import java.lang.reflect.Modifier

/**
 * Extracts symbols from Groovy AST for IDE features like completion and go-to-definition.
 * This is the core component that enables real language server functionality.
 */
object SymbolExtractor {

    // ... existing extractClassSymbols ...

    /**
     * Extract variable symbols from a method node (parameters and local variables).
     */
    fun extractVariableSymbols(methodNode: Any): List<VariableSymbol> {
        if (methodNode !is org.codehaus.groovy.ast.MethodNode) return emptyList()

        val variables = mutableListOf<VariableSymbol>()

        // 1. Add parameters
        methodNode.parameters?.forEach { param ->
            variables.add(
                VariableSymbol(
                    name = param.name,
                    type = param.type.nameWithoutPackage,
                    kind = VariableKind.PARAMETER,
                    line = param.lineNumber - 1,
                ),
            )
        }

        // 2. Add local variables (simple scan of the code block)
        val code = methodNode.code
        if (code is BlockStatement) {
            code.statements.forEach { stmt ->
                if (stmt is org.codehaus.groovy.ast.stmt.ExpressionStatement &&
                    stmt.expression is DeclarationExpression
                ) {
                    val decl = stmt.expression as DeclarationExpression
                    val variable = decl.variableExpression

                    // Use TypeInferencer to determine the best type
                    val inferredType = try {
                        TypeInferencer.inferType(decl)
                    } catch (e: Exception) {
                        "java.lang.Object"
                    }

                    variables.add(
                        VariableSymbol(
                            name = variable.name,
                            type = inferredType,
                            kind = VariableKind.LOCAL_VARIABLE,
                            line = stmt.lineNumber - 1,
                        ),
                    )
                }
            }
        }

        return variables
    }

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
     * Extract all symbols relevant for code completion at a given cursor position.
     */
    fun extractCompletionSymbols(ast: ASTNode, line: Int, character: Int): SymbolCompletionContext {
        if (ast !is ModuleNode) return SymbolCompletionContext.EMPTY

        val classes = extractClassSymbols(ast)
        val imports = extractImportSymbols(ast)

        // Find the class we're currently in (if any)
        val currentClass = classes.find { classSymbol ->
            val classNode = classSymbol.astNode as org.codehaus.groovy.ast.ClassNode
            line >= classSymbol.line && line <= classNode.lastLineNumber // Relaxed check
        }

        val methods = currentClass?.let { extractMethodSymbols(it.astNode) } ?: emptyList()
        val fields = currentClass?.let { extractFieldSymbols(it.astNode) } ?: emptyList()

        // Find current method and extract variables
        var variables: List<VariableSymbol> = emptyList()
        if (currentClass != null) {
            val classNode = currentClass.astNode as org.codehaus.groovy.ast.ClassNode
            var methodNode = classNode.methods.find { method ->
                line >= method.lineNumber - 1 && line <= method.lastLineNumber - 1
            }

            // Fallback for scripts: use 'run' method if no specific method matches and 'run' has no line info
            if (methodNode == null) {
                methodNode = classNode.methods.find { it.name == "run" && it.lineNumber == -1 }
            }

            if (methodNode != null) {
                variables = extractVariableSymbols(methodNode)
            }
        }

        return SymbolCompletionContext(
            classes = classes,
            methods = methods,
            fields = fields,
            variables = variables,
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

enum class VariableKind { PARAMETER, LOCAL_VARIABLE }

data class VariableSymbol(val name: String, val type: String, val kind: VariableKind, val line: Int)

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
data class SymbolCompletionContext(
    val classes: List<ClassSymbol>,
    val methods: List<MethodSymbol>,
    val fields: List<FieldSymbol>,
    val imports: List<ImportSymbol>,
    val variables: List<VariableSymbol> = emptyList(),
    val currentClass: ClassSymbol?,
) {
    companion object {
        val EMPTY = SymbolCompletionContext(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)
    }
}
