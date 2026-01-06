package com.github.albertocavalcante.refactor.codenarc.groovyism

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.Tree
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyIsoVisitor
import org.openrewrite.java.marker.OmitParentheses
import org.openrewrite.java.tree.J

/**
 * Recipe to move closure as last method parameter outside of parentheses.
 *
 * This aligns with CodeNarc rule: ClosureAsLastMethodParameter
 *
 * e.g. list.each({ println it }) -> list.each { println it }
 *
 * @see <a href="https://codenarc.github.io/CodeNarc/codenarc-rules-groovyism.html#closureaslastmethodparameter">CodeNarc Rule</a>
 */
class MoveClosureAsLastMethodParameter : Recipe() {

    override fun getDisplayName(): String = "Move closure as last method parameter"

    override fun getDescription(): String = "Moves the last closure argument of a method call outside the parentheses."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyIsoVisitor<ExecutionContext>() {
        override fun visitMethodInvocation(method: J.MethodInvocation, ctx: ExecutionContext): J.MethodInvocation {
            var m = super.visitMethodInvocation(method, ctx)
            val args = m.arguments

            if (args.isEmpty()) {
                return m
            }

            // Check if the last argument is a Lambda (Groovy Closure)
            val lastArg = args.last()

            // Note: In OpenRewrite Groovy, closures are parsed as J.Lambda
            if (lastArg !is J.Lambda) {
                return m
            }

            // Check if it already has OmitParentheses marker (either Java or Groovy marker)
            // We'll add the Java marker as recommended
            val hasOmitMarker = lastArg.markers.findFirst(OmitParentheses::class.java).isPresent ||
                lastArg.markers.findFirst(org.openrewrite.groovy.marker.OmitParentheses::class.java).isPresent

            if (hasOmitMarker) {
                return m
            }

            // Apply OmitParentheses marker to the last argument (Lambda)
            // This instructs GroovyPrinter to print it outside parentheses (as a trailing closure)
            // We also ensure it has a space prefix so it prints as " { ... }" instead of "{...}"
            val newLastArg = lastArg.withMarkers(lastArg.markers.addIfAbsent(OmitParentheses(Tree.randomId())))
                .withPrefix(lastArg.prefix.withWhitespace(" "))

            val newArgs = args.toMutableList()
            newArgs[newArgs.size - 1] = newLastArg

            return m.withArguments(newArgs)
        }
    }
}
