package com.github.albertocavalcante.groovylsp.providers.semantictokens

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes
import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier
import java.net.URI

/**
 * Provides semantic tokens for all Groovy files (not just Jenkins).
 *
 * Implements full semantic highlighting for Groovy language constructs:
 * - Class/Interface/Enum declarations and references
 * - Method declarations and calls
 * - Variables, parameters, properties
 * - Type references
 * - Modifiers (static, final, etc.)
 *
 * This provider visits the AST and resolves each identifier to its
 * declaration type, mapping it to the appropriate LSP semantic token type.
 */
@Suppress("TooManyFunctions")
object GroovySemanticTokenProvider {

    private val logger = LoggerFactory.getLogger(GroovySemanticTokenProvider::class.java)

    /**
     * Type alias for semantic tokens.
     * Uses the shared SemanticToken type from JenkinsSemanticTokenProvider.
     */
    typealias SemanticToken = JenkinsSemanticTokenProvider.SemanticToken

    /**
     * Token type indices matching LSP semantic token legend.
     * Derived from JenkinsSemanticTokenProvider.LEGEND_TOKEN_TYPES to ensure consistency.
     */
    object TokenTypes {
        // Derive indices from the shared legend to prevent misalignment
        private val LEGEND = JenkinsSemanticTokenProvider.LEGEND_TOKEN_TYPES

        private fun indexFor(tokenType: String): Int {
            val index = LEGEND.indexOf(tokenType)
            require(index >= 0) {
                "Semantic token type '$tokenType' not found in legend. " +
                    "Check JenkinsSemanticTokenProvider.LEGEND_TOKEN_TYPES."
            }
            return index
        }

        val NAMESPACE = indexFor(SemanticTokenTypes.Namespace)
        val TYPE = indexFor(SemanticTokenTypes.Type)
        val CLASS = indexFor(SemanticTokenTypes.Class)
        val ENUM = indexFor(SemanticTokenTypes.Enum)
        val INTERFACE = indexFor(SemanticTokenTypes.Interface)
        val STRUCT = indexFor(SemanticTokenTypes.Struct)
        val TYPE_PARAMETER = indexFor(SemanticTokenTypes.TypeParameter)
        val PARAMETER = indexFor(SemanticTokenTypes.Parameter)
        val VARIABLE = indexFor(SemanticTokenTypes.Variable)
        val PROPERTY = indexFor(SemanticTokenTypes.Property)
        val ENUM_MEMBER = indexFor(SemanticTokenTypes.EnumMember)
        val EVENT = indexFor(SemanticTokenTypes.Event)
        val FUNCTION = indexFor(SemanticTokenTypes.Function)
        val METHOD = indexFor(SemanticTokenTypes.Method)
        val MACRO = indexFor(SemanticTokenTypes.Macro)
        val KEYWORD = indexFor(SemanticTokenTypes.Keyword)
        val MODIFIER = indexFor(SemanticTokenTypes.Modifier)
        val COMMENT = indexFor(SemanticTokenTypes.Comment)
        val STRING = indexFor(SemanticTokenTypes.String)
        val NUMBER = indexFor(SemanticTokenTypes.Number)
        val REGEXP = indexFor(SemanticTokenTypes.Regexp)
        val OPERATOR = indexFor(SemanticTokenTypes.Operator)
        val DECORATOR = indexFor(SemanticTokenTypes.Decorator)
    }

    /**
     * Token modifier bit masks.
     * Derived from JenkinsSemanticTokenProvider.LEGEND_TOKEN_MODIFIERS to ensure consistency.
     */
    object TokenModifiers {
        // Derive bit masks from the shared legend to prevent misalignment
        private val LEGEND = JenkinsSemanticTokenProvider.LEGEND_TOKEN_MODIFIERS

        private fun maskFor(modifier: String): Int {
            val index = LEGEND.indexOf(modifier)
            require(index >= 0) {
                "Semantic token modifier '$modifier' not found in legend. " +
                    "Check JenkinsSemanticTokenProvider.LEGEND_TOKEN_MODIFIERS."
            }
            return 1 shl index
        }

        val DECLARATION = maskFor(SemanticTokenModifiers.Declaration)
        val DEFINITION = maskFor(SemanticTokenModifiers.Definition)
        val READONLY = maskFor(SemanticTokenModifiers.Readonly)
        val STATIC = maskFor(SemanticTokenModifiers.Static)
        val DEPRECATED = maskFor(SemanticTokenModifiers.Deprecated)
        val ABSTRACT = maskFor(SemanticTokenModifiers.Abstract)
        val ASYNC = maskFor(SemanticTokenModifiers.Async)
        val MODIFICATION = maskFor(SemanticTokenModifiers.Modification)
        val DOCUMENTATION = maskFor(SemanticTokenModifiers.Documentation)
        val DEFAULT_LIBRARY = maskFor(SemanticTokenModifiers.DefaultLibrary)
    }

    /**
     * Thread-local source lines for offset calculation.
     * This avoids passing source lines through the entire call chain while maintaining thread safety.
     */
    private val sourceLines = ThreadLocal<List<String>>()

    /**
     * Generate semantic tokens for all Groovy constructs.
     *
     * @param astModel Parsed AST model
     * @param uri Document URI
     * @param sourceText Optional source text for accurate offset calculation
     * @return List of semantic tokens
     */
    fun getSemanticTokens(astModel: GroovyAstModel, uri: URI, sourceText: String? = null): List<SemanticToken> {
        try {
            // Set source lines for this thread
            sourceLines.set(sourceText?.lines() ?: emptyList())

            val tokens = mutableListOf<SemanticToken>()

            try {
                val allNodes = astModel.getAllNodes()
                val classNodes = astModel.getAllClassNodes()

                // Visit all class nodes to get declarations
                classNodes.forEach { classNode ->
                    visitClassDeclaration(classNode, tokens)
                }

                // Visit all nodes to find references and other constructs
                allNodes.forEach { node ->
                    when (node) {
                        is VariableExpression -> visitVariableExpression(node, tokens)
                        is PropertyExpression -> visitPropertyExpression(node, tokens)
                        is ClosureExpression -> visitClosureExpression(node, tokens)
                        is MethodCallExpression -> visitMethodCallExpression(node, tokens)
                        is StaticMethodCallExpression -> visitStaticMethodCallExpression(node, tokens)
                    }
                }

                logger.debug("Generated {} Groovy semantic tokens for {}", tokens.size, uri)
            } catch (e: NullPointerException) {
                logger.error("Null pointer encountered while generating semantic tokens for {}: {}", uri, e.message, e)
            } catch (e: IndexOutOfBoundsException) {
                logger.error("Index out of bounds while generating semantic tokens for {}: {}", uri, e.message, e)
            } catch (e: IllegalStateException) {
                logger.error("Illegal state while generating semantic tokens for {}: {}", uri, e.message, e)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Catch remaining exceptions to prevent LSP crashes
                logger.error(
                    "Unexpected error generating semantic tokens for {}: {} - {}",
                    uri,
                    e.javaClass.simpleName,
                    e.message,
                    e,
                )
            }

            return tokens
        } finally {
            // Clean up thread local to prevent memory leaks
            sourceLines.remove()
        }
    }

    /**
     * Visit a class declaration and add tokens for class name, members, etc.
     */
    private fun visitClassDeclaration(classNode: ClassNode, tokens: MutableList<SemanticToken>) {
        // Skip synthetic/generated classes
        if (classNode.isSynthetic || classNode.lineNumber < 0) {
            return
        }

        // Add token for class/interface/enum declaration
        addClassDeclarationToken(classNode, tokens)

        // Visit members
        visitClassMembers(classNode, tokens)

        // Visit superclass and interfaces
        visitClassHierarchy(classNode, tokens)
    }

    /**
     * Add token for class/interface/enum declaration.
     */
    private fun addClassDeclarationToken(classNode: ClassNode, tokens: MutableList<SemanticToken>) {
        val tokenType = when {
            classNode.isInterface -> TokenTypes.INTERFACE
            classNode.isEnum -> TokenTypes.ENUM
            else -> TokenTypes.CLASS
        }

        var modifiers = TokenModifiers.DECLARATION or TokenModifiers.DEFINITION

        if (classNode.isAbstract && !classNode.isInterface) {
            modifiers = modifiers or TokenModifiers.ABSTRACT
        }
        if (classNode.isStaticClass) {
            modifiers = modifiers or TokenModifiers.STATIC
        }

        addTokenForNode(classNode, classNode.nameWithoutPackage.length, tokenType, modifiers, tokens)
    }

    /**
     * Visit class members (methods, fields, properties).
     */
    private fun visitClassMembers(classNode: ClassNode, tokens: MutableList<SemanticToken>) {
        classNode.methods.forEach { method ->
            visitMethodDeclaration(method, tokens)
        }

        classNode.fields.forEach { field ->
            visitFieldDeclaration(field, tokens)
        }

        classNode.properties.forEach { property ->
            visitPropertyDeclaration(property, tokens)
        }
    }

    /**
     * Visit superclass and interfaces (type references).
     *
     * Note: java.lang.Object is explicitly excluded from type references because:
     * 1. Every class implicitly extends Object, so highlighting it adds noise
     * 2. Users rarely write "extends Object" explicitly
     * 3. The Object type reference usually doesn't have meaningful source positions
     */
    private fun visitClassHierarchy(classNode: ClassNode, tokens: MutableList<SemanticToken>) {
        // Visit superclass (excluding implicit Object inheritance)
        if (classNode.superClass != null && classNode.superClass.lineNumber > 0) {
            val superName = classNode.superClass.nameWithoutPackage
            if (superName != "Object") {
                addTokenForNode(
                    classNode.superClass,
                    superName.length,
                    TokenTypes.CLASS,
                    0,
                    tokens,
                )
            }
        }

        // Visit interfaces
        classNode.interfaces.forEach { interfaceNode ->
            if (interfaceNode.lineNumber > 0) {
                addTokenForNode(
                    interfaceNode,
                    interfaceNode.nameWithoutPackage.length,
                    TokenTypes.INTERFACE,
                    0,
                    tokens,
                )
            }
        }
    }

    /**
     * Visit a method declaration.
     */
    private fun visitMethodDeclaration(method: MethodNode, tokens: MutableList<SemanticToken>) {
        // Skip synthetic methods (generated getters/setters, etc.)
        if (method.isSynthetic || method.lineNumber < 0) {
            return
        }

        var modifiers = TokenModifiers.DECLARATION or TokenModifiers.DEFINITION

        if (method.isStatic) {
            modifiers = modifiers or TokenModifiers.STATIC
        }
        if (method.isAbstract) {
            modifiers = modifiers or TokenModifiers.ABSTRACT
        }

        // Calculate the actual method declaration line and offset
        // MethodNode.lineNumber may point to annotations if present
        val (declLine, declCol) = getMethodDeclarationPosition(method)

        // Calculate offset from declaration start to method name
        val nameOffset = calculateMethodNameOffset(method)
        addMethodToken(declLine, declCol, nameOffset, method.name.length, modifiers, tokens)

        // Visit parameters
        method.parameters.forEach { param ->
            visitParameter(param, tokens)
        }

        // Visit return type if present
        if (method.returnType != null && method.returnType.lineNumber > 0) {
            val typeName = method.returnType.nameWithoutPackage
            if (typeName != "Object" && typeName != "void") {
                addTokenForNode(
                    method.returnType,
                    typeName.length,
                    getTokenTypeForClassNode(method.returnType),
                    0,
                    tokens,
                )
            }
        }
    }

    /**
     * Visit a field declaration.
     */
    private fun visitFieldDeclaration(field: FieldNode, tokens: MutableList<SemanticToken>) {
        if (field.isSynthetic || field.lineNumber < 0) {
            return
        }

        var modifiers = TokenModifiers.DECLARATION or TokenModifiers.DEFINITION

        if (field.isStatic) {
            modifiers = modifiers or TokenModifiers.STATIC
        }
        if (field.isFinal) {
            modifiers = modifiers or TokenModifiers.READONLY
        }

        // Enum constants: all enum constants are implicitly static final, so we just check if owner is enum
        if (field.owner?.isEnum == true && field.type == field.owner) {
            addTokenForNode(field, field.name.length, TokenTypes.ENUM_MEMBER, modifiers, tokens)
        } else {
            addTokenForNode(field, field.name.length, TokenTypes.PROPERTY, modifiers, tokens)
        }

        // Visit field type
        if (field.type != null && field.type.lineNumber > 0) {
            val typeName = field.type.nameWithoutPackage
            if (typeName != "Object") {
                addTokenForNode(
                    field.type,
                    typeName.length,
                    getTokenTypeForClassNode(field.type),
                    0,
                    tokens,
                )
            }
        }
    }

    /**
     * Visit a property declaration.
     */
    private fun visitPropertyDeclaration(property: PropertyNode, tokens: MutableList<SemanticToken>) {
        // Properties in Groovy are often backed by fields, skip if synthetic
        if (property.isSynthetic || property.lineNumber < 0) {
            return
        }

        var modifiers = TokenModifiers.DECLARATION or TokenModifiers.DEFINITION

        if (property.isStatic) {
            modifiers = modifiers or TokenModifiers.STATIC
        }

        // Check if the backing field is final
        if (property.field?.isFinal == true) {
            modifiers = modifiers or TokenModifiers.READONLY
        }

        addTokenForNode(property, property.name.length, TokenTypes.PROPERTY, modifiers, tokens)
    }

    /**
     * Visit a parameter.
     */
    private fun visitParameter(param: Parameter, tokens: MutableList<SemanticToken>) {
        if (param.lineNumber < 0) {
            return
        }

        val modifiers = TokenModifiers.DECLARATION or TokenModifiers.DEFINITION

        addTokenForNode(param, param.name.length, TokenTypes.PARAMETER, modifiers, tokens)

        // Visit parameter type
        if (param.type != null && param.type.lineNumber > 0) {
            val typeName = param.type.nameWithoutPackage
            if (typeName != "Object") {
                addTokenForNode(
                    param.type,
                    typeName.length,
                    getTokenTypeForClassNode(param.type),
                    0,
                    tokens,
                )
            }
        }
    }

    /**
     * Visit a variable expression (variable reference).
     */
    private fun visitVariableExpression(varExpr: VariableExpression, tokens: MutableList<SemanticToken>) {
        if (varExpr.lineNumber < 0) {
            return
        }

        // Skip 'this' and 'super'
        if (varExpr.isThisExpression || varExpr.isSuperExpression) {
            return
        }

        // Try to determine the token type based on the variable
        // Check accessedVariable first for accurate type information
        val tokenType = when {
            varExpr.accessedVariable is Parameter -> TokenTypes.PARAMETER
            varExpr.accessedVariable is FieldNode -> TokenTypes.PROPERTY
            // Implicit closure parameter 'it' - only when unresolved (accessedVariable is null)
            varExpr.accessedVariable == null && varExpr.name == "it" -> TokenTypes.PARAMETER
            else -> TokenTypes.VARIABLE
        }

        addTokenForNode(varExpr, varExpr.name.length, tokenType, 0, tokens)
    }

    /**
     * Visit a property expression (e.g., obj.property).
     */
    private fun visitPropertyExpression(propExpr: PropertyExpression, tokens: MutableList<SemanticToken>) {
        if (propExpr.property.lineNumber < 0) {
            return
        }

        // The property part (after the dot)
        val propertyName = propExpr.propertyAsString ?: return

        addTokenForNode(propExpr.property, propertyName.length, TokenTypes.PROPERTY, 0, tokens)
    }

    /**
     * Visit a closure expression to handle closure parameters.
     */
    private fun visitClosureExpression(closure: ClosureExpression, tokens: MutableList<SemanticToken>) {
        // Visit closure parameters
        closure.parameters?.forEach { param ->
            visitParameter(param, tokens)
        }
    }

    /**
     * Visit a method call expression (e.g., obj.method() or method()).
     */
    private fun visitMethodCallExpression(methodCall: MethodCallExpression, tokens: MutableList<SemanticToken>) {
        val method = methodCall.method
        if (method.lineNumber < 0) {
            return
        }

        val methodName = methodCall.methodAsString ?: return

        addTokenForNode(method, methodName.length, TokenTypes.METHOD, 0, tokens)
    }

    /**
     * Visit a static method call expression (e.g., ClassName.method()).
     */
    private fun visitStaticMethodCallExpression(
        staticCall: StaticMethodCallExpression,
        tokens: MutableList<SemanticToken>,
    ) {
        if (staticCall.lineNumber < 0) {
            return
        }

        val methodName = staticCall.method
        if (methodName.isNullOrEmpty()) {
            return
        }

        // Static method calls use the expression's position directly
        // Column points to the method name in most cases
        addTokenForNode(staticCall, methodName.length, TokenTypes.METHOD, TokenModifiers.STATIC, tokens)
    }

    /**
     * Get the appropriate token type for a ClassNode.
     */
    private fun getTokenTypeForClassNode(classNode: ClassNode): Int = when {
        classNode.isInterface -> TokenTypes.INTERFACE
        classNode.isEnum -> TokenTypes.ENUM
        else -> TokenTypes.CLASS
    }

    /**
     * Get the actual method declaration position, accounting for annotations.
     *
     * MethodNode.lineNumber points to the first annotation if present, not the
     * actual method declaration line. This function finds the line after all
     * annotations where the actual modifiers/return type start.
     *
     * @return Pair of (line, column) in 1-based coordinates
     */
    private fun getMethodDeclarationPosition(method: MethodNode): Pair<Int, Int> {
        val annotations = method.annotations
        if (annotations.isNullOrEmpty()) {
            // No annotations, use the method's reported position
            return Pair(method.lineNumber, method.columnNumber)
        }

        // Find the last annotation's last line
        val lastAnnotationLine = annotations.maxOfOrNull { it.lastLineNumber } ?: method.lineNumber

        // The method declaration starts on the line after the last annotation
        // Use the method's column number as a reasonable estimate for the declaration start
        return Pair(lastAnnotationLine + 1, method.columnNumber)
    }

    /**
     * Add a method token with explicit line/column position.
     */
    private fun addMethodToken(
        line: Int,
        column: Int,
        columnOffset: Int,
        length: Int,
        modifiers: Int,
        tokens: MutableList<SemanticToken>,
    ) {
        if (length <= 0) return
        if (line > 0 && column > 0) {
            tokens.add(
                SemanticToken(
                    line = line - 1, // Convert to 0-based
                    startChar = column - 1 + columnOffset, // Convert to 0-based + offset
                    length = length,
                    tokenType = TokenTypes.METHOD,
                    tokenModifiers = modifiers,
                ),
            )
        }
    }

    /**
     * Calculate the column offset from the method declaration start to the method name.
     *
     * For a method like "static def myMethod()", the AST reports position at "static",
     * but we need the position of "myMethod". This calculates the offset based on
     * the method's modifiers and return type.
     *
     * If source text is available, it extracts the actual declaration line to find
     * the method name position, avoiding issues with generic type formatting variations
     * (e.g., "Map<String, Integer>" vs "Map<String,Integer>").
     *
     * Note: We exclude 'public' from the calculation because Groovy methods are
     * implicitly public - the modifier is rarely written explicitly. If we included
     * it, the offset would be wrong for 99% of Groovy code.
     */
    private fun calculateMethodNameOffset(method: MethodNode): Int {
        // Try to use actual source text if available
        val lines = sourceLines.get() ?: emptyList()
        val (declLine, declCol) = getMethodDeclarationPosition(method)
        // declLine is 1-based, so declLine <= lines.size ensures declLine - 1 < lines.size
        if (lines.isNotEmpty() && declLine > 0 && declLine <= lines.size && declCol > 0) {
            val sourceLine = lines[declLine - 1] // Convert to 0-based
            val methodName = method.name

            // Find the method name in the source line
            // We look for the method name followed by '(' to avoid false matches
            // Regex.escape handles any special characters in the method name
            val namePattern = Regex("""\b${Regex.escape(methodName)}\s*\(""")
            val match = namePattern.find(sourceLine)
            if (match != null) {
                // Calculate offset from the declaration start column to the method name
                // declCol is 1-based, match.range.first is 0-based
                // So the offset is: match.range.first - (declCol - 1)
                return match.range.first - (declCol - 1)
            }
        }

        // Fallback to reconstruction-based calculation if source text unavailable
        var offset = 0

        // Add modifier lengths + spaces
        // Note: We deliberately SKIP public because it's implicit in Groovy.
        // Methods are public by default and the keyword is almost never written.
        val modifierTexts = mutableListOf<String>()
        // Skip isPublic - it's implicit in Groovy
        if (Modifier.isProtected(method.modifiers)) modifierTexts.add("protected")
        if (Modifier.isPrivate(method.modifiers)) modifierTexts.add("private")
        if (Modifier.isStatic(method.modifiers)) modifierTexts.add("static")
        if (Modifier.isFinal(method.modifiers)) modifierTexts.add("final")
        if (Modifier.isAbstract(method.modifiers)) modifierTexts.add("abstract")
        if (Modifier.isSynchronized(method.modifiers)) modifierTexts.add("synchronized")

        // Calculate total modifier length including spaces
        offset += modifierTexts.sumOf { it.length + 1 } // +1 for space after each modifier

        // Add return type length + space
        // In Groovy, "def" methods have Object as return type in AST
        // Handle generic types like List<String> by reconstructing the full type text
        val returnTypeNode = method.returnType
        val sourceTypeName = if (returnTypeNode != null) {
            fun getSourceText(node: ClassNode): String {
                if (node.genericsTypes.isNullOrEmpty()) {
                    return node.nameWithoutPackage
                }
                val generics = node.genericsTypes.joinToString(", ") { gt ->
                    gt.type?.let { getSourceText(it) } ?: gt.name
                }
                return "${node.nameWithoutPackage}<$generics>"
            }
            val typeText = getSourceText(returnTypeNode)
            // Use "def" (3 chars) as the source representation, not "Object" (6 chars)
            if (typeText == "Object") "def" else typeText
        } else {
            "def"
        }
        offset += sourceTypeName.length + 1 // +1 for space after type

        return offset
    }

    /**
     * Add a token for an AST node if it has valid position information.
     */
    private fun addTokenForNode(
        node: ASTNode,
        length: Int,
        tokenType: Int,
        modifiers: Int,
        tokens: MutableList<SemanticToken>,
    ) {
        // Validate token before adding - skip invalid lengths
        if (length <= 0) return
        if (node.lineNumber > 0 && node.columnNumber > 0) {
            tokens.add(
                SemanticToken(
                    line = node.lineNumber - 1, // Convert to 0-based
                    startChar = node.columnNumber - 1, // Convert to 0-based
                    length = length,
                    tokenType = tokenType,
                    tokenModifiers = modifiers,
                ),
            )
        }
    }
}
