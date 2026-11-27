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
 *
 * The rules share conventions. A finding's message names the reported code in backticks and says what is wrong with
 * it, then gives the fix and the issue that set the policy. The issue's description says what the rule reports,
 * starting with "Reports". The severity is detekt's category for the harm: `Defect` for a likely race,
 * `Maintainability` for an API kept to one module, `Performance` for repeated work. The debt is the usual time to fix
 * one finding. Dotted names are read by PSI segment through `PsiNames.kt`, never compared as source text.
 *
 * `GvyRuleSetProviderTest` checks each rule's severity, debt and description prefix, and the message shape of a few
 * representative findings per rule. Review and each rule's spelling fixtures keep the `PsiNames.kt` convention.
 */
class GvyRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "gvy"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(RegexInFunctionBody(config), VolatileProperty(config), JdkAtomics(config), StdlibAtomics(config)),
    )
}
