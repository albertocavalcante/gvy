package com.github.groovylsp.bsp.client

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for BSP connection timeouts.
 *
 * Defines timeout durations for different phases of the BSP lifecycle:
 * - [initTimeout]: How long to wait for server initialization
 * - [requestTimeout]: Default timeout for BSP requests (compile, sources, etc.)
 * - [shutdownTimeout]: How long to wait for graceful shutdown
 *
 * All durations use Kotlin's type-safe [Duration] API.
 */
data class ConnectionConfig(
    val initTimeout: Duration = 60.seconds,
    val requestTimeout: Duration = 120.seconds,
    val shutdownTimeout: Duration = 10.seconds,
)
