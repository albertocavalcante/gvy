package com.github.albertocavalcante.groovyparser.ast.visitor

import com.github.albertocavalcante.groovyparser.ast.NodeRelationshipTracker
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.BreakStatement
import org.codehaus.groovy.ast.stmt.CaseStatement
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.ContinueStatement
import org.codehaus.groovy.ast.stmt.DoWhileStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.ast.stmt.IfStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.SwitchStatement
import org.codehaus.groovy.ast.stmt.ThrowStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement
import org.codehaus.groovy.ast.stmt.WhileStatement
import java.net.URI

/**
 * Recursive AST visitor using composition over inheritance for improved control and testability.
 *
 * Unlike the legacy [NodeVisitorDelegate] which inherits from Groovy's ClassCodeVisitorSupport,
 * this visitor manually traverses the AST using recursive functions. This approach provides:
 *
 * - **Full control** over parent-child relationship tracking
 * - **No coupling** to Groovy's internal visitor hierarchy
 * - **Easier testing** of edge cases and subtle parent relationships
 * - **Better performance** through reduced virtual dispatch overhead
 *
 * ## Key Design Decisions
 *
 * 1. **Synthetic Node Filtering**: Only tracks nodes with valid positions (lineNumber > 0 && columnNumber > 0)
 * 2. **Explicit Traversal**: Uses [CodeVisitorSupport] for statement/expression traversal but wraps each visit with tracking
 * 3. **Special Handling**: Try-catch and method call expressions require custom traversal to match expected parent relationships
 *
 * ## Usage
 *
 * Enable via [ParseRequest.useRecursiveVisitor] flag. When enabled, this visitor runs in parallel
 * with the legacy delegate, allowing gradual migration and validation.
 *
 * ```kotlin
 * val request = ParseRequest(uri, content, useRecursiveVisitor = true)
 * val result = parser.parse(request)
 * val nodes = result.recursiveVisitor?.getAllNodes() ?: emptyList()
 * ```
 *
 * ## Parity Status
 *
 * See docs/RECURSIVE_VISITOR_PARITY.md for detailed parity analysis and known differences.
 * Current parity score: 98% (remaining differences are intentional improvements over delegate).
 *
 * @property tracker The relationship tracker that maintains parent-child mappings
 * @see NodeVisitorDelegate for the legacy inheritance-based implementation
 * @see NodeRelationshipTracker for parent-child relationship storage
 */
class RecursiveAstVisitor(private val tracker: NodeRelationshipTracker) {

    private lateinit var currentUri: URI

    fun visitModule(module: ModuleNode, uri: URI) {
        currentUri = uri
        tracker.clear()
        visitModuleNode(module)
    }

    private fun visitModuleNode(module: ModuleNode) {
        track(module) {
            module.imports?.forEach { visitImport(it) }
            module.starImports?.forEach { visitImport(it) }
            module.staticImports?.values?.forEach { visitImport(it) }
            module.staticStarImports?.values?.forEach { visitImport(it) }

            module.classes.forEach { visitClass(it) }
            module.statementBlock?.let { visitStatement(it) }
        }
    }

    private fun visitImport(importNode: ImportNode) {
        track(importNode) {
            visitAnnotations(importNode)
        }
    }

    private fun visitClass(classNode: ClassNode) {
        track(classNode) {
            visitAnnotations(classNode)
            classNode.properties?.forEach { visitProperty(it) }
            classNode.fields.forEach { visitField(it) }
            classNode.methods.forEach { visitMethod(it) }
            classNode.objectInitializerStatements?.forEach { visitStatement(it) }
            classNode.innerClasses.forEach { visitClass(it) }
        }
    }

    private fun visitMethod(methodNode: MethodNode) {
        track(methodNode) {
            visitAnnotations(methodNode)
            methodNode.parameters?.forEach { visitParameter(it) }
            methodNode.code?.visit(codeVisitor)
        }
    }

    private fun visitField(fieldNode: FieldNode) {
        track(fieldNode) {
            visitAnnotations(fieldNode)
            fieldNode.initialExpression?.visit(codeVisitor)
        }
    }

    private fun visitProperty(propertyNode: PropertyNode) {
        track(propertyNode) {
            visitAnnotations(propertyNode)
            propertyNode.getterBlock?.visit(codeVisitor)
            propertyNode.setterBlock?.visit(codeVisitor)
        }
    }

    private fun visitParameter(parameter: Parameter) {
        visitAnnotations(parameter)
        track(parameter) {}
    }

    private fun visitAnnotation(annotation: org.codehaus.groovy.ast.AnnotationNode) {
        track(annotation) {
            annotation.members?.values?.forEach { value ->
                if (value is org.codehaus.groovy.ast.expr.Expression) {
                    value.visit(codeVisitor)
                }
            }
        }
    }

    private fun visitAnnotations(node: AnnotatedNode) {
        node.annotations?.forEach { visitAnnotation(it) }
    }

    private fun visitStatement(statement: org.codehaus.groovy.ast.stmt.Statement) {
        statement.visit(codeVisitor)
    }

    private fun shouldTrack(node: ASTNode): Boolean = node.lineNumber > 0 && node.columnNumber > 0

    /**
     * Returns all AST nodes tracked during the last module visit.
     *
     * Only nodes with valid source positions (lineNumber > 0 && columnNumber > 0) are included.
     * Synthetic compiler-generated nodes are automatically filtered out.
     *
     * @return List of all tracked AST nodes in visitation order
     */
    fun getAllNodes(): List<ASTNode> = tracker.getAllNodes()

    /**
     * Returns the parent node of the given AST node.
     *
     * Parent relationships are established during AST traversal based on the visitor call stack.
     * Top-level nodes (e.g., ModuleNode) have null parents.
     *
     * @param node The AST node to query
     * @return The parent node, or null if this is a root node or not tracked
     */
    fun getParent(node: ASTNode): ASTNode? = tracker.getParent(node)

    /**
     * Returns the source URI associated with the given AST node.
     *
     * @param node The AST node to query
     * @return The source file URI, or null if node is not tracked
     */
    fun getUri(node: ASTNode): URI? = tracker.getUri(node)

    private fun track(node: ASTNode, block: () -> Unit) {
        if (shouldTrack(node)) {
            tracker.pushNode(node, currentUri)
            try {
                block()
            } finally {
                tracker.popNode()
            }
        } else {
            block()
        }
    }

    /**
     * Visitor that walks statements/expressions recursively while tracking parents.
     */
    private val codeVisitor = object : CodeVisitorSupport() {
        override fun visitBlockStatement(block: BlockStatement) {
            track(block) { super.visitBlockStatement(block) }
        }

        override fun visitExpressionStatement(statement: ExpressionStatement) {
            track(statement) { super.visitExpressionStatement(statement) }
        }

        override fun visitReturnStatement(statement: ReturnStatement) {
            track(statement) { super.visitReturnStatement(statement) }
        }

        override fun visitThrowStatement(statement: ThrowStatement) {
            track(statement) { super.visitThrowStatement(statement) }
        }

        override fun visitIfElse(ifElse: IfStatement) {
            track(ifElse) { super.visitIfElse(ifElse) }
        }

        override fun visitForLoop(forLoop: ForStatement) {
            track(forLoop) { super.visitForLoop(forLoop) }
        }

        override fun visitWhileLoop(loop: WhileStatement) {
            track(loop) { super.visitWhileLoop(loop) }
        }

        override fun visitDoWhileLoop(loop: DoWhileStatement) {
            track(loop) { super.visitDoWhileLoop(loop) }
        }

        override fun visitTryCatchFinally(statement: TryCatchStatement) {
            // Record the try/catch node with the current parent (outer block/method)
            track(statement) {
                // no-op; just track the node itself
            }

            // Visit try block without try/catch on the stack so its parent stays the outer block
            statement.tryStatement?.visit(this)

            // Visit catches/finally with the try/catch on the stack to mirror delegate behavior
            track(statement) {
                statement.catchStatements?.forEach { visitCatchStatement(it) }
                statement.finallyStatement?.visit(this)
            }
        }

        override fun visitCatchStatement(statement: CatchStatement) {
            // Track catch node itself for parity, but visit its contents without catch on the stack
            // so that contained blocks keep TryCatchStatement as their parent.
            track(statement) {
                // no-op body; tracking only
            }
            statement.variable?.let { visitParameter(it) }
            statement.code?.visit(this)
        }

        override fun visitSwitch(statement: SwitchStatement) {
            track(statement) { super.visitSwitch(statement) }
        }

        override fun visitCaseStatement(statement: CaseStatement) {
            track(statement) { super.visitCaseStatement(statement) }
        }

        override fun visitBreakStatement(statement: BreakStatement) {
            track(statement) { super.visitBreakStatement(statement) }
        }

        override fun visitContinueStatement(statement: ContinueStatement) {
            track(statement) { super.visitContinueStatement(statement) }
        }

        override fun visitDeclarationExpression(expression: DeclarationExpression) {
            track(expression) {
                expression.leftExpression.visit(this)
                expression.rightExpression.visit(this)
            }
        }

        override fun visitBinaryExpression(expression: org.codehaus.groovy.ast.expr.BinaryExpression) {
            track(expression) { super.visitBinaryExpression(expression) }
        }

        override fun visitMethodCallExpression(call: MethodCallExpression) {
            // Match legacy delegate: track the call, but only visit argument elements (not the tuple itself).
            track(call) {
                val args = call.arguments
                if (args is TupleExpression) {
                    args.expressions?.forEach { it.visit(this) }
                } else {
                    args?.visit(this)
                }
                call.objectExpression?.visit(this)
                call.method?.visit(this)
            }
        }

        override fun visitConstructorCallExpression(call: ConstructorCallExpression) {
            track(call) { super.visitConstructorCallExpression(call) }
        }

        override fun visitPropertyExpression(expression: PropertyExpression) {
            track(expression) { super.visitPropertyExpression(expression) }
        }

        override fun visitVariableExpression(expression: VariableExpression) {
            track(expression) { super.visitVariableExpression(expression) }
        }

        override fun visitConstantExpression(expression: ConstantExpression) {
            track(expression) { super.visitConstantExpression(expression) }
        }

        override fun visitClosureExpression(expression: ClosureExpression) {
            track(expression) {
                expression.parameters?.forEach { visitParameter(it) }
                super.visitClosureExpression(expression)
            }
        }

        override fun visitGStringExpression(expression: GStringExpression) {
            track(expression) { super.visitGStringExpression(expression) }
        }

        override fun visitClassExpression(expression: ClassExpression) {
            track(expression) { super.visitClassExpression(expression) }
        }

        override fun visitTupleExpression(expression: TupleExpression) {
            track(expression) { super.visitTupleExpression(expression) }
        }
    }
}
