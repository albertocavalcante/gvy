package com.github.albertocavalcante.groovylsp.ast

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.Position
import org.slf4j.LoggerFactory

/**
 * Performs semantic analysis on Groovy AST to provide deeper code understanding.
 * Enables advanced LSP features like intelligent completions and code navigation.
 */
class SemanticAnalyzer {

    companion object {
        private val logger = LoggerFactory.getLogger(SemanticAnalyzer::class.java)
    }

    private val typeResolver = TypeResolver()
    private val scopeAnalyzer = ScopeAnalyzer()

    /**
     * Performs comprehensive semantic analysis on a module.
     */
    fun analyzeModule(module: ModuleNode): SemanticAnalysisResult {
        logger.debug("Starting semantic analysis for module with ${module.classes.size} classes")

        val scopeTree = scopeAnalyzer.analyzeModuleScope(module)
        val semanticModel = buildSemanticModel(module, scopeTree)

        return SemanticAnalysisResult(
            module = module,
            scopeTree = scopeTree,
            semanticModel = semanticModel,
        )
    }

    /**
     * Builds a semantic model that contains rich information about the code.
     */
    private fun buildSemanticModel(module: ModuleNode, scopeTree: ScopeTree): SemanticModel {
        val symbols = mutableMapOf<String, List<SemanticSymbol>>()
        val typeInformation = mutableMapOf<ASTNodeKey, TypeInfo>()
        val relationships = mutableListOf<SymbolRelationship>()

        // Process each class
        module.classes.forEach { classNode ->
            processClass(classNode, symbols, typeInformation, relationships, scopeTree)
        }

        return SemanticModel(
            symbols = symbols,
            typeInformation = typeInformation,
            relationships = relationships,
        )
    }

    /**
     * Processes a class to extract semantic information.
     */
    private fun processClass(
        classNode: ClassNode,
        symbols: MutableMap<String, List<SemanticSymbol>>,
        typeInformation: MutableMap<ASTNodeKey, TypeInfo>,
        relationships: MutableList<SymbolRelationship>,
        scopeTree: ScopeTree,
    ) {
        val className = classNode.nameWithoutPackage

        // Add class symbol
        addSymbol(
            symbols,
            className,
            SemanticSymbol(
                name = className,
                kind = SemanticSymbolKind.CLASS,
                node = classNode,
                declarationRange = createRange(classNode),
                qualifiedName = classNode.name,
            ),
        )

        // Process inheritance relationships
        if (classNode.superClass != null && classNode.superClass.name != "java.lang.Object") {
            relationships.add(
                SymbolRelationship(
                    from = className,
                    to = classNode.superClass.nameWithoutPackage,
                    type = RelationshipType.EXTENDS,
                ),
            )
        }

        // Process interface implementations
        classNode.interfaces.forEach { interfaceNode ->
            relationships.add(
                SymbolRelationship(
                    from = className,
                    to = interfaceNode.nameWithoutPackage,
                    type = RelationshipType.IMPLEMENTS,
                ),
            )
        }

        // Process fields
        classNode.fields.forEach { field ->
            addSymbol(
                symbols,
                field.name,
                SemanticSymbol(
                    name = field.name,
                    kind = SemanticSymbolKind.FIELD,
                    node = field,
                    declarationRange = createRange(field),
                    containingClass = className,
                    type = field.type.nameWithoutPackage,
                ),
            )
        }

        // Process methods
        classNode.methods.forEach { method ->
            processMethod(method, className, symbols, typeInformation, scopeTree)
        }
    }

    /**
     * Processes a method to extract semantic information.
     */
    private fun processMethod(
        method: MethodNode,
        containingClass: String,
        symbols: MutableMap<String, List<SemanticSymbol>>,
        typeInformation: MutableMap<ASTNodeKey, TypeInfo>,
        scopeTree: ScopeTree,
    ) {
        // Add method symbol
        addSymbol(
            symbols,
            method.name,
            SemanticSymbol(
                name = method.name,
                kind = SemanticSymbolKind.METHOD,
                node = method,
                declarationRange = createRange(method),
                containingClass = containingClass,
                type = method.returnType.nameWithoutPackage,
                parameters = method.parameters.map { param ->
                    ParameterInfo(
                        name = param.name,
                        type = param.type.nameWithoutPackage,
                    )
                },
            ),
        )

        // Process method parameters
        method.parameters.forEach { param ->
            addSymbol(
                symbols,
                param.name,
                SemanticSymbol(
                    name = param.name,
                    kind = SemanticSymbolKind.PARAMETER,
                    node = param,
                    declarationRange = createRange(param),
                    containingMethod = method.name,
                    containingClass = containingClass,
                    type = param.type.nameWithoutPackage,
                ),
            )
        }

        // Analyze method body for type information
        if (method.code != null) {
            val context = TypeResolutionContext(
                enclosingClass = method.declaringClass,
                enclosingMethod = method,
                variableScope = method.variableScope,
            )
            analyzeMethodBody(method, context, typeInformation, scopeTree)
        }
    }

    /**
     * Analyzes method body to infer types and gather semantic information.
     */
    private fun analyzeMethodBody(
        method: MethodNode,
        context: TypeResolutionContext,
        typeInformation: MutableMap<ASTNodeKey, TypeInfo>,
        scopeTree: ScopeTree,
    ) {
        // This would involve traversing the method's AST and resolving types for expressions
        // For now, we'll provide a simplified implementation
        logger.debug("Analyzing method body for: ${method.name}")
    }

    /**
     * Adds a symbol to the symbols map, handling multiple symbols with the same name.
     */
    private fun addSymbol(symbols: MutableMap<String, List<SemanticSymbol>>, name: String, symbol: SemanticSymbol) {
        val existing = symbols[name] ?: emptyList()
        symbols[name] = existing + symbol
    }

    /**
     * Creates a range from an AST node.
     */
    private fun createRange(node: org.codehaus.groovy.ast.ASTNode): SymbolRange = SymbolRange(
        startLine = maxOf(0, node.lineNumber - 1),
        startColumn = maxOf(0, node.columnNumber - 1),
        endLine = maxOf(0, node.lastLineNumber - 1),
        endColumn = maxOf(0, node.lastColumnNumber - 1),
    )

    /**
     * Finds semantic information at a specific position.
     */
    fun findSemanticInfoAtPosition(analysisResult: SemanticAnalysisResult, line: Int, column: Int): SemanticInfo? {
        val scope = scopeAnalyzer.findScopeAtPosition(analysisResult.scopeTree, line, column)
            ?: return null

        // Find symbols at this position
        val symbolsAtPosition = findSymbolsAtPosition(analysisResult.semanticModel, line, column)

        return SemanticInfo(
            position = Position(line, column),
            scope = scope,
            symbols = symbolsAtPosition,
            availableCompletions = generateCompletions(scope, analysisResult.semanticModel),
        )
    }

    /**
     * Finds symbols at a specific position.
     */
    private fun findSymbolsAtPosition(model: SemanticModel, line: Int, column: Int): List<SemanticSymbol> =
        model.symbols.values.flatten().filter { symbol ->
            symbol.declarationRange.contains(line, column)
        }

    /**
     * Generates completion suggestions for a given scope.
     */
    private fun generateCompletions(scope: Scope, model: SemanticModel): List<CompletionSuggestion> {
        val completions = mutableListOf<CompletionSuggestion>()

        // Add symbols from current scope and parent scopes
        var currentScope: Scope? = scope
        while (currentScope != null) {
            currentScope.symbols.values.forEach { symbol ->
                completions.add(
                    CompletionSuggestion(
                        label = symbol.name,
                        kind = when (symbol.type) {
                            SymbolType.LOCAL_VARIABLE -> CompletionKind.VARIABLE
                            SymbolType.PARAMETER -> CompletionKind.VARIABLE
                            SymbolType.FIELD -> CompletionKind.FIELD
                            SymbolType.METHOD -> CompletionKind.METHOD
                            SymbolType.CLASS -> CompletionKind.CLASS
                            SymbolType.IMPORT -> CompletionKind.MODULE
                        },
                        detail = symbol.type.name,
                    ),
                )
            }
            currentScope = currentScope.parent
        }

        // Add class members if we're in a class context
        if (scope.type == ScopeType.CLASS || scope.type == ScopeType.METHOD) {
            val classScope = findClassScope(scope)
            if (classScope?.node is ClassNode) {
                val classNode = classScope.node as ClassNode

                // Add methods
                classNode.methods.forEach { method ->
                    completions.add(
                        CompletionSuggestion(
                            label = method.name,
                            kind = CompletionKind.METHOD,
                            detail = "${method.returnType.nameWithoutPackage} ${method.name}(" +
                                "${method.parameters.joinToString { it.type.nameWithoutPackage }})",
                        ),
                    )
                }

                // Add fields
                classNode.fields.forEach { field ->
                    completions.add(
                        CompletionSuggestion(
                            label = field.name,
                            kind = CompletionKind.FIELD,
                            detail = "${field.type.nameWithoutPackage} ${field.name}",
                        ),
                    )
                }
            }
        }

        return completions.distinctBy { it.label }
    }

    /**
     * Finds the class scope containing the given scope.
     */
    private fun findClassScope(scope: Scope): Scope? {
        var current: Scope? = scope
        while (current != null) {
            if (current.type == ScopeType.CLASS) {
                return current
            }
            current = current.parent
        }
        return null
    }
}

/**
 * Result of semantic analysis.
 */
data class SemanticAnalysisResult(val module: ModuleNode, val scopeTree: ScopeTree, val semanticModel: SemanticModel)

/**
 * Rich semantic model of the code.
 */
data class SemanticModel(
    val symbols: Map<String, List<SemanticSymbol>>,
    val typeInformation: Map<ASTNodeKey, TypeInfo>,
    val relationships: List<SymbolRelationship>,
)

/**
 * Key for identifying AST nodes in maps.
 */
data class ASTNodeKey(val nodeType: String, val line: Int, val column: Int)

/**
 * Enhanced symbol with semantic information.
 */
data class SemanticSymbol(
    val name: String,
    val kind: SemanticSymbolKind,
    val node: org.codehaus.groovy.ast.ASTNode,
    val declarationRange: SymbolRange,
    val qualifiedName: String? = null,
    val containingClass: String? = null,
    val containingMethod: String? = null,
    val type: String? = null,
    val parameters: List<ParameterInfo> = emptyList(),
)

/**
 * Kinds of semantic symbols.
 */
enum class SemanticSymbolKind {
    CLASS,
    METHOD,
    FIELD,
    PARAMETER,
    LOCAL_VARIABLE,
    INTERFACE,
    ENUM,
}

/**
 * Range information for symbols.
 */
data class SymbolRange(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int) {
    fun contains(line: Int, column: Int): Boolean = when {
        line < startLine || line > endLine -> false
        line == startLine && line == endLine -> column >= startColumn && column <= endColumn
        line == startLine -> column >= startColumn
        line == endLine -> column <= endColumn
        else -> true
    }
}

/**
 * Represents relationships between symbols.
 */
data class SymbolRelationship(val from: String, val to: String, val type: RelationshipType)

/**
 * Types of symbol relationships.
 */
enum class RelationshipType {
    EXTENDS,
    IMPLEMENTS,
    CALLS,
    REFERENCES,
    OVERRIDES,
}

/**
 * Semantic information at a specific position.
 */
data class SemanticInfo(
    val position: Position,
    val scope: Scope,
    val symbols: List<SemanticSymbol>,
    val availableCompletions: List<CompletionSuggestion>,
)

/**
 * Completion suggestion with semantic information.
 */
data class CompletionSuggestion(
    val label: String,
    val kind: CompletionKind,
    val detail: String,
    val documentation: String? = null,
)

/**
 * Kinds of completions.
 */
enum class CompletionKind {
    CLASS,
    METHOD,
    FIELD,
    VARIABLE,
    PARAMETER,
    MODULE,
    KEYWORD,
}
