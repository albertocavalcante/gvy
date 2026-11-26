// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity

// RED: registered but reports nothing yet, so the fixture tests fail until detection is implemented.
internal class JdkAtomics(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        id = "JdkAtomics",
        severity = Severity.Maintainability,
        description = "A use of java.util.concurrent.atomic.",
        debt = Debt.TEN_MINS,
    )
}
