// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Reports a JUnit test or lifecycle function that runs its whole body in `runBlocking { }` or `runTest { }` in any
 * form other than `fun name(): Unit = runBlocking { ... }`.
 *
 * Several forms run the same test, and gvy writes one, so that a reader and `lint` check one shape (#2507). Two others
 * are reported:
 *
 * 1. A type argument on the builder, `= runBlocking<Unit> { }`. It states the function's return type where `runTest`
 *    and other expression bodies cannot, so it cannot be the one form.
 * 2. A block body whose only statement is the builder call, `{ runBlocking { } }` or `{ return runBlocking { } }`.
 *
 * A block body with other statements around the builder is a different test shape and is not reported. A missing
 * `: Unit` is [TestFunctionReturnType]'s finding, not this rule's.
 *
 * The rule is purely syntactic (PSI only) because the pull-request `lint` gate runs detekt without type resolution
 * (#2409). A builder is a call named `runBlocking` or `runTest` with a trailing lambda, with or without a receiver or
 * arguments (`kotlinx.coroutines.runBlocking { }`, `scope.runTest { }`, `runTest(timeout) { }`); a safe call
 * (`scope?.runTest { }`) is not one, because its value is nullable. A message states the edit and never rewrites the
 * call, so that a receiver or an argument is not lost. [JUnitFunctionAnnotations] decides which functions JUnit calls.
 * The known limits are in `tools/detekt-rules/README.md`.
 */
internal class CoroutineTestBody(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        id = "CoroutineTestBody",
        severity = Severity.Style,
        description = "Reports JUnit test and lifecycle functions that wrap their whole body in a coroutine builder " +
            "in a form other than `fun name(): Unit = runBlocking { ... }` (#2507).",
        debt = Debt.FIVE_MINS,
    )

    private var annotations: JUnitFunctionAnnotations? = null

    override fun visitKtFile(file: KtFile) {
        annotations = JUnitFunctionAnnotations.of(file)
        super.visitKtFile(file)
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val annotation = annotations?.on(function) ?: return
        if (function.hasExpressionBody()) {
            function.bodyExpression?.builderCall()?.let { builder -> reportTypeArgument(builder, annotation) }
        } else {
            function.wrappedBuilderStatement()?.let { statement -> reportBlockWrapper(statement, annotation) }
        }
    }

    private fun reportTypeArgument(builder: KtCallExpression, annotation: String) {
        val typeArguments = builder.typeArgumentList ?: return
        val spelling = builder.calledName() + typeArguments.text.replace("`", "").replace(WHITESPACE, "")
        report(
            CodeSmell(
                issue,
                Entity.from(typeArguments),
                "`$spelling` puts the return type of this @$annotation function on the coroutine builder, which " +
                    "`runTest` and other expression bodies cannot do. Remove the type argument and declare `: Unit` " +
                    "on the function, between `)` and `=`; keep the builder's receiver and arguments (#2507).",
            ),
        )
    }

    private fun reportBlockWrapper(statement: KtExpression, annotation: String) {
        report(
            CodeSmell(
                issue,
                Entity.from(statement),
                "`${statement.firstLine()}` is the only statement in the block body of this @$annotation function. " +
                    "Write `fun name(): Unit =` followed by that call, with its receiver and arguments, and remove " +
                    "the outer braces and any `return` (#2507).",
            ),
        )
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
