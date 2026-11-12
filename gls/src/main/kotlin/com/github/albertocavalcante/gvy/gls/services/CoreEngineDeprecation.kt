package com.github.albertocavalcante.gvy.gls.services

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean

/** One migration notice for a server's startup and subsequent configuration intake. */
internal class CoreEngineDeprecation {
    private val reported = AtomicBoolean(false)
    private val logger = KotlinLogging.logger {}

    fun report(engine: String?) {
        if (engine?.equals("core", ignoreCase = true) == true && reported.compareAndSet(false, true)) {
            logger.warn {
                "groovy.languageServer.engine=core is deprecated. " +
                    "Saved core values select native on startup or restart. " +
                    "The alias is supported through 0.5.5 and will be removed in 0.5.6. " +
                    "Set groovy.languageServer.engine=native and restart the language server."
            }
        }
    }
}
