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
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Reports a JUnit test or lifecycle function whose return type is not declared the one way gvy declares it: `: Unit`
 * on an expression body, and nothing on a block body.
 *
 * JUnit does not accept a test or lifecycle function that returns a value: it does not run such a test. An expression
 * body without a declared type returns whatever its last expression returns, so
 * `fun x() = runBlocking { ...; assertNotNull(y) }` returns `y` and is not run. The build fails on JUnit's discovery
 * warning (#1080), but only when the tests run; this rule reports the declaration in `lint`, whatever the last
 * expression is today. Three forms are reported:
 *
 * 1. An expression body with no declared type: declare `: Unit`, or use a block body when the expression has a value
 *    of its own (`= assertThrows<E> { }`) and so cannot be a `Unit` body.
 * 2. A declared type that is not exactly `Unit`, on any body. `TestResult`, `kotlin.Unit` and `Unit?` are reported
 *    too: without type resolution an alias cannot be told from a value, and one spelling is easier to check by eye.
 * 3. `: Unit` on a block body or on a function without a body, where it says nothing. A block body that only wraps a
 *    coroutine builder is left to [CoroutineTestBody], whose fix keeps the type.
 *
 * The rule is purely syntactic (PSI only) because the pull-request `lint` gate runs detekt without type resolution
 * (#2409). [JUnitFunctionAnnotations] decides which functions JUnit calls. The known limits are in
 * `tools/detekt-rules/README.md`.
 */
internal class TestFunctionReturnType(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        id = "TestFunctionReturnType",
        severity = Severity.Defect,
        description = "Reports JUnit test and lifecycle functions that leave the type of an expression body " +
            "to inference, declare a return type other than `Unit`, or declare `: Unit` without an expression " +
            "body: JUnit does not run a test that returns a value (#2507).",
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
        val declared = function.typeReference
        when {
            declared == null -> if (function.hasExpressionBody()) reportInferred(function, annotation)
            !declared.isPlainUnit() -> reportOtherType(declared, annotation)
            function.hasExpressionBody() -> Unit
            // CoroutineTestBody asks for this body to become an expression body, which keeps the type.
            function.wrappedBuilderStatement() == null -> reportRedundantUnit(declared, annotation)
        }
    }

    private fun reportInferred(function: KtNamedFunction, annotation: String) {
        val body = function.bodyExpression?.firstLine().orEmpty()
        report(
            CodeSmell(
                issue,
                Entity.atName(function),
                "`= $body` is an expression body whose type this @$annotation function leaves to inference, so the " +
                    "function returns the value of its last expression, and JUnit does not accept a test or " +
                    "lifecycle function that returns a value. Insert `: Unit` between `)` and `=` and leave the " +
                    "body as it is; when the body has a value of its own and then does not compile, use a block " +
                    "body instead (#2507).",
            ),
        )
    }

    private fun reportOtherType(declared: KtTypeReference, annotation: String) {
        report(
            CodeSmell(
                issue,
                Entity.from(declared),
                "`: ${declared.spelling()}` is not how a @$annotation function declares its return type: JUnit does " +
                    "not accept a test or lifecycle function that returns a value, and another spelling hides " +
                    "whether this one does. Declare exactly `: Unit` on an expression body, and no type on a block " +
                    "body (#2507).",
            ),
        )
    }

    private fun reportRedundantUnit(declared: KtTypeReference, annotation: String) {
        report(
            CodeSmell(
                issue,
                Entity.from(declared),
                "`: Unit` says nothing on this @$annotation function, which has no expression body and so returns " +
                    "Unit already. Remove the type; only an expression body declares it, as in " +
                    "`fun name(): Unit = runBlocking { ... }` (#2507).",
            ),
        )
    }

    /** True for exactly `Unit`: not `kotlin.Unit`, `Unit?`, an annotated type or a type with arguments. */
    private fun KtTypeReference.isPlainUnit(): Boolean {
        val type = typeElement as? KtUserType ?: return false
        return type.qualifier == null &&
            type.referencedName == "Unit" &&
            type.typeArgumentList == null &&
            annotationEntries.isEmpty()
    }

    /** The type as written, on one line and without backticks, so that it can sit inside a message's code span. */
    private fun KtTypeReference.spelling(): String = text.replace("`", "").replace(WHITESPACE, " ")

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
