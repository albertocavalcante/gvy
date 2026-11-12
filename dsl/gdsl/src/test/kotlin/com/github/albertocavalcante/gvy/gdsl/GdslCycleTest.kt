// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gdsl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration

/** A GDSL script declaring classes whose superclasses form a cycle is answered, never hangs (#1411). */
class GdslCycleTest {
    @Test
    fun `class declarations in a superclass cycle are skipped without hanging`() {
        val result = assertTimeoutPreemptively(Duration.ofSeconds(TIMEOUT_SECONDS)) {
            GdslExecutor().executeAndCapture("class A extends B {}\nclass B extends A {}\n")
        }

        assertThat(result.success && result.warnings.none { "Class declarations" in it })
            .describedAs("classes must be reported as skipped, or the script rejected: %s", result.warnings)
            .isFalse()
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
    }
}
