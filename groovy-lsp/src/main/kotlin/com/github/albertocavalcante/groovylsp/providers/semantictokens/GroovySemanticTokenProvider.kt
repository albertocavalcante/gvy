package com.github.albertocavalcante.groovylsp.providers.semantictokens

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes
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

    private val logger = KotlinLogging.logger {}

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
        val UNNECESSARY = maskFor("unnecessary") // For unused imports dimming
    }

    /**
     * Generate semantic tokens for all Groovy constructs.
     *
     * @param astModel Parsed AST model
     * @param uri Document URI
     * @param unusedImports Set of unused ImportNodes (for marking with UNNECESSARY modifier)
     * @param moduleNode Optional ModuleNode to get imports from (for generating import tokens)
     * @return List of semantic tokens
     */
    fun getSemanticTokens(
        astModel: GroovyAstModel,
        uri: URI,
        unusedImports: Set<ImportNode> = emptySet(),
        moduleNode: ModuleNode? = null,
    ): List<SemanticToken> {
        val tokens = mutableListOf<SemanticToken>()

        try {
            val allNodes = astModel.getAllNodes()
            val classNodes = astModel.getAllClassNodes()

            // Visit imports to generate tokens with UNNECESSARY modifier for unused ones
            if (moduleNode != null) {
                visitImports(moduleNode, unusedImports, tokens)
            }

            // Visit all class nodes to get declarations
            classNodes.forEach { classNode ->
                visitClassDeclaration(classNode, tokens)
            }

            // Visit all nodes to find references and other constructs
            allNodes.forEach { node ->
                when (node) {
                    is VariableExpression -> visitVariableExpression(node, tokens)
                    is PropertyExpression -> visitPropertyExpression(node, tokens)
                    is ClassExpression -> visitClassExpression(node, tokens)
                    is ClosureExpression -> visitClosureExpression(node, tokens)
                    is MethodCallExpression -> visitMethodCallExpression(node, tokens)
                    is StaticMethodCallExpression -> visitStaticMethodCallExpression(node, tokens)
                }
            }

            logger.debug { "Generated ${tokens.size} Groovy semantic tokens for $uri" }
        } catch (e: NullPointerException) {
            logger.error(e) { "Null pointer encountered while generating semantic tokens for $uri: ${e.message}" }
        } catch (e: IndexOutOfBoundsException) {
            logger.error(e) { "Index out of bounds while generating semantic tokens for $uri: ${e.message}" }
        } catch (e: IllegalStateException) {
            logger.error(e) { "Illegal state while generating semantic tokens for $uri: ${e.message}" }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Catch remaining exceptions to prevent LSP crashes
            logger.error(e) {
                "Unexpected error generating semantic tokens for $uri: ${e.javaClass.simpleName} - ${e.message}"
            }
        }

        return tokens
    }

    /**
     * Visit import statements and generate tokens.
     * Unused imports get the UNNECESSARY modifier for visual dimming.
     */
    private fun visitImports(
        moduleNode: ModuleNode,
        unusedImports: Set<ImportNode>,
        tokens: MutableList<SemanticToken>,
    ) {
        moduleNode.imports.forEach { importNode ->
            visitImportNode(importNode, unusedImports, tokens)
        }
        // Also cover non-star static imports for dimming
        // Note: moduleNode.staticStarImports intentionally excluded (star imports always considered used)
        moduleNode.staticImports.values.forEach { importNode ->
            visitImportNode(importNode, unusedImports, tokens)
        }
    }

    /**
     * Visit a single import node and generate a semantic token.
     */
    private fun visitImportNode(
        importNode: ImportNode,
        unusedImports: Set<ImportNode>,
        tokens: MutableList<SemanticToken>,
    ) {
        // Groovy AST uses 1-based line/column numbers; 0 or negative means "unknown position"
        // Match pattern from NativeParserAdapter.kt:103 and DocumentHighlightProvider.kt:178
        if (importNode.lineNumber <= 0 || importNode.columnNumber <= 0) return

        // For static imports, highlight the field/method name
        // (e.g., "emptyMap" in "import static Collections.emptyMap")
        // For regular imports, highlight the alias if present, otherwise the class name
        // (e.g., "AL" in "import java.util.ArrayList as AL" or "ArrayList" in "import java.util.ArrayList")
        val typeName = if (importNode.isStatic) {
            importNode.fieldName ?: return
        } else {
            importNode.alias ?: importNode.type?.nameWithoutPackage ?: return
        }

        // Mark imports with DECLARATION modifier for distinct styling
        var modifiers = TokenModifiers.DECLARATION
        if (importNode in unusedImports) {
            modifiers = modifiers or TokenModifiers.UNNECESSARY
        }

        // Calculate position of the name to highlight in import statement
        // Regular import: "import java.util.ArrayList" -> ArrayList starts after last dot in className
        // Regular aliased import: "import java.util.ArrayList as AL" -> AL starts after " as "
        // Static import: "import static java.util.Collections.emptyMap" -> emptyMap starts after className + dot
        // ImportNode provides the position of the start of the import statement via columnNumber
        val className = importNode.className ?: return

        // Account for "import " vs "import static " prefix
        val importPrefixLength = if (importNode.isStatic) {
            "import static ".length
        } else {
            "import ".length
        }

        val typeNameStart = if (importNode.isStatic) {
            // For static imports: position after full class name plus separator dot
            // e.g., "import static java.util.Collections.emptyMap"
            // className = "java.util.Collections", need to add dot before fieldName
            importPrefixLength + className.length + 1
        } else if (importNode.alias != null) {
            // For aliased imports: position after " as "
            // e.g., "import java.util.ArrayList as AL"
            importPrefixLength + className.length + " as ".length
        } else {
            // For regular imports: position after last dot in className
            // e.g., "import java.util.ArrayList"
            val lastDotIndex = className.lastIndexOf('.')
            if (lastDotIndex >= 0) {
                importPrefixLength + lastDotIndex + 1
            } else {
                importPrefixLength
            }
        }

        tokens.add(
            SemanticToken(
                line = importNode.lineNumber - 1, // Convert to 0-based
                startChar = (importNode.columnNumber - 1) + typeNameStart, // Account for import start position
                length = typeName.length,
                tokenType = TokenTypes.CLASS,
                tokenModifiers = modifiers,
            ),
        )
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

        // Tokenize class annotations (e.g., @Entity, @Service)
        tokenizeAnnotations(classNode.annotations, tokens)

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

        // Tokenize annotations (e.g., @Override, @Test)
        tokenizeAnnotations(method.annotations, tokens)

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
     * Tokenizes both the receiver (obj) and the property.
     */
    private fun visitPropertyExpression(propExpr: PropertyExpression, tokens: MutableList<SemanticToken>) {
        val propertyName = propExpr.propertyAsString

        // Special case: Handle '.class' expressions like 'String.class', 'Map.class'
        // In Groovy AST, 'String.class' is represented as:
        //   PropertyExpression(objectExpression=VariableExpression("String"), property="class")
        // The VariableExpression's name is the class name (e.g., "String", "Map", "List")
        if (propertyName == "class") {
            val receiver = propExpr.objectExpression
            if (receiver is VariableExpression && receiver.lineNumber > 0) {
                // Check if the variable name starts with uppercase (indicates a class reference)
                val varName = receiver.name
                if (varName.firstOrNull()?.isUpperCase() == true) {
                    // This is a class literal - tokenize the class name
                    // Try to get type information if available
                    val tokenType = if (receiver.type != null) {
                        getTokenTypeForClassNode(receiver.type)
                    } else {
                        TokenTypes.CLASS // Default to CLASS type
                    }
                    addTokenForNode(receiver, varName.length, tokenType, 0, tokens)
                }
            } else if (receiver is ClassExpression) {
                // Handle explicit ClassExpression (less common)
                val classType = receiver.type
                if (classType != null) {
                    val className = classType.nameWithoutPackage
                    if (className.isNotEmpty()) {
                        if (receiver.lineNumber > 0 && receiver.columnNumber > 0) {
                            tokens.add(
                                SemanticToken(
                                    line = receiver.lineNumber - 1,
                                    startChar = receiver.columnNumber - 1,
                                    length = className.length,
                                    tokenType = getTokenTypeForClassNode(classType),
                                    tokenModifiers = 0,
                                ),
                            )
                        } else if (receiver.lineNumber > 0 && receiver.columnNumber > 0) {
                            tokens.add(
                                SemanticToken(
                                    line = receiver.lineNumber - 1,
                                    startChar = receiver.columnNumber - 1,
                                    length = className.length,
                                    tokenType = getTokenTypeForClassNode(classType),
                                    tokenModifiers = 0,
                                ),
                            )
                        }
                    }
                }
            }

            // Tokenize 'class' keyword
            if (propExpr.property.lineNumber >= 0) {
                addTokenForNode(propExpr.property, "class".length, TokenTypes.KEYWORD, 0, tokens)
            }
            return
        }

        // Regular property expression handling:
        // The receiver (objectExpression) is visited and tokenized via the main AST traversal
        // (e.g., visitVariableExpression), so we only need to tokenize the property here.

        // Tokenize property
        if (propExpr.property.lineNumber < 0) {
            return
        }
        val propertyLength = propertyName?.length ?: return
        addTokenForNode(propExpr.property, propertyLength, TokenTypes.PROPERTY, 0, tokens)
    }

    /**
     * Visit a class expression (e.g., String.class).
     * This handles class literals where the ClassExpression is directly in the AST.
     */
    private fun visitClassExpression(classExpr: ClassExpression, tokens: MutableList<SemanticToken>) {
        // ClassExpression is used in .class literals like String.class, Map.class
        if (classExpr.lineNumber > 0 && classExpr.columnNumber > 0) {
            val classType = classExpr.type
            if (classType != null) {
                val className = classType.nameWithoutPackage
                if (className.isNotEmpty()) {
                    // For .class literals, always use CLASS token type regardless of whether
                    // the type is actually a class, interface, or enum. This matches IDE conventions.
                    tokens.add(
                        SemanticToken(
                            line = classExpr.lineNumber - 1,
                            startChar = classExpr.columnNumber - 1,
                            length = className.length,
                            tokenType = TokenTypes.CLASS,
                            tokenModifiers = 0,
                        ),
                    )

                    // Also add a token for the '.class' keyword suffix
                    // The 'class' keyword starts at: classNameStart + classNameLength + 1 (for the '.')
                    val classKeywordLength = "class".length
                    tokens.add(
                        SemanticToken(
                            line = classExpr.lineNumber - 1,
                            startChar = classExpr.columnNumber - 1 + className.length + 1,
                            length = classKeywordLength,
                            tokenType = TokenTypes.KEYWORD,
                            tokenModifiers = 0,
                        ),
                    )
                }
            }
        }
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
     * Tokenizes the method name.
     */
    private fun visitMethodCallExpression(methodCall: MethodCallExpression, tokens: MutableList<SemanticToken>) {
        // Tokenize method name
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
     * Note: We exclude 'public' from the calculation because Groovy methods are
     * implicitly public - the modifier is rarely written explicitly. If we included
     * it, the offset would be wrong for 99% of Groovy code.
     */
    private fun calculateMethodNameOffset(method: MethodNode): Int {
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

    /**
     * Tokenize annotations with robust length calculation.
     * Uses lastColumnNumber when available, falls back to name length + 1 for @ symbol.
     */
    private fun tokenizeAnnotations(
        annotations: List<org.codehaus.groovy.ast.AnnotationNode>?,
        tokens: MutableList<SemanticToken>,
    ) {
        annotations?.forEach { annotation ->
            if (annotation.lineNumber > 0 && annotation.columnNumber > 0) {
                val length = if (annotation.lastLineNumber == annotation.lineNumber &&
                    annotation.lastColumnNumber > annotation.columnNumber
                ) {
                    annotation.lastColumnNumber - annotation.columnNumber
                } else {
                    // Fallback for multi-line or when end position is not available
                    val annotationName = annotation.classNode?.nameWithoutPackage
                    if (annotationName != null) annotationName.length + 1 else 0
                }

                if (length > 0) {
                    tokens.add(
                        SemanticToken(
                            line = annotation.lineNumber - 1,
                            startChar = annotation.columnNumber - 1,
                            length = length,
                            tokenType = TokenTypes.DECORATOR,
                            tokenModifiers = 0,
                        ),
                    )
                }
            }
        }
    }
}
