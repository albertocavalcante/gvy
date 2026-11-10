package com.github.albertocavalcante.gvy.diagnostics.codenarc

import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.SourceUnit

/** Thrown for any construct outside the declarative subset; carries the reason shown to the user. */
internal class Rejection(message: String) : RuntimeException(message, null, false, false)

/**
 * Turns a Groovy-DSL CodeNarc ruleset that came from the workspace into an equivalent **XML** ruleset without
 * ever evaluating the Groovy: the text is parsed to an AST (no compiler phase operation runs, so no transform
 * executes) and only a small declarative vocabulary is read out of it. CodeNarc then loads the generated XML.
 *
 * Accepted: one top-level `ruleset { ... }` containing `description '...'`, bare rule names, rules with literal
 * properties (`Rule { prop = 1 }`, `Rule(prop: 1)`) and `ruleset('rulesets/<name>.xml') { include/exclude 'Rule';
 * Rule { prop = 1 } }`. Ruleset references are limited to CodeNarc's bundled classpath XML files under `rulesets/`.
 * Anything else (calls, constructors, property access, expression GStrings, `evaluate`, `rule(...)`,
 * filesystem or `file:` references, imports, extra classes or methods) rejects the whole file.
 */
internal object WorkspaceRulesetInterpreter {
    private val logger = KotlinLogging.logger {}

    const val MAX_BYTES = 64 * 1024
    private const val MAX_STATEMENTS = 2_000
    private const val MAX_DEPTH = 8
    private val CLASSPATH_RULESET = Regex("rulesets/[A-Za-z0-9_-]+\\.xml")

    sealed interface Result {
        data class Accepted(val xml: String) : Result
        data class Rejected(val reason: String) : Result
    }

    fun interpret(text: String): Result {
        if (text.length > MAX_BYTES) return Result.Rejected("larger than $MAX_BYTES characters")
        // Parsing deeply nested input is expensive, so bound the nesting before the parser sees it.
        if (maxBraceDepth(text) > MAX_DEPTH) return Result.Rejected("nested deeper than $MAX_DEPTH levels")
        return try {
            Result.Accepted(render(parse(text)))
        } catch (e: Rejection) {
            Result.Rejected(e.message ?: "rejected")
        } catch (e: CompilationFailedException) {
            Result.Rejected("not parseable: ${e.message?.lineSequence()?.firstOrNull()}")
        } catch (e: StackOverflowError) {
            logger.debug(e) { "workspace ruleset too deeply nested" }
            Result.Rejected("too deeply nested")
        }
    }

    /** Deepest `{`/`(`/`[` nesting in [text]; a cheap over-approximation (strings are not skipped). */
    private fun maxBraceDepth(text: String): Int {
        var depth = 0
        var max = 0
        for (c in text) {
            when (c) {
                '{', '(', '[' -> max = maxOf(max, ++depth)
                '}', ')', ']' -> depth = maxOf(0, depth - 1)
            }
        }
        return max
    }

    private fun parse(text: String): ModuleNode {
        val config = CompilerConfiguration()
        return SourceUnit("workspace-ruleset", text, config, null, ErrorCollector(config)).run {
            parse()
            completePhase()
            nextPhase()
            convert()
            ast
        }
    }

    private class Counter(var statements: Int = 0) {
        fun tick() {
            if (++statements > MAX_STATEMENTS) fail("more than $MAX_STATEMENTS statements")
        }
    }

    private fun render(module: ModuleNode): String {
        val extra = module.imports.isNotEmpty() || module.starImports.isNotEmpty() ||
            module.staticImports.isNotEmpty() || module.staticStarImports.isNotEmpty() ||
            module.packageName != null || module.methods.isNotEmpty() || module.classes.size > 1
        if (extra) fail("imports, package, classes or methods are not allowed")
        val top = (module.statementBlock as BlockStatement).statements.singleOrNull()
            ?: fail("expected a single top-level ruleset { ... }")
        val call = callOf(top)
        if (call == null || call.methodAsString != "ruleset") fail("the top-level statement must be ruleset { ... }")

        val out = StringBuilder()
        out.append("<?xml version=\"1.0\"?>\n<ruleset xmlns=\"http://codenarc.org/ruleset/1.0\">\n")
        val counter = Counter()
        closureOf(call.arguments)?.let { body(it, out, counter) }
        out.append("</ruleset>\n")
        return out.toString()
    }

    private fun body(closure: ClosureExpression, out: StringBuilder, counter: Counter) {
        for (statement in statementsOf(closure)) {
            counter.tick()
            val expression = (statement as? ExpressionStatement)?.expression ?: fail("unsupported statement")
            when {
                expression is VariableExpression -> rule(expression.name, emptyMap(), out)
                expression is MethodCallExpression -> bodyCall(statement, out, counter)
                else -> fail("unsupported expression: ${expression.text}")
            }
        }
    }

    private fun bodyCall(statement: Statement, out: StringBuilder, counter: Counter) {
        val call = callOf(statement) ?: fail("qualified or dynamic call")
        val name = call.methodAsString!!
        val arguments = args(call)
        when (name) {
            "description" -> out.append("  <description>").append(xml(stringArg(arguments))).append("</description>\n")
            "ruleset" -> ruleSetReference(call, out, counter)
            else -> rule(name, properties(call), out)
        }
    }

    private fun ruleSetReference(call: MethodCallExpression, out: StringBuilder, counter: Counter) {
        val path = stringArg(args(call).filter { it !is ClosureExpression })
        if (!CLASSPATH_RULESET.matches(path)) fail("only classpath rulesets/*.xml references are allowed: $path")
        out.append("  <ruleset-ref path=\"").append(xml(path)).append("\"")
        val closure = closureOf(call.arguments)
        if (closure == null) {
            out.append("/>\n")
            return
        }
        out.append(">\n")
        for (statement in statementsOf(closure)) {
            counter.tick()
            referenceEntry(statement, out)
        }
        out.append("  </ruleset-ref>\n")
    }

    private fun referenceEntry(statement: Statement, out: StringBuilder) {
        val call = callOf(statement) ?: fail("unsupported statement inside ruleset(...)")
        val name = call.methodAsString!!
        when (name) {
            "include", "exclude" -> args(call).also { if (it.isEmpty()) fail("$name needs rule names") }.forEach {
                val rule = (it as? ConstantExpression)?.value as? String ?: fail("$name takes string literals")
                if (!NAME.matches(rule)) fail("bad rule name: $rule")
                out.append("    <$name name=\"").append(xml(rule)).append("\"/>\n")
            }
            else -> {
                requireRule(name)
                out.append("    <rule-config name=\"").append(xml(name)).append("\">\n")
                properties(call).forEach { (key, value) -> property(key, value, "      ", out) }
                out.append("    </rule-config>\n")
            }
        }
    }
}
