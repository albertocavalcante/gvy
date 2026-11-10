package com.github.albertocavalcante.gvy.gdsl

import com.github.albertocavalcante.gvy.gdsl.model.GdslParseResult
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.IfStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.Statement

/**
 * Reads the contributions of a GDSL script **without running it**.
 *
 * GDSL files are untrusted: they come from repositories, from a Jenkins controller's
 * `/pipeline-syntax/gdsl` endpoint or from plugin dumps. Real GDSL is declarative data
 * (`context(...)`, `contributor(ctx) { method(...); property(...) }`), so instead of evaluating the
 * script in a `GroovyShell`, this walks its AST and interprets only an allow-listed subset:
 *
 * - calls: `context`, `contributor`, `method`, `property`, `parameter`, `enclosingCall`,
 *   `scriptScope`, `closureScope`, `classScope`, `annotatedScope`, `delegatesTo` (ignored), `findClass`;
 * - values: string/number/boolean/null literals, lists, maps, class literals (as type names) and
 *   locals declared with `def x = <value>`;
 * - statements: expression statements, `def` declarations and `if (<value>) { ... } else { ... }`.
 *
 * Anything else (other calls, `new`, property access, loops, class declarations, closures outside
 * `contributor`, string interpolation with expressions) is skipped with a warning; nothing in the
 * script is ever executed. Semantics match the former `GdslScript` base class and [GdslContributor], which the old
 * shell-based executor used, so results are identical for declarative GDSL.
 */
internal class GdslInterpreter(private val limits: Limits = Limits()) {

    /** Bounds on the walk; parsing has its own limits (see [GdslExecutor]). */
    data class Limits(val maxNodes: Int = DEFAULT_MAX_NODES, val maxDepth: Int = DEFAULT_MAX_DEPTH)

    /** Raised to skip one statement; the message becomes a warning. */
    private class Unsupported(message: String, node: ASTNode) :
        RuntimeException("$message at line ${node.lineNumber}, column ${node.columnNumber}")

    /** Raised when a walk limit is exceeded; aborts the whole interpretation. */
    private class LimitExceeded(message: String) : RuntimeException(message)

    private val contributors = mutableListOf<GdslContributor>()
    private val warnings = mutableListOf<String>()
    private var nodes = 0
    private var depth = 0

    fun interpret(module: ModuleNode): GdslParseResult {
        if (module.classes.any { !it.isScript }) {
            warnings += "Class declarations are not supported and were skipped"
        }
        return try {
            statements(module.statementBlock, Scope(parent = null, contributor = null))
            GdslParseResult(
                methods = contributors.flatMap { it.methods },
                properties = contributors.flatMap { it.properties },
                warnings = warnings,
            )
        } catch (e: LimitExceeded) {
            GdslParseResult.error(e.message ?: "GDSL script exceeds the interpreter limits")
        }
    }

    /** Lexical scope: `def` locals, and the contributor whose closure is being read (if any). */
    private class Scope(val parent: Scope?, val contributor: GdslContributor?) {
        private val locals = mutableMapOf<String, Any?>()

        fun lookup(name: String): Any? = when {
            locals.containsKey(name) -> locals[name]
            parent != null -> parent.lookup(name)
            else -> throw NoSuchElementException(name)
        }

        fun has(name: String): Boolean = locals.containsKey(name) || parent?.has(name) == true

        fun define(name: String, value: Any?) {
            locals[name] = value
        }
    }

    private fun statements(statement: Statement, scope: Scope) {
        enter(statement) {
            when (statement) {
                is BlockStatement -> statement.statements.forEach { child ->
                    guarded { statements(child, scope) }
                }
                is ExpressionStatement -> evaluate(statement.expression, scope)
                is ReturnStatement -> evaluate(statement.expression, scope)
                is IfStatement -> {
                    val branch = if (truthy(evaluate(statement.booleanExpression.expression, scope))) {
                        statement.ifBlock
                    } else {
                        statement.elseBlock
                    }
                    statements(branch, scope)
                }
                is EmptyStatement -> Unit
                else -> throw Unsupported("Unsupported statement ${statement.javaClass.simpleName}", statement)
            }
        }
    }

    /** Runs [block] for one statement; an unsupported construct skips just that statement. */
    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Unsupported) {
            warnings += "Skipped: ${e.message}"
        }
    }

    private fun evaluate(expression: Expression, scope: Scope): Any? = enter(expression) {
        when (expression) {
            is ConstantExpression -> expression.value
            is ListExpression -> expression.expressions.map { evaluate(it, scope) }
            is MapExpression -> expression.mapEntryExpressions.associateTo(LinkedHashMap()) { entry ->
                evaluate(entry.keyExpression, scope) to evaluate(entry.valueExpression, scope)
            }
            is ClassExpression -> GdslTypeNames.resolve(expression.type.name)
            is VariableExpression -> variable(expression, scope)
            is DeclarationExpression -> declare(expression, scope)
            is MethodCallExpression -> call(expression, scope)
            else -> throw Unsupported("Unsupported expression ${expression.javaClass.simpleName}", expression)
        }
    }

    /**
     * A `def` local, or a bare capitalised name such as `Closure` in `params: [body: Closure]`. Before
     * semantic analysis Groovy parses a class reference as a variable; Groovy would resolve it to the
     * class, so it is read as that class's name (see [GdslTypeNames]).
     */
    private fun variable(expression: VariableExpression, scope: Scope): Any? {
        val name = expression.name
        return when {
            scope.has(name) -> scope.lookup(name)
            name.first().isUpperCase() -> GdslTypeNames.resolve(name)
            else -> throw Unsupported("Unknown variable '$name'", expression)
        }
    }

    /**
     * `def x = <value>`. Locals may only hold scalars or a context: a list or map local could be shared
     * by reference (`def x1 = [x0, x0]` …), which makes values, and their string forms, grow
     * exponentially in the number of statements.
     */
    private fun declare(expression: DeclarationExpression, scope: Scope): Any? {
        val target = expression.leftExpression as? VariableExpression
            ?: throw Unsupported("Unsupported declaration", expression)
        val value = evaluate(expression.rightExpression, scope)
        if (!isScalar(value) && value !is GdslContext) {
            throw Unsupported("Locals may only hold strings, numbers, booleans or contexts", expression)
        }
        scope.define(target.name, value)
        return value
    }

    private fun isScalar(value: Any?): Boolean = value == null || value is String || value is Number || value is Boolean

    private fun call(expression: MethodCallExpression, scope: Scope): Any? {
        val name = plainCallName(expression)
        val arguments = arguments(expression.arguments, scope)
        return when (name) {
            in SCOPE_TYPES -> arguments.named + mapOf("type" to SCOPE_TYPES.getValue(name))
            "context" -> context(arguments, expression)
            "contributor" -> contributor(arguments, scope, expression)
            "delegatesTo" -> null
            "findClass" -> arguments.singleString(expression)
            in CONTRIBUTOR_CALLS -> contributorCall(name, arguments, scope, expression)
            else -> throw Unsupported("Unsupported call '$name'", expression)
        }
    }

    /** The name of a call on the implicit receiver with a literal name; anything else is unsupported. */
    private fun plainCallName(expression: MethodCallExpression): String {
        val name = expression.methodAsString.takeIf { expression.isImplicitThis }
        return name ?: throw Unsupported("Only plain calls such as method(...) are supported", expression)
    }

    /** Calls that only exist on the contributor closure's delegate ([GdslContributor]). */
    private fun contributorCall(
        name: String,
        arguments: Arguments,
        scope: Scope,
        expression: MethodCallExpression,
    ): Any? {
        val contributor = scope.contributor
            ?: throw Unsupported("'$name' is only supported inside contributor { }", expression)
        if (name != "enclosingCall") requireScalarFields(arguments, expression)
        return when (name) {
            "method" -> contributor.method(arguments.named)
            "property" -> contributor.property(arguments.named)
            "parameter" -> contributor.parameter(arguments.named)
            else -> contributor.enclosingCall(arguments.singleString(expression))
        }
    }

    /** `name`, `type` and `doc` are stored as strings; a list or map there would be stringified whole. */
    private fun requireScalarFields(arguments: Arguments, expression: MethodCallExpression) {
        val nonScalar = SCALAR_FIELDS.firstOrNull { key -> key in arguments.named && !isScalar(arguments.named[key]) }
        if (nonScalar != null) throw Unsupported("'$nonScalar' must be a string", expression)
    }

    /**
     * `context(...)`. Values may only be scalars, a flat list of scalars (`filetypes: ["gson"]`) or one
     * level of scope map holding scalars (`scope: closureScope(isArg: true)`). A context holding contexts
     * could share them by reference (`context(a: c0, b: c0)` …) and make hashing or printing it
     * exponential in the number of statements.
     */
    private fun context(arguments: Arguments, expression: MethodCallExpression): GdslContext {
        val nested = arguments.named.entries.firstOrNull { (_, value) ->
            !isScalar(value) && !isScalarCollection(value)
        }
        if (nested != null) {
            val message = "Context values may only be strings, numbers, booleans, lists of those " +
                "or scopes ('${nested.key}')"
            throw Unsupported(message, expression)
        }
        return GdslContext(arguments.named.nonNullValues())
    }

    private fun isScalarCollection(value: Any?): Boolean = when (value) {
        is List<*> -> value.all(::isScalar)
        is Map<*, *> -> value.all { (key, item) -> key is String && isScalar(item) }
        else -> false
    }

    private fun Map<String, Any?>.nonNullValues(): Map<String, Any> = buildMap {
        this@nonNullValues.forEach { (key, value) -> if (value != null) put(key, value) }
    }

    /** `contributor(contexts) { ... }`: reads the closure once per context, as the old `GdslScript` did. */
    private fun contributor(arguments: Arguments, scope: Scope, expression: MethodCallExpression) {
        val body = arguments.closure ?: throw Unsupported("contributor without a closure", expression)
        val contexts = when (val target = arguments.positional.firstOrNull()) {
            is List<*> -> target.filterIsInstance<GdslContext>()
            is GdslContext -> listOf(target)
            null -> listOf(GdslContext(emptyMap()))
            else -> emptyList()
        }
        contexts.forEach { context ->
            val contributor = GdslContributor(context)
            statements(body.code, Scope(parent = scope, contributor = contributor))
            contributors += contributor
        }
    }

    private class Arguments(val named: Map<String, Any?>, val positional: List<Any?>, val closure: ClosureExpression?)

    private fun arguments(expression: Expression, scope: Scope): Arguments {
        val items = (expression as? TupleExpression)?.expressions ?: listOf(expression)
        val named = LinkedHashMap<String, Any?>()
        val positional = mutableListOf<Any?>()
        var closure: ClosureExpression? = null
        for (item in items) {
            when {
                item is NamedArgumentListExpression ->
                    (evaluate(item, scope) as Map<*, *>).forEach { (key, value) -> named[key.toString()] = value }
                item is ClosureExpression -> closure = item
                else -> positional += evaluate(item, scope)
            }
        }
        return Arguments(named, positional, closure)
    }

    private fun Arguments.singleString(node: ASTNode): String =
        positional.singleOrNull() as? String ?: throw Unsupported("Expected one string argument", node)

    /** Groovy truth for the values this interpreter can produce. */
    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is Number -> value.toDouble() != 0.0
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }

    private inline fun <T> enter(node: ASTNode, block: () -> T): T {
        if (++nodes > limits.maxNodes) {
            throw LimitExceeded("GDSL script has more than ${limits.maxNodes} AST nodes (line ${node.lineNumber})")
        }
        if (++depth > limits.maxDepth) {
            throw LimitExceeded("GDSL script nests deeper than ${limits.maxDepth} levels (line ${node.lineNumber})")
        }
        try {
            return block()
        } finally {
            depth--
        }
    }

    private companion object {
        val SCOPE_TYPES = mapOf(
            "scriptScope" to "script",
            "closureScope" to "closure",
            "classScope" to "class",
            "annotatedScope" to "annotated",
        )
        val CONTRIBUTOR_CALLS = setOf("method", "property", "parameter", "enclosingCall")
        val SCALAR_FIELDS = listOf("name", "type", "doc")
        const val DEFAULT_MAX_NODES = 500_000
        const val DEFAULT_MAX_DEPTH = 256
    }
}
