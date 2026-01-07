package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.control.SourceUnit
import java.net.URI

/**
 * AST visitor that extracts semantic information from a compiled ModuleNode.
 * Builds a SemanticDocument containing all symbols and their occurrences.
 *
 * Following Metals/SemanticDB conventions for symbol IDs and occurrence tracking.
 */
class SemanticDocumentBuilder(private val moduleNode: ModuleNode, private val uri: URI) {
    private val symbols = mutableListOf<SymbolInfo>()
    private val occurrences = mutableListOf<SymbolOccurrence>()

    /**
     * Build the semantic document by walking the entire AST
     */
    fun build(): SemanticDocument {
        // Extract imports
        moduleNode.imports.forEach { extractImport(it) }
        moduleNode.starImports.forEach { extractImport(it) }
        moduleNode.staticImports.forEach { (_, importNode) -> extractStaticImport(importNode) }
        moduleNode.staticStarImports.forEach { (_, importNode) -> extractStaticImport(importNode) }

        // Extract classes and their members
        moduleNode.classes.forEach { classNode ->
            extractClass(classNode)
        }

        // Extract script methods (top-level methods in scripts)
        moduleNode.methods.forEach { methodNode ->
            extractMethod(methodNode, null)
        }

        return SemanticDocument(uri, symbols, occurrences)
    }

    /**
     * Extract import symbol
     */
    private fun extractImport(importNode: ImportNode) {
        val range = nodeToRange(importNode) ?: return
        val symbolId = createImportSymbolId(importNode)
        val name = importNode.alias ?: importNode.className ?: importNode.packageName

        symbols.add(
            SymbolInfo(
                symbol = symbolId,
                kind = SymbolKind.IMPORT,
                range = range,
                name = name,
                owner = null,
            ),
        )

        // Add occurrence for the import itself
        occurrences.add(
            SymbolOccurrence(
                symbol = symbolId,
                range = range,
                role = OccurrenceRole.IMPORT,
            ),
        )
    }

    /**
     * Extract static import symbol
     */
    private fun extractStaticImport(importNode: ImportNode) {
        val range = nodeToRange(importNode) ?: return
        val symbolId = createStaticImportSymbolId(importNode)
        val name = importNode.fieldName ?: importNode.alias ?: importNode.className

        symbols.add(
            SymbolInfo(
                symbol = symbolId,
                kind = SymbolKind.IMPORT,
                range = range,
                name = name,
                owner = null,
            ),
        )

        occurrences.add(
            SymbolOccurrence(
                symbol = symbolId,
                range = range,
                role = OccurrenceRole.IMPORT,
            ),
        )
    }

    /**
     * Extract class symbol and all its members
     */
    private fun extractClass(classNode: ClassNode) {
        // Skip synthetic/generated classes
        if (classNode.isSynthetic || classNode.name.contains("$")) {
            return
        }

        val range = nodeToRange(classNode) ?: return
        val symbolId = createClassSymbolId(classNode)

        val kind = when {
            classNode.isInterface -> SymbolKind.INTERFACE
            classNode.isEnum -> SymbolKind.ENUM
            else -> SymbolKind.CLASS
        }

        symbols.add(
            SymbolInfo(
                symbol = symbolId,
                kind = kind,
                range = range,
                name = classNode.nameWithoutPackage,
                owner = null,
            ),
        )

        // Add definition occurrence
        occurrences.add(
            SymbolOccurrence(
                symbol = symbolId,
                range = range,
                role = OccurrenceRole.DEFINITION,
            ),
        )

        // Extract fields
        classNode.fields.forEach { fieldNode ->
            extractField(fieldNode, classNode)
        }

        // Extract properties
        classNode.properties.forEach { propertyNode ->
            extractProperty(propertyNode, classNode)
        }

        // Extract methods
        classNode.methods.forEach { methodNode ->
            extractMethod(methodNode, classNode)
        }

        // Visit the class body to extract occurrences (method calls, field accesses, etc.)
        val visitor = OccurrenceVisitor(classNode)
        classNode.visitContents(visitor)
    }

    /**
     * Extract field symbol
     */
    private fun extractField(fieldNode: FieldNode, owner: ClassNode) {
        val range = nodeToRange(fieldNode) ?: return
        val ownerSymbolId = createClassSymbolId(owner)
        val symbolId = createFieldSymbolId(owner, fieldNode)

        symbols.add(
            SymbolInfo(
                symbol = symbolId,
                kind = SymbolKind.FIELD,
                range = range,
                name = fieldNode.name,
                owner = ownerSymbolId,
            ),
        )

        occurrences.add(
            SymbolOccurrence(
                symbol = symbolId,
                range = range,
                role = OccurrenceRole.DEFINITION,
            ),
        )
    }

    /**
     * Extract property symbol
     */
    private fun extractProperty(propertyNode: PropertyNode, owner: ClassNode) {
        val range = nodeToRange(propertyNode) ?: return
        val ownerSymbolId = createClassSymbolId(owner)
        val symbolId = createPropertySymbolId(owner, propertyNode)

        symbols.add(
            SymbolInfo(
                symbol = symbolId,
                kind = SymbolKind.PROPERTY,
                range = range,
                name = propertyNode.name,
                owner = ownerSymbolId,
            ),
        )

        occurrences.add(
            SymbolOccurrence(
                symbol = symbolId,
                range = range,
                role = OccurrenceRole.DEFINITION,
            ),
        )
    }

    /**
     * Extract method symbol
     */
    private fun extractMethod(methodNode: MethodNode, owner: ClassNode?) {
        val range = nodeToRange(methodNode) ?: return
        val ownerSymbolId = owner?.let { createClassSymbolId(it) }
        val symbolId = createMethodSymbolId(owner, methodNode)

        val kind = when {
            methodNode is ConstructorNode -> SymbolKind.CONSTRUCTOR
            else -> SymbolKind.METHOD
        }

        symbols.add(
            SymbolInfo(
                symbol = symbolId,
                kind = kind,
                range = range,
                name = methodNode.name,
                owner = ownerSymbolId,
            ),
        )

        occurrences.add(
            SymbolOccurrence(
                symbol = symbolId,
                range = range,
                role = OccurrenceRole.DEFINITION,
            ),
        )

        // Extract parameters
        methodNode.parameters.forEach { parameter ->
            extractParameter(parameter, methodNode, owner)
        }
    }

    /**
     * Extract parameter symbol
     */
    private fun extractParameter(parameter: Parameter, method: MethodNode, owner: ClassNode?) {
        val range = nodeToRange(parameter) ?: return
        val ownerSymbolId = createMethodSymbolId(owner, method)
        val symbolId = createParameterSymbolId(owner, method, parameter)

        symbols.add(
            SymbolInfo(
                symbol = symbolId,
                kind = SymbolKind.PARAMETER,
                range = range,
                name = parameter.name,
                owner = ownerSymbolId,
            ),
        )

        occurrences.add(
            SymbolOccurrence(
                symbol = symbolId,
                range = range,
                role = OccurrenceRole.DEFINITION,
            ),
        )
    }

    /**
     * Visitor for extracting occurrences (references, calls, etc.)
     * Tracks local variable types to correctly attribute method/property accesses to external types.
     */
    private inner class OccurrenceVisitor(private val currentClass: ClassNode) : ClassCodeVisitorSupport() {

        // Track variable names to their declared types (e.g., "calc" -> Calculator ClassNode)
        private val localVariableTypes = mutableMapOf<String, ClassNode>()

        override fun getSourceUnit(): SourceUnit? = null

        override fun visitMethod(node: MethodNode) {
            // New scope for each method
            localVariableTypes.clear()
            // Track parameters as local variables
            node.parameters?.forEach { param ->
                if (param.type != null) {
                    localVariableTypes[param.name] = param.type
                }
            }
            super.visitMethod(node)
        }

        override fun visitDeclarationExpression(expression: DeclarationExpression) {
            // Track the declared type of local variables
            // For "Calculator calc = new Calculator()", store "calc" -> Calculator type
            val varExpr = expression.variableExpression
            val declaredType = varExpr.type
            // Only track if the declared type is meaningful (not Object)
            if (declaredType.name != "java.lang.Object" && declaredType.name != "Object") {
                localVariableTypes[varExpr.name] = declaredType
            }
            super.visitDeclarationExpression(expression)
        }

        private fun resolveTypeName(type: ClassNode): String {
            // If already fully qualified (contains dots), just use it
            if (type.name.contains(".")) {
                return type.name.replace('.', '/')
            }

            // Try redirect (might point to FQN)
            if (type.redirect() != type && type.redirect().name.contains(".")) {
                return type.redirect().name.replace('.', '/')
            }

            // Fallback: prepend current package if available
            val packageName = moduleNode.packageName
            if (!packageName.isNullOrEmpty()) {
                val pkgPath = packageName.replace('.', '/')
                return if (pkgPath.endsWith("/")) {
                    "${pkgPath}${type.name}"
                } else {
                    "$pkgPath/${type.name}"
                }
            }

            return type.name
        }

        override fun visitMethodCallExpression(call: MethodCallExpression) {
            val range = nodeToRange(call) ?: return super.visitMethodCallExpression(call)

            // Resolve the receiver type to get the correct owner class
            // For "calc.add()", use Calculator (from localVariableTypes) not Main
            val receiverType = resolveReceiverNode(call.objectExpression)
            val methodName = call.methodAsString
            val ownerName = if (receiverType != null) {
                resolveTypeName(receiverType)
            } else {
                resolveTypeName(currentClass)
            }
            // Use just the method name + empty parens for now, matching indexing logic
            val symbolId = "$ownerName#$methodName()."

            occurrences.add(
                SymbolOccurrence(
                    symbol = symbolId,
                    range = range,
                    role = OccurrenceRole.CALL,
                ),
            )

            super.visitMethodCallExpression(call)
        }

        override fun visitConstructorCallExpression(call: ConstructorCallExpression) {
            val range = nodeToRange(call) ?: return super.visitConstructorCallExpression(call)

            // Create symbol ID for constructor call
            val typeName = resolveTypeName(call.type)
            // Use class symbol ID to resolve to the class definition
            // TODO: Support precise constructor resolution with parameters
            val symbolId = "$typeName#"

            occurrences.add(
                SymbolOccurrence(
                    symbol = symbolId,
                    range = range,
                    role = OccurrenceRole.CALL,
                ),
            )

            super.visitConstructorCallExpression(call)
        }

        override fun visitVariableExpression(expression: VariableExpression) {
            val range = nodeToRange(expression) ?: return super.visitVariableExpression(expression)

            // Variable reference
            // Use resolveTypeName for consistency, though currentClass.name is usually FQN
            val className = resolveTypeName(currentClass)
            val symbolId = "$className#${expression.name}."

            occurrences.add(
                SymbolOccurrence(
                    symbol = symbolId,
                    range = range,
                    role = OccurrenceRole.REFERENCE,
                ),
            )

            super.visitVariableExpression(expression)
        }

        override fun visitPropertyExpression(expression: PropertyExpression) {
            val range = nodeToRange(expression) ?: return super.visitPropertyExpression(expression)

            // Resolve the receiver type for property access
            // For "calc.value", use Calculator (from localVariableTypes) not Main
            val receiverType = resolveReceiverNode(expression.objectExpression)
            val propertyName = expression.propertyAsString
            val ownerName = if (receiverType != null) {
                resolveTypeName(receiverType)
            } else {
                resolveTypeName(currentClass)
            }
            val symbolId = "$ownerName#$propertyName."

            occurrences.add(
                SymbolOccurrence(
                    symbol = symbolId,
                    range = range,
                    role = OccurrenceRole.REFERENCE,
                ),
            )

            super.visitPropertyExpression(expression)
        }

        /**
         * Resolve the type of the receiver expression.
         * Uses local variable type tracking from DeclarationExpressions.
         * Returns the ClassNode, or null to use current class as fallback.
         */
        private fun resolveReceiverNode(receiver: Expression): ClassNode? {
            // Check if the receiver is a variable with a tracked declared type
            if (receiver is VariableExpression) {
                localVariableTypes[receiver.name]?.let { return it }
            }

            // Fallback to expression type (usually Object for unresolved)
            val exprType = receiver.type
            return when {
                exprType == null -> null
                exprType.name == "java.lang.Object" -> null
                exprType.name == "Object" -> null
                exprType.name.contains("$") -> null // Synthetic
                exprType.name == currentClass.name -> null // Same class, use default
                exprType.name.isNotEmpty() -> exprType
                else -> null
            }
        }
    }

    /**
     * Convert AST node to Range (0-indexed)
     */
    private fun nodeToRange(node: Any): Range? {
        return try {
            // Use reflection to get line and column info from ASTNode
            val lineStart = node.javaClass.getMethod("getLineNumber").invoke(node) as? Int ?: return null
            val columnStart = node.javaClass.getMethod("getColumnNumber").invoke(node) as? Int ?: return null
            val lineEnd = node.javaClass.getMethod("getLastLineNumber").invoke(node) as? Int ?: lineStart
            val columnEnd = node.javaClass.getMethod("getLastColumnNumber").invoke(node) as? Int ?: columnStart

            // Convert to 0-indexed (Groovy uses 1-indexed)
            if (lineStart <= 0 || columnStart <= 0) return null

            Range(
                startLine = lineStart - 1,
                startColumn = columnStart - 1,
                endLine = lineEnd - 1,
                endColumn = columnEnd,
            )
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /**
         * Create unique symbol ID for a class following SemanticDB convention.
         * Format: "com/example/MyClass#"
         */
        fun createClassSymbolId(classNode: ClassNode): String = "${classNode.name.replace('.', '/')}#"

        /**
         * Create unique symbol ID for a field.
         * Format: "com/example/MyClass#myField."
         */
        fun createFieldSymbolId(owner: ClassNode, field: FieldNode): String =
            "${createClassSymbolId(owner)}${field.name}."

        /**
         * Create unique symbol ID for a property.
         * Format: "com/example/MyClass#myProperty."
         */
        fun createPropertySymbolId(owner: ClassNode, property: PropertyNode): String =
            "${createClassSymbolId(owner)}${property.name}."

        /**
         * Create unique symbol ID for a method.
         * Format: "com/example/MyClass#myMethod()." for no-param methods
         * Format: "com/example/MyClass#myMethod(int,String)." for parameterized methods
         */
        fun createMethodSymbolId(owner: ClassNode?, method: MethodNode): String {
            val ownerPrefix = owner?.let { createClassSymbolId(it) } ?: ""
            // Simplify symbol ID by ignoring parameters to allow easier matching from call sites
            // usage: Calculator#add().
            // TODO(#703): Support precise method overloading resolution
            //   See: https://github.com/albertocavalcante/gvy/issues/703
            return "${ownerPrefix}${method.name}()."
        }

        /**
         * Create unique symbol ID for a parameter.
         * Format: "com/example/MyClass#myMethod(int,String).param"
         */
        fun createParameterSymbolId(owner: ClassNode?, method: MethodNode, parameter: Parameter): String {
            val methodId = createMethodSymbolId(owner, method)
            return "${methodId}${parameter.name}"
        }

        /**
         * Create symbol ID for import.
         * Format: "import#com/example/MyClass"
         */
        fun createImportSymbolId(importNode: ImportNode): String {
            val className = importNode.className ?: importNode.packageName
            return "import#${className.replace('.', '/')}"
        }

        /**
         * Create symbol ID for static import.
         * Format: "import#com/example/MyClass.staticMethod"
         */
        fun createStaticImportSymbolId(importNode: ImportNode): String {
            val className = importNode.className ?: ""
            val fieldName = importNode.fieldName ?: ""
            return "import#${className.replace('.', '/')}.$fieldName"
        }
    }
}
