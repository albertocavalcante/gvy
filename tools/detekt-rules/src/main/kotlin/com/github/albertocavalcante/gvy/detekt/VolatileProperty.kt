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
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Reports `@Volatile` annotations, and a typealias of the annotation where it is declared.
 *
 * A volatile field makes single reads and writes visible to other threads, but `x++`, check-then-act and two related
 * fields read one after the other still race, without a compiler warning. Each field should use the type that says
 * what it is for: `OnceFlag`, `SharedValue` or `Counter` from `:common:concurrent`, `lazy`, or a lock (#2474).
 *
 * The rule is purely syntactic (PSI only) because the pull-request `lint` gate runs detekt without type resolution
 * (#2409). It recognises the unqualified name, `kotlin.jvm.Volatile`, `kotlin.concurrent.Volatile` and import aliases
 * of either, with any use-site target. Names are read by PSI segment ([qualifiedName]), so backticks, comments and line
 * breaks inside them do not hide the annotation. When a file imports another annotation named `Volatile`, the
 * unqualified name refers to that one and is not reported. A typealias (`typealias V = kotlin.jvm.Volatile`) is
 * reported at its declaration, not at each `@V`: removing it is the fix. The known limits are in
 * `tools/detekt-rules/README.md`.
 */
internal class VolatileProperty(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        id = "VolatileProperty",
        severity = Severity.Warning,
        description = "A @Volatile field allows racy compound updates. Use OnceFlag, SharedValue or Counter from " +
            ":common:concurrent, `lazy`, or a lock (#2474).",
        debt = Debt.TWENTY_MINS,
    )

    private var volatileNames: Set<String> = emptySet()

    override fun visitKtFile(file: KtFile) {
        volatileNames = volatileNamesIn(file)
        super.visitKtFile(file)
    }

    override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
        super.visitAnnotationEntry(annotationEntry)
        val name = annotationEntry.typeReference?.userTypeName() ?: return
        if (name in volatileNames) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(annotationEntry),
                    "`@$name` allows racy compound updates such as `x++` and check-then-act. Use OnceFlag, " +
                        "SharedValue or Counter from :common:concurrent, `lazy`, or a lock (#2474).",
                ),
            )
        }
    }

    override fun visitTypeAlias(typeAlias: KtTypeAlias) {
        super.visitTypeAlias(typeAlias)
        val name = typeAlias.getTypeReference()?.userTypeName() ?: return
        if (name in volatileNames) {
            report(
                CodeSmell(
                    issue,
                    Entity.atName(typeAlias),
                    "`typealias ${typeAlias.name} = $name` lets `@${typeAlias.name}` declare volatile fields that " +
                        "this rule cannot see. Use OnceFlag, SharedValue or Counter from :common:concurrent, `lazy`, " +
                        "or a lock (#2474).",
                ),
            )
        }
    }

    private fun KtTypeReference.userTypeName(): String? = (typeElement as? KtUserType)?.qualifiedName()

    /** The spellings that name Kotlin's annotation in [file]: qualified names, import aliases and the plain name. */
    private fun volatileNamesIn(file: KtFile): Set<String> {
        val names = KOTLIN_VOLATILE.toMutableSet()
        var importsOtherVolatile = false
        for (directive in file.importDirectives) {
            // An alias keeps its backticks in PSI (`as `V``). qualifiedName() drops them, so drop them here too.
            val importedName = directive.importedName?.asString()?.let(KtPsiUtil::unquoteIdentifier) ?: continue
            val importsKotlinVolatile = directive.importedFqName?.asString() in KOTLIN_VOLATILE
            when {
                importsKotlinVolatile -> names += importedName
                importedName == PLAIN_NAME -> importsOtherVolatile = true
            }
        }
        // Kotlin's annotation is a default import on the JVM, so the plain name means it unless an import says not.
        if (!importsOtherVolatile) names += PLAIN_NAME
        return names
    }

    private companion object {
        const val PLAIN_NAME = "Volatile"
        val KOTLIN_VOLATILE = setOf("kotlin.jvm.Volatile", "kotlin.concurrent.Volatile")
    }
}
