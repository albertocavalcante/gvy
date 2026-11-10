package com.github.albertocavalcante.gvy.diagnostics.codenarc

import com.github.albertocavalcante.groovyparser.api.untrusted.UntrustedAstParser
import com.github.albertocavalcante.groovyparser.api.untrusted.UntrustedParseLimits
import com.github.albertocavalcante.groovyparser.api.untrusted.UntrustedParseResult
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement

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
    /** Largest ruleset file read at all (bytes); well above [UntrustedParseLimits.maxChars] for multi-byte text. */
    const val MAX_FILE_BYTES = 256L * 1024

    private val LIMITS = UntrustedParseLimits()
    private val CLASSPATH_RULESET = Regex("rulesets/[A-Za-z0-9_-]+\\.xml")

    sealed interface Result {
        data class Accepted(val xml: String) : Result
        data class Rejected(val reason: String) : Result
    }

    fun interpret(text: String): Result {
        val parsed = UntrustedAstParser.parse(text, "workspace-ruleset", LIMITS)
        return when (parsed) {
            is UntrustedParseResult.Rejected -> Result.Rejected(parsed.reason)
            is UntrustedParseResult.Parsed -> try {
                Result.Accepted(render(parsed.module))
            } catch (e: Rejection) {
                Result.Rejected(e.message ?: "rejected")
            }
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
        closureOf(call.arguments)?.let { body(it, out) }
        out.append("</ruleset>\n")
        return out.toString()
    }

    private fun body(closure: ClosureExpression, out: StringBuilder) {
        for (statement in statementsOf(closure)) {
            val expression = (statement as? ExpressionStatement)?.expression ?: fail("unsupported statement")
            when {
                expression is VariableExpression -> rule(expression.name, emptyMap(), out)
                expression is MethodCallExpression -> bodyCall(statement, out)
                else -> fail("unsupported expression: ${expression.text}")
            }
        }
    }

    private fun bodyCall(statement: Statement, out: StringBuilder) {
        val call = callOf(statement) ?: fail("qualified or dynamic call")
        val name = call.methodAsString!!
        val arguments = args(call)
        when (name) {
            "description" -> out.append("  <description>").append(xml(stringArg(arguments))).append("</description>\n")
            "ruleset" -> ruleSetReference(call, out)
            else -> rule(name, properties(call), out)
        }
    }

    private fun ruleSetReference(call: MethodCallExpression, out: StringBuilder) {
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
