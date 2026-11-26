// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.concurrent.OnceFlag
import io.github.oshai.kotlinlogging.KotlinLogging

/** One migration notice for a server's startup and subsequent configuration intake. */
internal class CoreEngineDeprecation {
    private val reported = OnceFlag()
    private val logger = KotlinLogging.logger {}

    fun report(engine: String?) {
        if (engine?.equals("core", ignoreCase = true) == true && reported.trySet()) {
            logger.warn {
                "groovy.languageServer.engine=core is deprecated. " +
                    "Saved core values select native on startup or restart. " +
                    "The alias is supported through 0.5.5 and will be removed in 0.5.6. " +
                    "Set groovy.languageServer.engine=native and restart the language server."
            }
        }
    }
}
