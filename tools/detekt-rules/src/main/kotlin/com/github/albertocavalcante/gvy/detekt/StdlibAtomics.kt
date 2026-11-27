// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Severity

/**
 * Reports uses of the stdlib's `kotlin.concurrent.atomics`: an import, a star import included, or a qualified name such
 * as `kotlin.concurrent.atomics.AtomicInt(0)` or `@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)`.
 * [PackageUseRule] describes the detection.
 *
 * The package is experimental (`@ExperimentalAtomicApi`, KT-82228). `:common:concurrent` is the only module that opts
 * in, behind gvy's own `OnceFlag`, `SharedValue` and `Counter`, so an upstream change breaks that module alone (#2474).
 * `tools/lint/detekt.yml` excludes it and nothing else. The compiler's opt-in requirement is the first fence against
 * accidental use; this rule is the second, and it also reports a module that deliberately adds the opt-in.
 *
 * detekt's `ForbiddenImport` held this policy before. It matches import paths only, so it missed a star import
 * (`import kotlin.concurrent.atomics.*` has no trailing dot) and a fully qualified use, which needs no import.
 */
internal class StdlibAtomics(config: Config = Config.empty) : PackageUseRule(config, "kotlin.concurrent.atomics") {
    override val issue = Issue(
        id = "StdlibAtomics",
        severity = Severity.Maintainability,
        description = "Reports the experimental kotlin.concurrent.atomics: only :common:concurrent opts in (#2474).",
        debt = Debt.TEN_MINS,
    )

    override fun message(name: String): String = "`$name` is experimental, and only :common:concurrent opts into it. " +
        "Use OnceFlag, SharedValue or Counter from that module, or a plain field for state one thread owns (#2474)."
}
