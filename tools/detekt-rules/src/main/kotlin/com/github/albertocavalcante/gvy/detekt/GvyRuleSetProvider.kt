// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * Registers gvy's own detekt rules under the `gvy` rule set id.
 *
 * Every rule here must be purely syntactic (PSI only): the pull-request `lint` gate runs detekt without type
 * resolution (#2409), so a rule that needs it would silently never fire there.
 */
class GvyRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "gvy"

    override fun instance(config: Config): RuleSet = RuleSet(ruleSetId, listOf(RegexInFunctionBody(config)))
}
