package com.github.albertocavalcante.gvy.gls.e2e

/** Session acquisition and cancellation callbacks; contexts with an already owned session need no cancellation hook. */
internal class ScenarioSessionAccess(val session: () -> LanguageServerSession, val checkActive: () -> Unit = {})
