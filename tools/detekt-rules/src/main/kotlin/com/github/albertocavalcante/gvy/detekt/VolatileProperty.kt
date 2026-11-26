// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity

// RED: registered but reports nothing yet, so the fixture tests fail until detection is implemented.
internal class VolatileProperty(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        id = "VolatileProperty",
        severity = Severity.Warning,
        description = "A @Volatile field.",
        debt = Debt.TWENTY_MINS,
    )
}
