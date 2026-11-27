// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Severity

/**
 * Reports uses of `java.util.concurrent.atomic`: an import, or a qualified name such as
 * `java.util.concurrent.atomic.AtomicLong()` in an expression or a type. [PackageUseRule] describes the detection.
 *
 * Shared state goes through gvy's own `OnceFlag`, `SharedValue` and `Counter` from `:common:concurrent`, which keep the
 * JVM's atomics in one module (#2474). Test sources, the facade itself and modules published on their own are
 * excluded in `tools/lint/detekt.yml`. A sanctioned use (a field updater) writes the qualified name at a declaration
 * annotated `@Suppress("JdkAtomics")`.
 */
internal class JdkAtomics(config: Config = Config.empty) : PackageUseRule(config, "java.util.concurrent.atomic") {
    override val issue = Issue(
        id = "JdkAtomics",
        severity = Severity.Maintainability,
        description = "Reports java.util.concurrent.atomic: gvy keeps it behind :common:concurrent (#2474).",
        debt = Debt.TEN_MINS,
    )

    override fun message(name: String): String = "`$name` bypasses gvy's shared-state types. Use OnceFlag, " +
        "SharedValue or Counter from :common:concurrent, or a plain field for state one thread owns (#2474)."
}
