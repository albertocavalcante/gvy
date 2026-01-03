package com.github.albertocavalcante.groovyparser.ast.visitor

import com.github.albertocavalcante.groovyparser.ast.AstPositionQuery
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.NodeRelationshipTracker
import com.github.albertocavalcante.groovyparser.ast.types.Position
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.PackageNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PostfixExpression
import org.codehaus.groovy.ast.expr.PrefixExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.RangeExpression
import org.codehaus.groovy.ast.expr.SpreadExpression
import org.codehaus.groovy.ast.expr.SpreadMapExpression
import org.codehaus.groovy.ast.expr.TernaryExpression
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
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.ast.stmt.SwitchStatement
import org.codehaus.groovy.ast.stmt.ThrowStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement
import org.codehaus.groovy.ast.stmt.WhileStatement
import org.slf4j.LoggerFactory
import java.net.URI

// ...
class RecursiveAstVisitor(private val tracker: NodeRelationshipTracker) : GroovyAstModel {
    // NOTE: Never write to stdout from LSP code paths (stdio mode): it corrupts the JSON-RPC stream.
    // Keep any temporary instrumentation behind logger debug/trace instead.
    private val logger = LoggerFactory.getLogger(RecursiveAstVisitor::class.java)

    private lateinit var currentUri: URI
    private val positionQuery = AstPositionQuery(tracker)

    override fun getParent(node: ASTNode): ASTNode? = tracker.getParent(node)
    override fun getChildren(node: ASTNode): List<ASTNode> = tracker.getChildren(node)
    override fun getUri(node: ASTNode): URI? = tracker.getUri(node)
    override fun getNodes(uri: URI): List<ASTNode> = tracker.getNodes(uri)
    override fun getAllNodes(): List<ASTNode> = tracker.getAllNodes()
    override fun getAllClassNodes(): List<ClassNode> = tracker.getAllClassNodes()
    override fun contains(ancestor: ASTNode, descendant: ASTNode): Boolean = tracker.contains(ancestor, descendant)
    override fun getNodeAt(uri: URI, position: Position): ASTNode? = positionQuery.getNodeAt(uri, position)
    override fun getNodeAt(uri: URI, line: Int, character: Int): ASTNode? =
        positionQuery.getNodeAt(uri, line, character)

    fun visitModule(module: ModuleNode, uri: URI) {
        currentUri = uri
        tracker.clear()
        if (logger.isDebugEnabled) {
            logger.debug("[DEBUG visitModule] module.classes size: {}", module.classes.size)
            module.classes.forEach { cls ->
                logger.debug(
                    "  - {} @ Line {}:{}, isScript={}",
                    cls.name,
                    cls.lineNumber,
                    cls.columnNumber,
                    cls.isScript,
                )
            }
        }
        tracker.setModuleNode(uri, module)
        visitModuleNode(module)
    }

    private fun visitModuleNode(module: ModuleNode) {
        track(module) {
            module.`package`?.let { visitPackage(it) }
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
            // Track the imported type so position queries on the imported class name work.
            // This enables go-to-definition on import statements (e.g., `import org.junit.Test`).
            val type = importNode.type
            if (type != null) {
                track(type) { /* no-op */ }
            }
        }
    }

    private fun visitPackage(packageNode: PackageNode) {
        track(packageNode) {
            visitAnnotations(packageNode)
        }
    }

    private fun visitClass(classNode: ClassNode) {
        if (logger.isDebugEnabled) {
            logger.debug(
                "[DEBUG visitClass] Visiting {} @ {}:{}, shouldTrack={}, id={}",
                classNode.name,
                classNode.lineNumber,
                classNode.columnNumber,
                shouldTrack(classNode),
                System.identityHashCode(classNode),
            )
        }
        track(classNode) {
            visitAnnotations(classNode)
            // Track type references in the class header so navigation works for `extends` and `implements`.
            classNode.superClass?.let {
                track(it) { /* no-op */ }
            }
            classNode.interfaces?.forEach { iface ->
                track(iface) { /* no-op */ }
            }
            classNode.properties?.forEach { visitProperty(it) }
            classNode.fields.forEach { visitField(it) }
            classNode.declaredConstructors.forEach { visitMethod(it) }
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

    private fun visitAnnotation(annotation: AnnotationNode) {
        track(annotation) {
            annotation.members?.values?.forEach { value ->
                if (value is Expression) {
                    value.visit(codeVisitor)
                }
            }
        }
    }

    private fun visitAnnotations(node: AnnotatedNode) {
        node.annotations?.forEach { visitAnnotation(it) }
    }

    private fun visitStatement(statement: Statement) {
        statement.visit(codeVisitor)
    }

    private fun shouldTrack(node: ASTNode): Boolean = node.lineNumber > 0 && node.columnNumber > 0

    private inline fun track(node: ASTNode, block: () -> Unit) {
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
        private inline fun <T : ASTNode> visitWithTracking(node: T, visitSuper: (T) -> Unit) {
            track(node) { visitSuper(node) }
        }

        override fun visitBlockStatement(block: BlockStatement) {
            visitWithTracking(block) { super.visitBlockStatement(it) }
        }

        override fun visitExpressionStatement(statement: ExpressionStatement) {
            visitWithTracking(statement) { super.visitExpressionStatement(it) }
        }

        override fun visitReturnStatement(statement: ReturnStatement) {
            visitWithTracking(statement) { super.visitReturnStatement(it) }
        }

        override fun visitThrowStatement(statement: ThrowStatement) {
            visitWithTracking(statement) { super.visitThrowStatement(it) }
        }

        override fun visitIfElse(ifElse: IfStatement) {
            visitWithTracking(ifElse) { super.visitIfElse(it) }
        }

        override fun visitForLoop(forLoop: ForStatement) {
            visitWithTracking(forLoop) { super.visitForLoop(it) }
        }

        override fun visitWhileLoop(loop: WhileStatement) {
            visitWithTracking(loop) { super.visitWhileLoop(it) }
        }

        override fun visitDoWhileLoop(loop: DoWhileStatement) {
            visitWithTracking(loop) { super.visitDoWhileLoop(it) }
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
            visitWithTracking(statement) { super.visitSwitch(it) }
        }

        override fun visitCaseStatement(statement: CaseStatement) {
            visitWithTracking(statement) { super.visitCaseStatement(it) }
        }

        override fun visitBreakStatement(statement: BreakStatement) {
            visitWithTracking(statement) { super.visitBreakStatement(it) }
        }

        override fun visitContinueStatement(statement: ContinueStatement) {
            visitWithTracking(statement) { super.visitContinueStatement(it) }
        }

        override fun visitDeclarationExpression(expression: DeclarationExpression) {
            track(expression) {
                expression.leftExpression.visit(this)
                expression.rightExpression.visit(this)
            }
        }

        override fun visitBinaryExpression(expression: BinaryExpression) {
            visitWithTracking(expression) { super.visitBinaryExpression(it) }
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
            track(call) {
                // Track the referenced type so position queries inside `new TypeName(...)` can resolve to the type.
                if (logger.isDebugEnabled) {
                    // NOTE: Stdout is reserved for JSON-RPC in stdio mode; debug output must go through the logger.
                    logger.debug(
                        "[visitConstructorCallExpression] Constructor type: {} @ {}:{}, id={}",
                        call.type.name,
                        call.type.lineNumber,
                        call.type.columnNumber,
                        System.identityHashCode(call.type),
                    )
                }
                track(call.type) { /* no-op */ }
                super.visitConstructorCallExpression(call)
            }
        }

        override fun visitPropertyExpression(expression: PropertyExpression) {
            visitWithTracking(expression) { super.visitPropertyExpression(it) }
        }

        override fun visitPostfixExpression(expression: PostfixExpression) {
            visitWithTracking(expression) { super.visitPostfixExpression(it) }
        }

        override fun visitPrefixExpression(expression: PrefixExpression) {
            visitWithTracking(expression) { super.visitPrefixExpression(it) }
        }

        override fun visitVariableExpression(expression: VariableExpression) {
            visitWithTracking(expression) { super.visitVariableExpression(it) }
        }

        override fun visitConstantExpression(expression: ConstantExpression) {
            visitWithTracking(expression) { super.visitConstantExpression(it) }
        }

        override fun visitClosureExpression(expression: ClosureExpression) {
            visitWithTracking(expression) { expr ->
                expr.parameters?.forEach { param -> visitParameter(param) }
                super.visitClosureExpression(expr)
            }
        }

        override fun visitGStringExpression(expression: GStringExpression) {
            visitWithTracking(expression) { super.visitGStringExpression(it) }
        }

        override fun visitClassExpression(expression: ClassExpression) {
            visitWithTracking(expression) { super.visitClassExpression(it) }
        }

        override fun visitTupleExpression(expression: TupleExpression) {
            visitWithTracking(expression) { super.visitTupleExpression(it) }
        }

        override fun visitListExpression(expression: ListExpression) {
            visitWithTracking(expression) { super.visitListExpression(it) }
        }

        override fun visitMapExpression(expression: MapExpression) {
            visitWithTracking(expression) { super.visitMapExpression(it) }
        }

        override fun visitRangeExpression(expression: RangeExpression) {
            visitWithTracking(expression) { super.visitRangeExpression(it) }
        }

        override fun visitTernaryExpression(expression: TernaryExpression) {
            visitWithTracking(expression) { super.visitTernaryExpression(it) }
        }

        override fun visitSpreadExpression(expression: SpreadExpression) {
            visitWithTracking(expression) { super.visitSpreadExpression(it) }
        }

        override fun visitSpreadMapExpression(expression: SpreadMapExpression) {
            visitWithTracking(expression) { super.visitSpreadMapExpression(it) }
        }
    }
}
