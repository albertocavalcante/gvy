// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.db

import com.github.albertocavalcante.gvy.semantics.sourceSimpleName
import com.github.albertocavalcante.gvy.semantics.toSemanticType
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.InnerClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.AttributeExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.MethodPointerExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.Types
import java.net.URI
import java.util.IdentityHashMap

/**
 * AST visitor that extracts semantic information from a compiled ModuleNode.
 * Builds a SemanticDocument containing all symbols and their occurrences.
 *
 * Following Metals/SemanticDB conventions for symbol IDs and occurrence tracking.
 */
class SemanticDocumentBuilder(private val moduleNode: ModuleNode, private val uri: URI) {
    private val symbols = mutableListOf<SymbolInfo>()
    private val occurrences = mutableListOf<SymbolOccurrence>()
    private val unresolved = UnresolvedNames()

    // Assignment targets: false for a plain write (`x = 1`), true for a read-modify-write (`x += 1`, `x++`).
    private val assigned = IdentityHashMap<Expression, Boolean>()

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

        if (DispatchNames.dispatchesDynamically(moduleNode.classes)) unresolved.dispatch(UnresolvedNames.WILDCARD)
        unresolved.annotations(AnnotationMembers.declared(moduleNode))

        return SemanticDocument(uri, symbols, occurrences, unresolved.toSet())
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
                symbol = symbolId.id,
                kind = SymbolKind.IMPORT,
                range = range,
                name = name,
                owner = null,
            ),
        )

        // Add occurrence for the import itself
        occurrences.add(
            SymbolOccurrence(
                symbol = symbolId.id,
                range = range,
                role = OccurrenceRole.IMPORT,
            ),
        )
        importTypeOccurrence(importNode, range)?.let(occurrences::add)
    }

    /**
     * Extract static import symbol
     */
    private fun extractStaticImport(importNode: ImportNode) {
        // A static import names a member, which its uses (`helper()`, or `tgt()` for an alias) never show.
        unresolved.addAll(listOfNotNull(importNode.fieldName, importNode.alias))
        val range = nodeToRange(importNode) ?: return
        val symbolId = createStaticImportSymbolId(importNode)
        val name = importNode.fieldName ?: importNode.alias ?: importNode.className

        symbols.add(
            SymbolInfo(
                symbol = symbolId.id,
                kind = SymbolKind.IMPORT,
                range = range,
                name = name,
                owner = null,
            ),
        )

        occurrences.add(
            SymbolOccurrence(
                symbol = symbolId.id,
                range = range,
                role = OccurrenceRole.IMPORT,
            ),
        )
        importTypeOccurrence(importNode, range)?.let(occurrences::add)
    }

    private fun importTypeOccurrence(importNode: ImportNode, range: Range): SymbolOccurrence? {
        val type = importNode.type ?: return null
        val resolved = TypeHierarchies().compilerResolved(type) ?: return null
        // A shared ClassHelper node carries whichever file last stamped it (#1354): use this import's own range.
        val typeRange = if (ClassHelper.isCachedType(type)) range else nodeToRange(type) ?: range
        return SymbolOccurrence(createClassSymbolId(resolved).id, typeRange, OccurrenceRole.TYPE_REF)
    }

    private fun isNotIndexed(classNode: ClassNode) =
        classNode.isSynthetic || classNode is InnerClassNode && classNode.isAnonymous

    /**
     * Extract class symbol and all its members
     */
    private fun extractClass(classNode: ClassNode) {
        // Synthetic and anonymous classes (#1130) and a trait's helper class get no symbols or occurrences, but
        // their bodies still use names, so the uncertain uses in them are collected.
        val range = nodeToRange(classNode)
        if (range == null || isNotIndexed(classNode)) {
            visitBody(classNode, record = false)
            return
        }
        val symbolId = createClassSymbolId(classNode)

        val kind = when {
            classNode.isInterface -> SymbolKind.INTERFACE
            classNode.isEnum -> SymbolKind.ENUM
            else -> SymbolKind.CLASS
        }

        symbols.add(
            SymbolInfo(
                symbol = symbolId.id,
                kind = kind,
                range = range,
                name = classNode.sourceSimpleName(),
                owner = null,
            ),
        )

        // Add definition occurrence
        occurrences.add(
            SymbolOccurrence(
                symbol = symbolId.id,
                range = range,
                role = OccurrenceRole.DEFINITION,
            ),
        )

        // Shared ClassHelper nodes (every script's groovy.lang.Script) carry a foreign position (#1354): skip them.
        (listOfNotNull(classNode.unresolvedSuperClass) + classNode.unresolvedInterfaces).filterNot {
            ClassHelper.isCachedType(it)
        }.forEach { type ->
            val resolved = TypeHierarchies().compilerResolved(type)
            val referenceRange = nodeToRange(type)
            if (resolved != null && referenceRange != null) {
                occurrences.add(
                    SymbolOccurrence(createClassSymbolId(resolved).id, referenceRange, OccurrenceRole.TYPE_REF),
                )
            }
        }

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

        // `methods` excludes constructors. Some take hidden compiler parameters (enums, inner classes): not indexed.
        if (!IndexedConstructors.addsParameters(classNode)) {
            classNode.declaredConstructors.forEach { constructor ->
                extractMethod(constructor, classNode)
            }
        }

        visitBody(classNode, record = true)
    }

    /** Visit the class body to extract occurrences (method calls, field accesses, etc.) and uncertain uses. */
    private fun visitBody(classNode: ClassNode, record: Boolean) {
        unresolved.inTraitHelper = DispatchNames.isTraitHelper(classNode)
        val operators = OperatorSites(unresolved, assigned)
        classNode.visitContents(operators)
        classNode.objectInitializerStatements.forEach { it.visit(operators) }
        // A class that is not recorded still has its uses collected, into sinks that are thrown away.
        val visitor = if (record) {
            OccurrenceVisitor(classNode, symbols, occurrences, record = true)
        } else {
            OccurrenceVisitor(classNode, mutableListOf(), mutableListOf(), record = false)
        }
        classNode.visitContents(visitor)
        visitor.visitInitializers(classNode.objectInitializerStatements)
        unresolved.inTraitHelper = false
    }

    /**
     * The declared type to record for a member, or null when it is not known from the declaration alone: a type
     * variable (`T get()`) stands for whatever the receiver's type arguments say, which the index does not keep.
     */
    private fun ClassNode.toDeclaredType() = if (isGenericsPlaceHolder) null else toSemanticType()

    /**
     * Extract field symbol
     */
    private fun extractField(fieldNode: FieldNode, owner: ClassNode) {
        val range = nodeToRange(fieldNode) ?: return
        val ownerSymbolId = createClassSymbolId(owner)
        val symbolId = createFieldSymbolId(owner, fieldNode)

        symbols.add(
            SymbolInfo(
                symbol = symbolId.id,
                kind = SymbolKind.FIELD,
                range = range,
                name = fieldNode.name,
                owner = ownerSymbolId.id,
                type = fieldNode.type.toDeclaredType(),
            ),
        )

        occurrences.add(
            SymbolOccurrence(
                symbol = symbolId.id,
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
                symbol = symbolId.id,
                kind = SymbolKind.PROPERTY,
                range = range,
                name = propertyNode.name,
                owner = ownerSymbolId.id,
                type = propertyNode.type.toDeclaredType(),
            ),
        )

        occurrences.add(
            SymbolOccurrence(
                symbol = symbolId.id,
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
        // A constructor is named for its class, as a source declaration spells it (its AST name is `<init>`).
        val name = if (methodNode is ConstructorNode && owner != null) owner.sourceSimpleName() else methodNode.name

        symbols.add(
            SymbolInfo(
                symbol = symbolId.id,
                kind = kind,
                range = range,
                name = name,
                owner = ownerSymbolId?.id,
                type = methodNode.returnType.toDeclaredType().takeIf { kind == SymbolKind.METHOD },
            ),
        )

        occurrences.add(
            SymbolOccurrence(
                symbol = symbolId.id,
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
                symbol = symbolId.id,
                kind = SymbolKind.PARAMETER,
                range = range,
                name = parameter.name,
                owner = ownerSymbolId.id,
            ),
        )

        occurrences.add(
            SymbolOccurrence(
                symbol = symbolId.id,
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
    @Suppress("TooManyFunctions") // Groovy's visitor contract requires one override per relevant syntax node.
    private inner class OccurrenceVisitor(
        private val currentClass: ClassNode,
        private val symbols: MutableList<SymbolInfo>,
        private val occurrences: MutableList<SymbolOccurrence>,
        // False for a class that gets no symbols or occurrences: its uses are then attributed to nothing.
        private val record: Boolean,
    ) : ClassCodeVisitorSupport() {

        private val scopes = ArrayDeque<MutableMap<String, LocalBinding>>()

        private val resolver = CallResolver(moduleNode, currentClass, ::lookup)

        private val constructors = ConstructorResolver()

        // Enclosing closures, innermost last; true when the closure is an argument of a delegate-neutral GDK call.
        private val closures = ArrayDeque<Boolean>()

        private val transparentClosures = HashSet<ClosureExpression>()

        private fun accessOf(target: Expression): Access = when (assigned[target]) {
            null -> Access.READ
            false -> Access.WRITE
            else -> Access.BOTH
        }

        // Inside an opaque closure a name may be answered by the closure's delegate instead.
        private val closureScope: ClosureScope get() = when {
            closures.isEmpty() -> ClosureScope.NONE
            closures.all { it } -> ClosureScope.TRANSPARENT
            else -> ClosureScope.OPAQUE
        }

        // In a static method `this` is the Class object.
        private var staticContext = false

        // Id prefix of the method (or initializer) whose body is being visited; scopes locals.
        private var scopeOwnerId: Symbol = Symbol.ofInitializer(Symbol.ofClass(currentClass.name))

        override fun getSourceUnit(): SourceUnit? = null

        /** Instance initializer blocks (`{ ... }`) are not members, so the class contents do not reach them. */
        fun visitInitializers(statements: List<Statement>) = scoped { statements.forEach { it.visit(this) } }

        private inline fun scoped(body: () -> Unit) {
            scopes.addLast(HashMap())
            try {
                body()
            } finally {
                scopes.removeLast()
            }
        }

        private fun lookup(name: String): LocalBinding? = scopes.asReversed().firstNotNullOfOrNull { it[name] }

        private fun declaresMember(owner: ClassNode, name: String): Boolean =
            owner.getDeclaredField(name) != null || owner.getProperty(name) != null

        /** Declare a local variable or parameter: bind the name and index it as a VARIABLE symbol. */
        private fun declareLocal(name: String, node: ASTNode, type: ClassNode?) {
            val scope = scopes.lastOrNull() ?: return
            val range = nodeToRange(node) ?: return
            val id = createLocalSymbolId(scopeOwnerId, name, range)
            scope[name] = LocalBinding(id.id, TypeSignatures.knownTypeOrNull(type))
            symbols.add(SymbolInfo(id.id, SymbolKind.VARIABLE, range, name, scopeOwnerId.id))
            occurrences.add(SymbolOccurrence(id.id, range, OccurrenceRole.DEFINITION))
        }

        override fun visitConstructorOrMethod(node: MethodNode, isConstructor: Boolean) {
            if (generatedHelperMethod(node, unresolved.inTraitHelper)) return
            val savedOwner = scopeOwnerId
            val savedStatic = staticContext
            scopeOwnerId = createMethodSymbolId(currentClass, node)
            staticContext = node.isStatic
            scoped {
                node.parameters?.forEach { param ->
                    scopes.last()[param.name] = LocalBinding(
                        createParameterSymbolId(currentClass, node, param).id,
                        TypeSignatures.knownTypeOrNull(param.type),
                    )
                }
                // Default values run in the method's scope, after its parameters are bound.
                node.parameters?.filter { it.hasInitialExpression() }?.forEach { it.initialExpression.visit(this) }
                super.visitConstructorOrMethod(node, isConstructor)
            }
            scopeOwnerId = savedOwner
            staticContext = savedStatic
        }

        override fun visitField(node: FieldNode) {
            // A static field's initializer runs in a static context, where `this` is the Class object.
            val saved = staticContext
            staticContext = node.isStatic
            try {
                super.visitField(node)
            } finally {
                staticContext = saved
            }
        }

        override fun visitBlockStatement(block: BlockStatement) = scoped { super.visitBlockStatement(block) }

        override fun visitClosureExpression(expression: ClosureExpression) = scoped {
            closures.addLast(expression in transparentClosures)
            try {
                visitClosureBody(expression)
            } finally {
                closures.removeLast()
            }
        }

        private fun visitClosureBody(expression: ClosureExpression) {
            if (expression.isParameterSpecified) {
                expression.parameters.forEach { declareLocal(it.name, it, it.type) }
            } else {
                // The implicit `it`: bound so uses do not fall through to a field named `it`.
                nodeToRange(expression)?.let { range ->
                    scopes.last()["it"] = LocalBinding(createLocalSymbolId(scopeOwnerId, "it", range).id, null)
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
            // Receiver and arguments may contain the same name; only the member is this occurrence.
            val range = nodeToRange(call.method)
            val outcome = call.methodAsString?.let { resolver.resolve(call, it, closureScope) }
            val symbolId = (outcome as? CallOutcome.Resolved)?.symbolId
            val stored = record && range != null && symbolId != null
            if (range != null &&
                symbolId != null
            ) {
                occurrences.add(SymbolOccurrence(symbolId, range, OccurrenceRole.CALL))
            }
            if (unresolved.isSource(call)) unresolved.call(call, stored)
            if (resolver.isDelegateNeutralCall(call)) {
                (call.arguments as? TupleExpression)?.expressions.orEmpty()
                    .filterIsInstance<ClosureExpression>()
                    .filterNotTo(transparentClosures, GroovyDispatch::reshapesItself)
            }
            super.visitMethodCallExpression(call)
        }

        override fun visitConstructorCallExpression(call: ConstructorCallExpression) {
            // The call can contain other class names in its arguments, including the canonical
            // name of an aliased constructor. Attribute only the compiler's type span.
            val range = nodeToRange(call.type) ?: nodeToRange(call)
            // Only a class the compiler resolved: a bare name would be guessed into this package.
            if (range == null || !resolver.isResolved(call.type)) return super.visitConstructorCallExpression(call)

            val typeName = resolver.resolveTypeName(call.type)
            // The class stays attributed at the type name: references to the class include its `new` expressions.
            occurrences.add(
                SymbolOccurrence(
                    symbol = Symbol.ofClass(typeName).id,
                    range = range,
                    role = OccurrenceRole.CALL,
                ),
            )
            constructorCallOccurrence(call, range)?.let(occurrences::add)

            super.visitConstructorCallExpression(call)
        }

        /**
         * The call of the constructor `new T(args)` invokes, when [ConstructorResolver] can tell. It
         * covers `new T`, from the keyword to the end of the type name, so it never shares a range with the class
         * occurrence at [typeRange] and the innermost occurrence at the type name stays the class's.
         */
        private fun constructorCallOccurrence(call: ConstructorCallExpression, typeRange: Range): SymbolOccurrence? {
            val symbolId = attributedConstructor(call) ?: return null
            val range = constructorCallRange(call, typeRange) ?: return null
            return SymbolOccurrence(symbolId, range, OccurrenceRole.CALL)
        }

        /** The id of the constructor the call invokes; when not known, the class's name is recorded as uncertain. */
        private fun attributedConstructor(call: ConstructorCallExpression): String? =
            when (val outcome = constructors.resolve(call)) {
                is CallOutcome.Resolved -> outcome.symbolId
                is CallOutcome.Unresolved -> {
                    // The class occurrence stands, but the constructor is not known: say so, keyed by the class.
                    if (outcome.reason.isUncertainConstructorUse() && unresolved.isSource(call)) {
                        constructedClass(call)?.let { unresolved.name(it.sourceSimpleName()) }
                    }
                    null
                }
            }

        /**
         * The class whose constructor [call] invokes. `this(...)` and `super(...)` carry the placeholder types
         * `ClassNode.THIS` and `ClassNode.SUPER`, which are not the classes: they mean this class and its superclass.
         * A superclass of `Object` has no source constructor to be unsure about, so it is null.
         */
        private fun constructedClass(call: ConstructorCallExpression): ClassNode? = when {
            call.isThisCall -> currentClass
            call.isSuperCall -> currentClass.unresolvedSuperClass?.takeUnless { it.name == "java.lang.Object" }
            else -> call.type
        }

        /** From the `new` keyword to the end of the type name; null when the type has no position of its own. */
        private fun constructorCallRange(call: ConstructorCallExpression, typeRange: Range): Range? {
            val start = nodeToRange(call) ?: return null
            val starts = start.startLine < typeRange.startLine ||
                start.startLine == typeRange.startLine && start.startColumn < typeRange.startColumn
            if (!starts) return null
            return Range(start.startLine, start.startColumn, typeRange.endLine, typeRange.endColumn)
        }

        /** Uncertain, unless the class is not a source class (classpath) or the call makes a class of its own. */
        private fun CallOutcome.Reason.isUncertainConstructorUse() =
            this != CallOutcome.Reason.NOT_SOURCE_CLASS && this != CallOutcome.Reason.ANONYMOUS_CLASS

        override fun visitClassExpression(expression: ClassExpression) {
            val range = nodeToRange(expression)
            if (range != null && resolver.isResolved(expression.type)) {
                occurrences.add(
                    SymbolOccurrence(
                        Symbol.ofClass(resolver.resolveTypeName(expression.type)).id,
                        range,
                        OccurrenceRole.TYPE_REF,
                    ),
                )
            }
            super.visitClassExpression(expression)
        }

        override fun visitVariableExpression(expression: VariableExpression) {
            val range = nodeToRange(expression)
            if (!expression.isThisExpression && !expression.isSuperExpression) {
                // A parameter or local shadows a field of the same name. Otherwise only a field or property
                // the class declares, and not inside an opaque closure, whose delegate may resolve the name first.
                val local = lookup(expression.name)
                val symbolId = local?.id
                    ?: Symbol.ofField(
                        Symbol.ofClass(resolver.resolveTypeName(currentClass)),
                        expression.name,
                    ).id.takeIf {
                        declaresMember(currentClass, expression.name) &&
                            !closureScope.delegateMayAnswer(expression.name)
                    }
                if (range != null && symbolId != null) {
                    occurrences.add(SymbolOccurrence(symbolId, range, OccurrenceRole.REFERENCE))
                }
                // A parameter or local is certain; a field is attributed only where its occurrence is kept.
                val attributed = local != null || (record && range != null && symbolId != null)
                if (!attributed && unresolved.isSource(expression)) {
                    unresolved.member(expression.name, accessOf(expression))
                }
            }
            super.visitVariableExpression(expression)
        }

        override fun visitBinaryExpression(expression: BinaryExpression) {
            val type = expression.operation.type
            if (Types.ofType(type, Types.ASSIGNMENT_OPERATOR)) {
                assigned[expression.leftExpression] = type != Types.ASSIGN
            }
            if (unresolved.isSource(expression)) recordOperator(type, expression)
            super.visitBinaryExpression(expression)
        }

        private fun recordOperator(type: Int, expression: BinaryExpression) {
            if (type == Types.LEFT_SQUARE_BRACKET) {
                unresolved.index(
                    expression.leftExpression,
                    expression.rightExpression,
                    resolver::receiverType,
                    accessOf(expression),
                )
            } else {
                unresolved.operator(type, expression.leftExpression, expression.rightExpression)
            }
        }

        override fun visitAttributeExpression(expression: AttributeExpression) {
            // `obj.@name` reads the field directly; the index never attributes it.
            unresolved.name(expression.propertyAsString)
            super.visitAttributeExpression(expression)
        }

        override fun visitMethodPointerExpression(expression: MethodPointerExpression) {
            // `obj.&name` names a method without calling it; the index never attributes it.
            unresolved.name((expression.methodName as? ConstantExpression)?.value as? String)
            super.visitMethodPointerExpression(expression)
        }

        override fun visitPropertyExpression(expression: PropertyExpression) {
            val range = nodeToRange(expression.property)
            val symbolId = expression.propertyAsString?.let {
                resolver.resolveProperty(expression.objectExpression, it, staticContext, expression.isSpreadSafe)
            }
            val occurrence = if (range != null && symbolId != null) {
                SymbolOccurrence(symbolId, range, OccurrenceRole.REFERENCE)
            } else {
                null
            }
            occurrence?.let(occurrences::add)
            if (!(record && occurrence != null) && unresolved.isSource(expression)) {
                unresolved.member(expression.propertyAsString, accessOf(expression))
            }
            super.visitPropertyExpression(expression)
        }
    }

    /**
     * Convert an AST node to a half-open, 0-indexed (LSP) [Range].
     *
     * Groovy lines and columns are 1-based and `lastColumnNumber` is the column just past the node's last
     * character, so the LSP exclusive end is `lastColumnNumber - 1`. This is the same conversion as the
     * parser module's `ASTNode.safeRange`. Nodes without positions, or that would be empty, give null.
     */
    private fun nodeToRange(node: ASTNode): Range? =
        groovyToLspRange(node.lineNumber, node.columnNumber, node.lastLineNumber, node.lastColumnNumber)

    companion object {
        /**
         * Groovy's 1-based (line, column, lastLine, lastColumn-exclusive) as an LSP range, or null when
         * the position is unset (<= 0) or the range would be empty or inverted.
         */
        internal fun groovyToLspRange(line: Int, column: Int, lastLine: Int, lastColumn: Int): Range? {
            val valid = line > 0 && column > 0 && lastLine >= line && lastColumn > 0
            val nonEmpty = lastLine > line || lastColumn > column
            return if (valid && nonEmpty) Range(line - 1, column - 1, lastLine - 1, lastColumn - 1) else null
        }

        /**
         * Create unique symbol ID for a class following SemanticDB convention.
         * Format: "com/example/MyClass#"
         */
        fun createClassSymbolId(classNode: ClassNode): Symbol = Symbol.ofClass(classNode.name)

        /**
         * Create unique symbol ID for a field.
         * Format: "com/example/MyClass#myField."
         */
        fun createFieldSymbolId(owner: ClassNode, field: FieldNode): Symbol =
            Symbol.ofField(createClassSymbolId(owner), field.name)

        /**
         * Create unique symbol ID for a property.
         * Format: "com/example/MyClass#myProperty."
         */
        fun createPropertySymbolId(owner: ClassNode, property: PropertyNode): Symbol =
            Symbol.ofField(createClassSymbolId(owner), property.name)

        /**
         * Create unique symbol ID for a method.
         *
         * Format: "com/example/MyClass#myMethod()." for no-param methods and
         * "com/example/MyClass#myMethod(String,int)." otherwise. Parameter types are erased and
         * written as simple names, so overloads get distinct ids while the id stays the same
         * whether or not the compiler has qualified the type names yet.
         */
        fun createMethodSymbolId(owner: ClassNode?, method: MethodNode): Symbol = Symbol.ofMethod(
            owner?.let(::createClassSymbolId),
            method.name,
            TypeSignatures.parameterList(method.parameters),
        )

        /**
         * Create unique symbol ID for a parameter.
         * Format: "com/example/MyClass#myMethod(int,String).(param)"
         */
        fun createParameterSymbolId(owner: ClassNode?, method: MethodNode, parameter: Parameter): Symbol =
            Symbol.ofParameter(createMethodSymbolId(owner, method), parameter.name)

        /**
         * Create unique symbol ID for a local variable or closure/loop/catch parameter.
         * Format: "com/example/MyClass#myMethod(int).name@line:column", where line and column
         * are the 0-indexed start of the declaration. Unique per declaration, never equal to a
         * field id, and the same for the same declaration in identical content.
         */
        fun createLocalSymbolId(scope: Symbol, name: String, declaration: Range): Symbol =
            Symbol.ofLocal(scope, name, declaration.startLine, declaration.startColumn)

        /**
         * Create symbol ID for import.
         * Format: "import#com/example/MyClass"
         */
        fun createImportSymbolId(importNode: ImportNode): Symbol {
            val className = importNode.className ?: importNode.packageName
            return Symbol.ofImport(className)
        }

        /**
         * Create symbol ID for static import.
         * Format: "import#com/example/MyClass.staticMethod"
         */
        fun createStaticImportSymbolId(importNode: ImportNode): Symbol {
            val className = importNode.className ?: ""
            val fieldName = importNode.fieldName ?: ""
            return Symbol.ofStaticImport(className, fieldName)
        }
    }
}
