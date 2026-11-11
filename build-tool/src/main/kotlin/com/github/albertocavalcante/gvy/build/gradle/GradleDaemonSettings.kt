package com.github.albertocavalcante.gvy.build.gradle

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Lifetime knobs for the Gradle daemon and Tooling API connections that the language server starts for dependency
 * resolution.
 *
 * @property daemonIdleTimeout how long a daemon started by the server stays alive without work (sent to Gradle
 *   as `org.gradle.daemon.idletimeout` with every operation). Gradle's own default
 *   is 3 hours, which leaves a second JVM charged to the language server long after resolution has finished.
 * @property connectionIdleTimeout how long a pooled [org.gradle.tooling.ProjectConnection] may sit unused before the
 *   pool closes it (closing a connection drops the client-side Tooling API state; it does not stop the daemon).
 */
data class GradleDaemonSettings(
    val daemonIdleTimeout: Duration = DEFAULT_DAEMON_IDLE_TIMEOUT,
    val connectionIdleTimeout: Duration = DEFAULT_CONNECTION_IDLE_TIMEOUT,
) {
    init {
        require(daemonIdleTimeout.isPositive() && daemonIdleTimeout.inWholeMilliseconds <= Int.MAX_VALUE) {
            "daemonIdleTimeout must be positive and fit in an int of milliseconds: $daemonIdleTimeout"
        }
        require(connectionIdleTimeout.isPositive()) {
            "connectionIdleTimeout must be positive: $connectionIdleTimeout"
        }
    }

    companion object {
        val DEFAULT_DAEMON_IDLE_TIMEOUT: Duration = 10.minutes
        val DEFAULT_CONNECTION_IDLE_TIMEOUT: Duration = 5.minutes

        /** Gradle takes the idle timeout as an int of milliseconds; larger values are clamped to this many minutes. */
        private const val MAX_DAEMON_IDLE_MINUTES = Int.MAX_VALUE / 60_000

        /**
         * Builds settings from a user-facing minutes value; null or non-positive falls back to the default and
         * very large values are clamped to what Gradle can represent.
         */
        fun withDaemonIdleMinutes(minutes: Int?): GradleDaemonSettings = GradleDaemonSettings(
            daemonIdleTimeout = minutes?.takeIf { it > 0 }?.coerceAtMost(MAX_DAEMON_IDLE_MINUTES)?.minutes
                ?: DEFAULT_DAEMON_IDLE_TIMEOUT,
        )
    }
}

/**
 * The arguments every language-server Tooling API operation passes to Gradle. They are shared by all operations
 * (the JDK/Gradle pre-flight check and the IDEA model fetch) and are constants, so successive resolutions request
 * the same daemon: Gradle reuses a daemon only on an exact match of JVM args and properties, and any difference
 * would start another daemon per call.
 */
internal object GradleOperationArguments {
    /**
     * `org.gradle.daemon.idletimeout` (milliseconds) is a Gradle property read from the operation's `-D` arguments:
     * the daemon this operation starts exits after that much idle time instead of Gradle's 3 hour default. It is
     * not part of daemon compatibility, so it never causes a second daemon by itself.
     */
    fun buildArguments(settings: GradleDaemonSettings): Array<String> = arrayOf(
        "-Dorg.gradle.daemon=true",
        "-Dorg.gradle.parallel=true",
        "-Dorg.gradle.configureondemand=true",
        "-Dorg.gradle.vfs.watch=true",
        "-Dorg.gradle.daemon.idletimeout=${settings.daemonIdleTimeout.inWholeMilliseconds}",
    )

    /**
     * `-Xmx` is a ceiling, not a reservation: the daemon only grows to what the build's configuration needs, and the
     * idle timeout is what returns the memory. It is kept at 1 GB because the IDEA model configures the whole build
     * and a too-small ceiling turns a large multi-project build into an OutOfMemoryError (no tests or history
     * justify a different number).
     */
    val JVM_ARGUMENTS: Array<String> = arrayOf("-Xmx1g", "-XX:+UseG1GC")
}
