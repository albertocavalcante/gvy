// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class GradleDaemonSettingsTest {

    @Test
    fun `defaults are a ten minute daemon and a five minute connection`() {
        val settings = GradleDaemonSettings()

        assertEquals(10.minutes, settings.daemonIdleTimeout)
        assertEquals(5.minutes, settings.connectionIdleTimeout)
    }

    @Test
    fun `withDaemonIdleMinutes uses the value only when positive`() {
        assertEquals(3.minutes, GradleDaemonSettings.withDaemonIdleMinutes(3).daemonIdleTimeout)
        assertEquals(10.minutes, GradleDaemonSettings.withDaemonIdleMinutes(0).daemonIdleTimeout)
        assertEquals(10.minutes, GradleDaemonSettings.withDaemonIdleMinutes(-1).daemonIdleTimeout)
        assertEquals(10.minutes, GradleDaemonSettings.withDaemonIdleMinutes(null).daemonIdleTimeout)
        // Clamped to three hours rather than rejected, so a silly setting cannot break startup.
        assertEquals(180.minutes, GradleDaemonSettings.withDaemonIdleMinutes(Int.MAX_VALUE).daemonIdleTimeout)
        assertEquals(180.minutes, GradleDaemonSettings.withDaemonIdleMinutes(180).daemonIdleTimeout)
    }

    @Test
    fun `non-positive durations are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { GradleDaemonSettings(daemonIdleTimeout = Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { GradleDaemonSettings(daemonIdleTimeout = 365.days) }
        assertThrows(IllegalArgumentException::class.java) {
            GradleDaemonSettings(connectionIdleTimeout = Duration.ZERO)
        }
    }

    @Test
    fun `operation arguments carry the idle timeout and are stable so resolutions reuse one daemon`() {
        val expected = listOf(
            "-Dorg.gradle.daemon=true",
            "-Dorg.gradle.parallel=true",
            "-Dorg.gradle.configureondemand=true",
            "-Dorg.gradle.vfs.watch=true",
            "-Dorg.gradle.daemon.idletimeout=600000",
        )
        assertEquals(expected, GradleOperationArguments.buildArguments(GradleDaemonSettings()).toList())
        assertEquals(expected, GradleOperationArguments.buildArguments(GradleDaemonSettings()).toList())
        assertEquals(
            "-Dorg.gradle.daemon.idletimeout=180000",
            GradleOperationArguments.buildArguments(GradleDaemonSettings.withDaemonIdleMinutes(3)).last(),
        )
        assertEquals(listOf("-Xmx1g", "-XX:+UseG1GC"), GradleOperationArguments.JVM_ARGUMENTS.toList())
    }
}
