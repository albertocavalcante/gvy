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
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.ForStatement
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
     * Visitor for extracting occurrences (references, calls, etc.).
     *
     * Keeps a stack of lexical scopes so a use of `x` binds to the innermost parameter or local
     * named `x` (and gets that declaration's unique id) before falling back to a field of the class.
     * The scopes also carry the declared or inferred type used to attribute calls to a receiver.
     */
    private inner class OccurrenceVisitor(private val currentClass: ClassNode) : ClassCodeVisitorSupport() {

        private val scopes = ArrayDeque<MutableMap<String, LocalBinding>>()

        private val resolver = CallResolver(moduleNode, currentClass, ::lookup)

        // Id prefix of the method (or initializer) whose body is being visited; scopes locals.
        private var scopeOwnerId: String = "${createClassSymbolId(currentClass)}{init}."

        override fun getSourceUnit(): SourceUnit? = null

        private inline fun scoped(body: () -> Unit) {
            scopes.addLast(HashMap())
            try {
                body()
            } finally {
                scopes.removeLast()
            }
        }

        private fun lookup(name: String): LocalBinding? = scopes.reversed().firstNotNullOfOrNull { it[name] }

        /** Declare a local variable or parameter: bind the name and index it as a VARIABLE symbol. */
        private fun declareLocal(name: String, node: Any, type: ClassNode?) {
            val scope = scopes.lastOrNull() ?: return
            val range = nodeToRange(node) ?: return
            val id = createLocalSymbolId(scopeOwnerId, name, range)
            scope[name] = LocalBinding(id, TypeSignatures.knownTypeOrNull(type))
            symbols.add(SymbolInfo(id, SymbolKind.VARIABLE, range, name, scopeOwnerId))
            occurrences.add(SymbolOccurrence(id, range, OccurrenceRole.DEFINITION))
        }

        override fun visitConstructorOrMethod(node: MethodNode, isConstructor: Boolean) {
            val savedOwner = scopeOwnerId
            scopeOwnerId = createMethodSymbolId(currentClass, node)
            scoped {
                node.parameters?.forEach { param ->
                    scopes.last()[param.name] = LocalBinding(
                        createParameterSymbolId(currentClass, node, param),
                        TypeSignatures.knownTypeOrNull(param.type),
                    )
                }
                super.visitConstructorOrMethod(node, isConstructor)
            }
            scopeOwnerId = savedOwner
        }

        override fun visitBlockStatement(block: BlockStatement) = scoped { super.visitBlockStatement(block) }

        override fun visitClosureExpression(expression: ClosureExpression) = scoped {
            if (expression.isParameterSpecified) {
                expression.parameters.forEach { declareLocal(it.name, it, it.type) }
            } else {
                // The implicit `it`: bound so uses do not fall through to a field named `it`.
                nodeToRange(expression)?.let { range ->
                    scopes.last()["it"] = LocalBinding(createLocalSymbolId(scopeOwnerId, "it", range), null)
                }
            }
            super.visitClosureExpression(expression)
        }

        override fun visitForLoop(forLoop: ForStatement) = scoped {
            if (forLoop.variable != ForStatement.FOR_LOOP_DUMMY) {
                declareLocal(forLoop.variable.name, forLoop.variable, forLoop.variable.type)
            }
            super.visitForLoop(forLoop)
        }

        override fun visitCatchStatement(statement: CatchStatement) = scoped {
            declareLocal(statement.variable.name, statement.variable, statement.variable.type)
            super.visitCatchStatement(statement)
        }

        override fun visitDeclarationExpression(expression: DeclarationExpression) {
            // The initializer is evaluated before the new name comes into scope.
            expression.rightExpression.visit(this)
            when (val left = expression.leftExpression) {
                is VariableExpression ->
                    declareLocal(
                        left.name,
                        left,
                        TypeSignatures.knownTypeOrNull(left.type) ?: resolver.inferType(expression),
                    )
                is TupleExpression ->
                    left.expressions.filterIsInstance<VariableExpression>()
                        .forEach { declareLocal(it.name, it, it.type) }
                else -> left.visit(this)
            }
        }

        override fun visitMethodCallExpression(call: MethodCallExpression) {
            val range = nodeToRange(call)
            val symbolId = call.methodAsString?.let { resolver.resolveCall(call, it) }
            if (range != null && symbolId != null) {
                occurrences.add(SymbolOccurrence(symbolId, range, OccurrenceRole.CALL))
            }
            super.visitMethodCallExpression(call)
        }

        override fun visitConstructorCallExpression(call: ConstructorCallExpression) {
            val range = nodeToRange(call) ?: return super.visitConstructorCallExpression(call)

            // Create symbol ID for constructor call
            val typeName = resolver.resolveTypeName(call.type)
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
            val range = nodeToRange(expression)
            if (range != null && !expression.isThisExpression && !expression.isSuperExpression) {
                // A parameter or local shadows a field of the same name.
                val symbolId = lookup(expression.name)?.id
                    ?: "${resolver.resolveTypeName(currentClass)}#${expression.name}."
                occurrences.add(SymbolOccurrence(symbolId, range, OccurrenceRole.REFERENCE))
            }
            super.visitVariableExpression(expression)
        }

        override fun visitPropertyExpression(expression: PropertyExpression) {
            val range = nodeToRange(expression)
            val receiver = resolver.receiverType(expression.objectExpression)
            val propertyName = expression.propertyAsString
            // Without a known receiver type the property is not attributed to any class.
            if (range != null && receiver != null && propertyName != null) {
                occurrences.add(
                    SymbolOccurrence(
                        "${resolver.resolveTypeName(receiver)}#$propertyName.",
                        range,
                        OccurrenceRole.REFERENCE,
                    ),
                )
            }
            super.visitPropertyExpression(expression)
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
         *
         * Format: "com/example/MyClass#myMethod()." for no-param methods and
         * "com/example/MyClass#myMethod(String,int)." otherwise. Parameter types are erased and
         * written as simple names, so overloads get distinct ids while the id stays the same
         * whether or not the compiler has qualified the type names yet.
         */
        fun createMethodSymbolId(owner: ClassNode?, method: MethodNode): String {
            val ownerPrefix = owner?.let { createClassSymbolId(it) } ?: ""
            return "${ownerPrefix}${method.name}(${TypeSignatures.parameterList(method.parameters)})."
        }

        /**
         * Create unique symbol ID for a parameter.
         * Format: "com/example/MyClass#myMethod(int,String).(param)"
         */
        fun createParameterSymbolId(owner: ClassNode?, method: MethodNode, parameter: Parameter): String =
            "${createMethodSymbolId(owner, method)}(${parameter.name})"

        /**
         * Create unique symbol ID for a local variable or closure/loop/catch parameter.
         * Format: "com/example/MyClass#myMethod(int).name@line:column", where line and column
         * are the 0-indexed start of the declaration. Unique per declaration, never equal to a
         * field id, and the same for the same declaration in identical content.
         */
        fun createLocalSymbolId(scopeOwnerId: String, name: String, declaration: Range): String =
            "$scopeOwnerId$name@${declaration.startLine}:${declaration.startColumn}"

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
