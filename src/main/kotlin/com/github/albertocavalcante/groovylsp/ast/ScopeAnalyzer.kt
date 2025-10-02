package com.github.albertocavalcante.groovylsp.ast

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.slf4j.LoggerFactory

/**
 * Analyzes variable and method scopes in Groovy AST.
 * Provides scope-aware symbol resolution for LSP features.
 */
class ScopeAnalyzer {

    companion object {
        private val logger = LoggerFactory.getLogger(ScopeAnalyzer::class.java)
    }

    /**
     * Analyzes the scope structure of a module.
     */
    fun analyzeModuleScope(module: ModuleNode): ScopeTree {
        val rootScope = Scope(
            type = ScopeType.MODULE,
            node = module,
            startLine = 0,
            endLine = Int.MAX_VALUE,
        )

        // Process top-level classes
        module.classes.forEach { classNode ->
            val classScope = analyzeClassScope(classNode, rootScope)
            rootScope.addChild(classScope)
        }

        return ScopeTree(rootScope)
    }

    /**
     * Analyzes the scope of a class.
     */
    private fun analyzeClassScope(classNode: ClassNode, parent: Scope): Scope {
        val classScope = Scope(
            type = ScopeType.CLASS,
            node = classNode,
            parent = parent,
            startLine = classNode.lineNumber - 1,
            endLine = classNode.lastLineNumber - 1,
        )

        // Add class fields to scope
        classNode.fields.forEach { field ->
            classScope.addSymbol(
                ScopeSymbol(
                    name = field.name,
                    type = SymbolType.FIELD,
                    node = field,
                    declarationLine = field.lineNumber - 1,
                ),
            )
        }

        // Process methods
        classNode.methods.forEach { method ->
            val methodScope = analyzeMethodScope(method, classScope)
            classScope.addChild(methodScope)
        }

        return classScope
    }

    /**
     * Analyzes the scope of a method.
     */
    private fun analyzeMethodScope(method: MethodNode, parent: Scope): Scope {
        val methodScope = Scope(
            type = ScopeType.METHOD,
            node = method,
            parent = parent,
            startLine = method.lineNumber - 1,
            endLine = method.lastLineNumber - 1,
        )

        // Add method parameters to scope
        method.parameters.forEach { param ->
            methodScope.addSymbol(
                ScopeSymbol(
                    name = param.name,
                    type = SymbolType.PARAMETER,
                    node = param,
                    declarationLine = method.lineNumber - 1,
                ),
            )
        }

        // Analyze method body
        if (method.code != null) {
            analyzeBlockScope(method.code, methodScope)
        }

        return methodScope
    }

    /**
     * Analyzes a block statement scope.
     */
    private fun analyzeBlockScope(statement: org.codehaus.groovy.ast.stmt.Statement?, parent: Scope) {
        if (statement == null) return

        when (statement) {
            is BlockStatement -> {
                statement.statements.forEach { stmt ->
                    analyzeStatement(stmt, parent)
                }
            }
            else -> analyzeStatement(statement, parent)
        }
    }

    /**
     * Analyzes individual statements for scope information.
     */
    private fun analyzeStatement(statement: org.codehaus.groovy.ast.stmt.Statement, scope: Scope) {
        when (statement) {
            is DeclarationExpression -> {
                // Variable declaration
                scope.addSymbol(
                    ScopeSymbol(
                        name = statement.variableExpression.name,
                        type = SymbolType.LOCAL_VARIABLE,
                        node = statement,
                        declarationLine = statement.lineNumber - 1,
                    ),
                )
            }
            is ForStatement -> {
                // For loop creates a new scope
                val forScope = Scope(
                    type = ScopeType.BLOCK,
                    node = statement,
                    parent = scope,
                    startLine = statement.lineNumber - 1,
                    endLine = statement.lastLineNumber - 1,
                )

                // Add loop variable
                if (statement.variable != null) {
                    forScope.addSymbol(
                        ScopeSymbol(
                            name = statement.variable.name,
                            type = SymbolType.LOCAL_VARIABLE,
                            node = statement.variable,
                            declarationLine = statement.lineNumber - 1,
                        ),
                    )
                }

                scope.addChild(forScope)
                analyzeBlockScope(statement.loopBlock, forScope)
            }
            is BlockStatement -> {
                // Block creates a new scope
                val blockScope = Scope(
                    type = ScopeType.BLOCK,
                    node = statement,
                    parent = scope,
                    startLine = statement.lineNumber - 1,
                    endLine = statement.lastLineNumber - 1,
                )
                scope.addChild(blockScope)
                analyzeBlockScope(statement, blockScope)
            }
        }
    }

    /**
     * Finds the most specific scope containing the given position.
     */
    fun findScopeAtPosition(scopeTree: ScopeTree, line: Int, column: Int): Scope? =
        findScopeAtPositionRecursive(scopeTree.root, line, column)

    private fun findScopeAtPositionRecursive(scope: Scope, line: Int, column: Int): Scope? {
        if (!scope.containsPosition(line, column)) {
            return null
        }

        // Check children first (more specific scopes)
        for (child in scope.children) {
            val result = findScopeAtPositionRecursive(child, line, column)
            if (result != null) {
                return result
            }
        }

        // Return this scope if no child contains the position
        return scope
    }

    /**
     * Resolves a symbol name in the given scope context.
     */
    fun resolveSymbol(name: String, scope: Scope): SymbolResolution? {
        var currentScope: Scope? = scope

        while (currentScope != null) {
            // Check local symbols first
            val symbol = currentScope.symbols[name]
            if (symbol != null) {
                return SymbolResolution(
                    symbol = symbol,
                    scope = currentScope,
                    resolutionType = ResolutionType.LOCAL,
                )
            }

            // Check class members if we're in a class scope
            if (currentScope.type == ScopeType.CLASS && currentScope.node is ClassNode) {
                val classNode = currentScope.node as ClassNode

                // Check fields
                val field = classNode.getField(name)
                if (field != null) {
                    return SymbolResolution(
                        symbol = ScopeSymbol(
                            name = field.name,
                            type = SymbolType.FIELD,
                            node = field,
                            declarationLine = field.lineNumber - 1,
                        ),
                        scope = currentScope,
                        resolutionType = ResolutionType.MEMBER,
                    )
                }

                // Check methods
                val methods = classNode.getMethods(name)
                if (methods.isNotEmpty()) {
                    return SymbolResolution(
                        symbol = ScopeSymbol(
                            name = methods.first().name,
                            type = SymbolType.METHOD,
                            node = methods.first(),
                            declarationLine = methods.first().lineNumber - 1,
                        ),
                        scope = currentScope,
                        resolutionType = ResolutionType.MEMBER,
                    )
                }
            }

            currentScope = currentScope.parent
        }

        return null
    }
}

/**
 * Represents a scope tree for a compilation unit.
 */
data class ScopeTree(val root: Scope)

/**
 * Represents a scope in the code.
 */
data class Scope(
    val type: ScopeType,
    val node: ASTNode,
    val parent: Scope? = null,
    val startLine: Int = 0,
    val endLine: Int = Int.MAX_VALUE,
    val children: MutableList<Scope> = mutableListOf(),
    val symbols: MutableMap<String, ScopeSymbol> = mutableMapOf(),
) {

    fun addChild(child: Scope) {
        children.add(child)
    }

    fun addSymbol(symbol: ScopeSymbol) {
        symbols[symbol.name] = symbol
    }

    fun containsPosition(line: Int, column: Int): Boolean = line >= startLine && line <= endLine
}

/**
 * Types of scopes in Groovy code.
 */
enum class ScopeType {
    MODULE,
    CLASS,
    METHOD,
    BLOCK,
    CLOSURE,
}

/**
 * Represents a symbol within a scope.
 */
data class ScopeSymbol(val name: String, val type: SymbolType, val node: ASTNode, val declarationLine: Int)

/**
 * Types of symbols.
 */
enum class SymbolType {
    LOCAL_VARIABLE,
    PARAMETER,
    FIELD,
    METHOD,
    CLASS,
    IMPORT,
}

/**
 * Result of symbol resolution.
 */
data class SymbolResolution(val symbol: ScopeSymbol, val scope: Scope, val resolutionType: ResolutionType)

/**
 * How the symbol was resolved.
 */
enum class ResolutionType {
    LOCAL, // Found in local scope
    MEMBER, // Found as class member
    INHERITED, // Found in superclass
    IMPORTED, // Found via import
}
