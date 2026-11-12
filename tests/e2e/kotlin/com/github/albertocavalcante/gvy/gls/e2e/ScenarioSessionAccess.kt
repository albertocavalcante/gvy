// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

/** Session acquisition and cancellation callbacks; contexts with an already owned session need no cancellation hook. */
internal class ScenarioSessionAccess(val session: () -> LanguageServerSession, val checkActive: () -> Unit = {})
