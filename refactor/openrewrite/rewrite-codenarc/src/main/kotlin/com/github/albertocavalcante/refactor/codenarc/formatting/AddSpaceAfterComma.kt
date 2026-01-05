package com.github.albertocavalcante.refactor.codenarc.formatting

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyIsoVisitor
import org.openrewrite.groovy.tree.G
import org.openrewrite.java.tree.Expression
import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.JContainer
import org.openrewrite.java.tree.JRightPadded
import org.openrewrite.java.tree.Statement

/**
 * Recipe to add a space after commas in Groovy code.
 *
 * This aligns with CodeNarc rule: SpaceAfterComma
 *
 * The recipe ensures that commas in method arguments, lists, maps, and variable
 * declarations are followed by a single space for readability.
 *
 * @see <a href="https://codenarc.org/codenarc-rules-formatting.html#spaceaftercomma">CodeNarc Rule</a>
 */
class AddSpaceAfterComma : Recipe() {

    override fun getDisplayName(): String = "Add space after comma"

    override fun getDescription(): String = "Ensures that commas are followed by a space in method arguments, " +
        "list literals, map literals, and variable declarations."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> {
        return object : GroovyIsoVisitor<ExecutionContext>() {

            override fun visitMethodInvocation(method: J.MethodInvocation, ctx: ExecutionContext): J.MethodInvocation {
                var m = super.visitMethodInvocation(method, ctx)

                val args = m.padding.arguments
                val updatedArgs = ensureSpaceAfterCommasExpr(args)
                if (updatedArgs !== args) {
                    m = m.padding.withArguments(updatedArgs)
                }

                return m
            }

            override fun visitMethodDeclaration(
                method: J.MethodDeclaration,
                ctx: ExecutionContext,
            ): J.MethodDeclaration {
                var m = super.visitMethodDeclaration(method, ctx)

                val params = m.padding.parameters
                val updatedParams = ensureSpaceAfterCommasStatement(params)
                if (updatedParams !== params) {
                    m = m.padding.withParameters(updatedParams)
                }

                return m
            }

            override fun visitVariableDeclarations(
                multiVariable: J.VariableDeclarations,
                ctx: ExecutionContext,
            ): J.VariableDeclarations {
                var v = super.visitVariableDeclarations(multiVariable, ctx)

                val vars = v.padding.variables
                val updatedVars = ensureSpaceAfterCommasNamedVar(vars)
                if (updatedVars !== vars) {
                    v = v.padding.withVariables(updatedVars)
                }

                return v
            }

            override fun visitListLiteral(listLiteral: G.ListLiteral, ctx: ExecutionContext): G.ListLiteral {
                var l = super.visitListLiteral(listLiteral, ctx)

                val elements = l.padding.elements
                val updatedElements = ensureSpaceAfterCommasExpr(elements)
                if (updatedElements !== elements) {
                    l = l.padding.withElements(updatedElements)
                }

                return l
            }

            override fun visitMapLiteral(mapLiteral: G.MapLiteral, ctx: ExecutionContext): G.MapLiteral {
                var m = super.visitMapLiteral(mapLiteral, ctx)

                val elements = m.padding.elements
                val updatedElements = ensureSpaceAfterCommasMapEntry(elements)
                if (updatedElements !== elements) {
                    m = m.padding.withElements(updatedElements)
                }

                return m
            }

            /**
             * Ensure space after commas in a JContainer of Expression elements.
             * Space is added to the NEXT element's prefix, not the current element's after.
             */
            private fun ensureSpaceAfterCommasExpr(container: JContainer<Expression>): JContainer<Expression> {
                val elements = container.padding.elements
                var changed = false
                val updated = elements.mapIndexed { index, elem ->
                    // For all elements after the first, ensure there's a space in prefix
                    if (index > 0) {
                        val prefix = elem.element.prefix
                        if (prefix.whitespace.isEmpty()) {
                            changed = true
                            elem.withElement(elem.element.withPrefix(prefix.withWhitespace(" ")))
                        } else {
                            elem
                        }
                    } else {
                        elem
                    }
                }

                return if (changed) container.padding.withElements(updated) else container
            }

            /**
             * Ensure space after commas for Statement elements (method parameters).
             */
            private fun ensureSpaceAfterCommasStatement(container: JContainer<Statement>): JContainer<Statement> {
                val elements = container.padding.elements
                var changed = false
                val updated = elements.mapIndexed { index, elem ->
                    if (index > 0) {
                        val prefix = elem.element.prefix
                        if (prefix.whitespace.isEmpty()) {
                            changed = true
                            elem.withElement(elem.element.withPrefix(prefix.withWhitespace(" ")))
                        } else {
                            elem
                        }
                    } else {
                        elem
                    }
                }

                return if (changed) container.padding.withElements(updated) else container
            }

            /**
             * Ensure space after commas for MapEntry elements.
             */
            private fun ensureSpaceAfterCommasMapEntry(container: JContainer<G.MapEntry>): JContainer<G.MapEntry> {
                val elements = container.padding.elements
                var changed = false
                val updated = elements.mapIndexed { index, elem ->
                    if (index > 0) {
                        val prefix = elem.element.prefix
                        if (prefix.whitespace.isEmpty()) {
                            changed = true
                            elem.withElement(elem.element.withPrefix(prefix.withWhitespace(" ")))
                        } else {
                            elem
                        }
                    } else {
                        elem
                    }
                }

                return if (changed) container.padding.withElements(updated) else container
            }

            /**
             * Ensure space after commas for NamedVariable elements.
             */
            private fun ensureSpaceAfterCommasNamedVar(
                elements: List<JRightPadded<J.VariableDeclarations.NamedVariable>>,
            ): List<JRightPadded<J.VariableDeclarations.NamedVariable>> {
                var changed = false
                val updated = elements.mapIndexed { index, elem ->
                    if (index > 0) {
                        val prefix = elem.element.prefix
                        if (prefix.whitespace.isEmpty()) {
                            changed = true
                            elem.withElement(elem.element.withPrefix(prefix.withWhitespace(" ")))
                        } else {
                            elem
                        }
                    } else {
                        elem
                    }
                }

                return if (changed) updated else elements
            }
        }
    }
}
