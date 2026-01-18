package com.github.albertocavalcante.groovylsp.providers.semantictokens

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode
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
import java.lang.reflect.Modifier

/**
 * Visits AST nodes and generates semantic tokens.
 *
 * This class traverses the Groovy AST and generates semantic tokens for
 * declarations, references, and other language constructs. It delegates
 * token type and modifier resolution to TokenTypeResolver and TokenModifierResolver.
 *
 * Responsibilities:
 * - Visit class declarations and their members
 * - Visit method declarations and calls
 * - Visit variable expressions, properties, and parameters
 * - Visit imports and annotations
 * - Calculate accurate token positions, especially for methods with complex return types
 */
@Suppress("TooManyFunctions")
class SemanticTokenVisitor(private val sourceLines: List<String>) {

    /**
     * Type alias for semantic tokens.
     */
    typealias SemanticToken = JenkinsSemanticTokenProvider.SemanticToken

    /**
     * Visit imports and generate tokens.
     * Unused imports get the UNNECESSARY modifier for visual dimming.
     */
    fun visitImports(moduleNode: ModuleNode, unusedImports: Set<ImportNode>, tokens: MutableList<SemanticToken>) {
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
        if (importNode.lineNumber <= 0 || importNode.columnNumber <= 0) return

        // For static imports, highlight the field/method name
        // For regular imports, highlight the alias if present, otherwise the class name
        val typeName = if (importNode.isStatic) {
            importNode.fieldName ?: return
        } else {
            importNode.alias ?: importNode.type?.nameWithoutPackage ?: return
        }

        val modifiers = TokenModifierResolver.getModifiersForImport(importNode, unusedImports)

        // Calculate position of the name to highlight in import statement
        val className = importNode.className ?: return

        // Account for "import " vs "import static " prefix
        val importPrefixLength = if (importNode.isStatic) {
            "import static ".length
        } else {
            "import ".length
        }

        val typeNameStart = if (importNode.isStatic) {
            // For static imports: position after full class name plus separator dot
            importPrefixLength + className.length + 1
        } else if (importNode.alias != null) {
            // For aliased imports: position after " as "
            importPrefixLength + className.length + " as ".length
        } else {
            // For regular imports: position after last dot in className
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
                startChar = (importNode.columnNumber - 1) + typeNameStart,
                length = typeName.length,
                tokenType = TokenTypeResolver.TokenTypes.CLASS,
                tokenModifiers = modifiers,
            ),
        )
    }

    /**
     * Visit a class declaration and add tokens for class name, members, etc.
     */
    fun visitClassDeclaration(classNode: ClassNode, tokens: MutableList<SemanticToken>) {
        // Skip synthetic/generated classes
        if (classNode.isSynthetic || classNode.lineNumber < 0) {
            return
        }

        // Add token for class/interface/enum declaration
        addClassDeclarationToken(classNode, tokens)

        // Tokenize class annotations
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
        val tokenType = TokenTypeResolver.getTokenTypeForClassNode(classNode)
        val modifiers = TokenModifierResolver.getModifiersForClassDeclaration(classNode)

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
     * Note: java.lang.Object is explicitly excluded from type references.
     */
    private fun visitClassHierarchy(classNode: ClassNode, tokens: MutableList<SemanticToken>) {
        // Visit superclass (excluding implicit Object inheritance)
        if (classNode.superClass != null && classNode.superClass.lineNumber > 0) {
            val superName = classNode.superClass.nameWithoutPackage
            if (superName != "Object") {
                addTokenForNode(
                    classNode.superClass,
                    superName.length,
                    TokenTypeResolver.TokenTypes.CLASS,
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
                    TokenTypeResolver.TokenTypes.INTERFACE,
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

        val modifiers = TokenModifierResolver.getModifiersForMethodDeclaration(method)

        // Calculate the actual method declaration line and offset
        val (declLine, declCol) = getMethodDeclarationPosition(method)

        // Calculate offset from declaration start to method name
        val nameOffset = calculateMethodNameOffset(method)
        addMethodToken(declLine, declCol, nameOffset, method.name.length, modifiers, tokens)

        // Tokenize annotations
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
                    TokenTypeResolver.getTokenTypeForClassNode(method.returnType),
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

        val modifiers = TokenModifierResolver.getModifiersForFieldDeclaration(field)
        val tokenType = TokenTypeResolver.getTokenTypeForField(field)

        addTokenForNode(field, field.name.length, tokenType, modifiers, tokens)

        // Visit field type
        if (field.type != null && field.type.lineNumber > 0) {
            val typeName = field.type.nameWithoutPackage
            if (typeName != "Object") {
                addTokenForNode(
                    field.type,
                    typeName.length,
                    TokenTypeResolver.getTokenTypeForClassNode(field.type),
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

        val modifiers = TokenModifierResolver.getModifiersForPropertyDeclaration(property)

        addTokenForNode(property, property.name.length, TokenTypeResolver.TokenTypes.PROPERTY, modifiers, tokens)
    }

    /**
     * Visit a parameter.
     */
    fun visitParameter(param: Parameter, tokens: MutableList<SemanticToken>) {
        if (param.lineNumber < 0) {
            return
        }

        val modifiers = TokenModifierResolver.getModifiersForParameterDeclaration()

        addTokenForNode(param, param.name.length, TokenTypeResolver.TokenTypes.PARAMETER, modifiers, tokens)

        // Visit parameter type
        if (param.type != null && param.type.lineNumber > 0) {
            val typeName = param.type.nameWithoutPackage
            if (typeName != "Object") {
                addTokenForNode(
                    param.type,
                    typeName.length,
                    TokenTypeResolver.getTokenTypeForClassNode(param.type),
                    0,
                    tokens,
                )
            }
        }
    }

    /**
     * Visit a variable expression (variable reference).
     */
    fun visitVariableExpression(varExpr: VariableExpression, tokens: MutableList<SemanticToken>) {
        if (varExpr.lineNumber < 0) {
            return
        }

        // Skip 'this' and 'super'
        if (varExpr.isThisExpression || varExpr.isSuperExpression) {
            return
        }

        val tokenType = TokenTypeResolver.getTokenTypeForVariableExpression(varExpr)

        addTokenForNode(varExpr, varExpr.name.length, tokenType, 0, tokens)
    }

    /**
     * Visit a property expression (e.g., obj.property).
     */
    fun visitPropertyExpression(propExpr: PropertyExpression, tokens: MutableList<SemanticToken>) {
        val propertyName = propExpr.propertyAsString

        // Special case: Handle '.class' expressions
        if (propertyName == "class") {
            handleClassLiteralExpression(propExpr, tokens)
            return
        }

        // Tokenize property
        if (propExpr.property.lineNumber < 0) {
            return
        }
        val propertyLength = propertyName?.length ?: return
        addTokenForNode(propExpr.property, propertyLength, TokenTypeResolver.TokenTypes.PROPERTY, 0, tokens)
    }

    /**
     * Handle class literal expressions like 'String.class', 'Map.class'.
     */
    private fun handleClassLiteralExpression(propExpr: PropertyExpression, tokens: MutableList<SemanticToken>) {
        val receiver = propExpr.objectExpression

        if (receiver is VariableExpression && receiver.lineNumber > 0) {
            // Check if the variable name starts with uppercase (indicates a class reference)
            val varName = receiver.name
            if (varName.firstOrNull()?.isUpperCase() == true) {
                // This is a class literal - tokenize the class name
                val tokenType = if (receiver.type != null) {
                    TokenTypeResolver.getTokenTypeForClassNode(receiver.type)
                } else {
                    TokenTypeResolver.TokenTypes.CLASS
                }
                addTokenForNode(receiver, varName.length, tokenType, 0, tokens)
            }
        } else if (receiver is ClassExpression) {
            // Handle explicit ClassExpression
            val classType = receiver.type
            if (classType != null) {
                val className = classType.nameWithoutPackage
                if (className.isNotEmpty() && receiver.lineNumber > 0 && receiver.columnNumber > 0) {
                    tokens.add(
                        SemanticToken(
                            line = receiver.lineNumber - 1,
                            startChar = receiver.columnNumber - 1,
                            length = className.length,
                            tokenType = TokenTypeResolver.getTokenTypeForClassNode(classType),
                            tokenModifiers = 0,
                        ),
                    )
                }
            }
        }

        // Tokenize 'class' keyword
        if (propExpr.property.lineNumber >= 0) {
            addTokenForNode(propExpr.property, "class".length, TokenTypeResolver.TokenTypes.KEYWORD, 0, tokens)
        }
    }

    /**
     * Visit a class expression (e.g., String.class).
     */
    fun visitClassExpression(classExpr: ClassExpression, tokens: MutableList<SemanticToken>) {
        if (classExpr.lineNumber > 0 && classExpr.columnNumber > 0) {
            val classType = classExpr.type
            if (classType != null) {
                val className = classType.nameWithoutPackage
                if (className.isNotEmpty()) {
                    tokens.add(
                        SemanticToken(
                            line = classExpr.lineNumber - 1,
                            startChar = classExpr.columnNumber - 1,
                            length = className.length,
                            tokenType = TokenTypeResolver.TokenTypes.CLASS,
                            tokenModifiers = 0,
                        ),
                    )

                    // Also add a token for the '.class' keyword suffix
                    val classKeywordLength = "class".length
                    tokens.add(
                        SemanticToken(
                            line = classExpr.lineNumber - 1,
                            startChar = classExpr.columnNumber - 1 + className.length + 1,
                            length = classKeywordLength,
                            tokenType = TokenTypeResolver.TokenTypes.KEYWORD,
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
    fun visitClosureExpression(closure: ClosureExpression, tokens: MutableList<SemanticToken>) {
        // Visit closure parameters
        closure.parameters?.forEach { param ->
            visitParameter(param, tokens)
        }
    }

    /**
     * Visit a method call expression (e.g., obj.method() or method()).
     */
    fun visitMethodCallExpression(methodCall: MethodCallExpression, tokens: MutableList<SemanticToken>) {
        // Tokenize method name
        val method = methodCall.method
        if (method.lineNumber < 0) {
            return
        }

        val methodName = methodCall.methodAsString ?: return

        addTokenForNode(method, methodName.length, TokenTypeResolver.TokenTypes.METHOD, 0, tokens)
    }

    /**
     * Visit a static method call expression (e.g., ClassName.method()).
     */
    fun visitStaticMethodCallExpression(staticCall: StaticMethodCallExpression, tokens: MutableList<SemanticToken>) {
        if (staticCall.lineNumber < 0) {
            return
        }

        val methodName = staticCall.method
        if (methodName.isNullOrEmpty()) {
            return
        }

        val modifiers = TokenModifierResolver.getModifiersForStaticMethodCall()

        addTokenForNode(staticCall, methodName.length, TokenTypeResolver.TokenTypes.METHOD, modifiers, tokens)
    }

    /**
     * Get the actual method declaration position, accounting for annotations.
     */
    private fun getMethodDeclarationPosition(method: MethodNode): Pair<Int, Int> {
        val annotations = method.annotations
        if (annotations.isNullOrEmpty()) {
            return Pair(method.lineNumber, method.columnNumber)
        }

        // Find the last annotation's last line
        val lastAnnotationLine = annotations.maxOfOrNull { it.lastLineNumber } ?: method.lineNumber

        // The method declaration starts on the line after the last annotation
        return Pair(lastAnnotationLine + 1, method.columnNumber)
    }

    /**
     * Add a method token with explicit line/column position.
     */
    @Suppress("LongParameterList") // Necessary for precise token positioning
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
                    tokenType = TokenTypeResolver.TokenTypes.METHOD,
                    tokenModifiers = modifiers,
                ),
            )
        }
    }

    /**
     * Calculate the column offset from the method declaration start to the method name.
     */
    private fun calculateMethodNameOffset(method: MethodNode): Int {
        // Try source-based calculation first
        findMethodNameOffsetFromSource(method)?.let { return it }

        // Fallback to reconstruction-based calculation
        var offset = 0

        // Add modifier lengths + spaces (skip public as it's implicit in Groovy)
        val modifierTexts = mutableListOf<String>()
        if (Modifier.isProtected(method.modifiers)) modifierTexts.add("protected")
        if (Modifier.isPrivate(method.modifiers)) modifierTexts.add("private")
        if (Modifier.isStatic(method.modifiers)) modifierTexts.add("static")
        if (Modifier.isFinal(method.modifiers)) modifierTexts.add("final")
        if (Modifier.isAbstract(method.modifiers)) modifierTexts.add("abstract")
        if (Modifier.isSynchronized(method.modifiers)) modifierTexts.add("synchronized")

        offset += modifierTexts.sumOf { it.length + 1 } // +1 for space after each modifier

        // Add return type length + space
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
     * Try to find the method name offset by searching the actual source text.
     */
    private fun findMethodNameOffsetFromSource(method: MethodNode): Int? {
        if (sourceLines.isEmpty()) return null

        val (declLine, declCol) = getMethodDeclarationPosition(method)
        if (!isValidSourcePosition(declLine, declCol, sourceLines.size)) return null

        val sourceLine = sourceLines[declLine - 1] // Convert to 0-based
        val methodName = method.name

        // Find the method name followed by '(' to avoid false matches
        val namePattern = Regex("""\b${Regex.escape(methodName)}\s*\(""")
        val declStartIndex = declCol - 1 // Convert 1-based to 0-based
        val match = namePattern.find(sourceLine, declStartIndex) ?: return null

        return match.range.first - declStartIndex
    }

    /**
     * Check if source position is valid for the given line count.
     */
    private fun isValidSourcePosition(line: Int, column: Int, lineCount: Int): Boolean =
        line > 0 && line <= lineCount && column > 0

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
     */
    @Suppress("NestedBlockDepth") // Necessary for annotation position validation
    fun tokenizeAnnotations(annotations: List<AnnotationNode>?, tokens: MutableList<SemanticToken>) {
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
                            tokenType = TokenTypeResolver.TokenTypes.DECORATOR,
                            tokenModifiers = 0,
                        ),
                    )
                }
            }
        }
    }
}
