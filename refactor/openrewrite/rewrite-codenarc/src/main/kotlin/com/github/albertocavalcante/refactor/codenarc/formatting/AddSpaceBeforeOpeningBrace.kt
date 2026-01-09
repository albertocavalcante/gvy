package com.github.albertocavalcante.refactor.codenarc.formatting

import org.openrewrite.ExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.TreeVisitor
import org.openrewrite.groovy.GroovyIsoVisitor
import org.openrewrite.java.tree.J

/**
 * Recipe to add a space before opening braces in Groovy code.
 *
 * This aligns with CodeNarc rule: SpaceBeforeOpeningBrace
 *
 * The recipe ensures that opening braces for classes, methods, closures,
 * and control structures are preceded by a space for readability.
 *
 * @see <a href="https://codenarc.org/codenarc-rules-formatting.html#spacebeforeopeningbrace">CodeNarc Rule</a>
 */
class AddSpaceBeforeOpeningBrace : Recipe() {

    override fun getDisplayName(): String = "Add space before opening brace"

    override fun getDescription(): String = "Ensures opening braces are preceded by a space. " +
        "CodeNarc Rule: SpaceBeforeOpeningBrace. " +
        "https://codenarc.org/codenarc-rules-formatting.html#spacebeforeopeningbrace."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> {
        return object : GroovyIsoVisitor<ExecutionContext>() {

            override fun visitMethodDeclaration(
                method: J.MethodDeclaration,
                ctx: ExecutionContext,
            ): J.MethodDeclaration {
                var m = super.visitMethodDeclaration(method, ctx)

                val body = m.body
                if (body != null) {
                    val updatedBody = ensureSpaceBeforeBrace(body)
                    if (updatedBody !== body) {
                        m = m.withBody(updatedBody)
                    }
                }

                return m
            }

            override fun visitClassDeclaration(
                classDecl: J.ClassDeclaration,
                ctx: ExecutionContext,
            ): J.ClassDeclaration {
                var c = super.visitClassDeclaration(classDecl, ctx)

                val body = c.body
                val updatedBody = ensureSpaceBeforeBrace(body)
                if (updatedBody !== body) {
                    c = c.withBody(updatedBody)
                }

                return c
            }

            override fun visitIf(iff: J.If, ctx: ExecutionContext): J.If {
                var i = super.visitIf(iff, ctx)

                // Handle then part
                val thenPart = i.thenPart
                if (thenPart is J.Block) {
                    val updatedThenPart = ensureSpaceBeforeBrace(thenPart)
                    if (updatedThenPart !== thenPart) {
                        i = i.withThenPart(updatedThenPart)
                    }
                }

                // Handle else part
                val elsePart = i.elsePart
                if (elsePart != null) {
                    val elseBody = elsePart.body
                    if (elseBody is J.Block) {
                        val updatedElseBody = ensureSpaceBeforeBrace(elseBody)
                        if (updatedElseBody !== elseBody) {
                            i = i.withElsePart(elsePart.withBody(updatedElseBody))
                        }
                    }
                }

                return i
            }

            override fun visitForLoop(forLoop: J.ForLoop, ctx: ExecutionContext): J.ForLoop {
                var f = super.visitForLoop(forLoop, ctx)

                val body = f.body
                if (body is J.Block) {
                    val updatedBody = ensureSpaceBeforeBrace(body)
                    if (updatedBody !== body) {
                        f = f.withBody(updatedBody)
                    }
                }

                return f
            }

            override fun visitForEachLoop(forEachLoop: J.ForEachLoop, ctx: ExecutionContext): J.ForEachLoop {
                var f = super.visitForEachLoop(forEachLoop, ctx)

                val body = f.body
                if (body is J.Block) {
                    val updatedBody = ensureSpaceBeforeBrace(body)
                    if (updatedBody !== body) {
                        f = f.withBody(updatedBody)
                    }
                }

                return f
            }

            override fun visitWhileLoop(whileLoop: J.WhileLoop, ctx: ExecutionContext): J.WhileLoop {
                var w = super.visitWhileLoop(whileLoop, ctx)

                val body = w.body
                if (body is J.Block) {
                    val updatedBody = ensureSpaceBeforeBrace(body)
                    if (updatedBody !== body) {
                        w = w.withBody(updatedBody)
                    }
                }

                return w
            }

            override fun visitTry(tryStmt: J.Try, ctx: ExecutionContext): J.Try {
                var t = super.visitTry(tryStmt, ctx)

                // Handle try block
                val body = t.body
                val updatedBody = ensureSpaceBeforeBrace(body)
                if (updatedBody !== body) {
                    t = t.withBody(updatedBody)
                }

                // Handle catch blocks
                val catches = t.catches
                if (catches.isNotEmpty()) {
                    var catchesChanged = false
                    val updatedCatches = catches.map { catch ->
                        val catchBody = catch.body
                        val updatedCatchBody = ensureSpaceBeforeBrace(catchBody)
                        if (updatedCatchBody !== catchBody) {
                            catchesChanged = true
                            catch.withBody(updatedCatchBody)
                        } else {
                            catch
                        }
                    }
                    if (catchesChanged) {
                        t = t.withCatches(updatedCatches)
                    }
                }

                // Handle finally block
                val finallyBlock = t.padding.finally
                if (finallyBlock != null) {
                    val finallyBody = finallyBlock.element
                    val updatedFinally = ensureSpaceBeforeBrace(finallyBody)
                    if (updatedFinally !== finallyBody) {
                        t = t.padding.withFinally(finallyBlock.withElement(updatedFinally))
                    }
                }

                return t
            }

            override fun visitLambda(lambda: J.Lambda, ctx: ExecutionContext): J.Lambda {
                var l = super.visitLambda(lambda, ctx)

                // For Groovy trailing closures (with OmitParentheses marker),
                // the space goes on the lambda itself, not on the body block
                val hasOmitMarker =
                    l.markers.findFirst(org.openrewrite.java.marker.OmitParentheses::class.java).isPresent ||
                        l.markers.findFirst(org.openrewrite.groovy.marker.OmitParentheses::class.java).isPresent

                if (hasOmitMarker) {
                    // Add space to lambda's prefix (e.g., list.each{} -> list.each {})
                    val prefix = l.prefix
                    val whitespace = prefix.whitespace
                    if (whitespace.isEmpty()) {
                        l = l.withPrefix(prefix.withWhitespace(" "))
                    }
                } else {
                    // For regular lambdas (e.g., in method declarations), add space to body block
                    val body = l.body
                    if (body is J.Block) {
                        val updatedBody = ensureSpaceBeforeBrace(body)
                        if (updatedBody !== body) {
                            l = l.withBody(updatedBody)
                        }
                    }
                }

                return l
            }

            /**
             * Ensures a block has a space in its prefix if the prefix is empty.
             * Preserves newlines and multiple spaces.
             */
            private fun ensureSpaceBeforeBrace(block: J.Block): J.Block {
                val prefix = block.prefix
                val whitespace = prefix.whitespace

                // Only add space if:
                // 1. There's no whitespace at all (empty string)
                // If there's already whitespace (space, newline, etc.), keep it as-is
                return if (whitespace.isEmpty()) {
                    block.withPrefix(prefix.withWhitespace(" "))
                } else {
                    block
                }
            }
        }
    }
}
