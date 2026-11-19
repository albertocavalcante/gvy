// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.NativeArtifactStamp
import com.github.albertocavalcante.nativeapi.NativeClasspathInputs
import com.github.albertocavalcante.nativeapi.NativeCompilerSdkIdentity
import com.github.albertocavalcante.nativeapi.NativeJdkIdentity
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class NativeJdkIdentityTest {
    @Test
    fun `native inputs capture the actual boot image and preserve it when recording compiler fallback`() {
        val boot = assertNotNull(NativeJdkIdentity.observe())
        assertEquals(boot.imageHome.toRealPath(), boot.imageHome)
        assertEquals(Runtime.version().toString(), boot.runtimeVersion)
        assertEquals(System.getProperty("java.vendor"), boot.vendor)
        assertEquals(
            NativeArtifactStamp.observe(boot.imageHome.resolve("lib/modules"), maxPaths = 1),
            boot.modulesStamp,
        )

        val inputs = NativeClasspathInputs.capture(emptyList(), emptyList())
        assertEquals(boot, inputs.bootJdk)
        val sdk = NativeCompilerSdkIdentity(
            "control",
            boot.imageHome.resolve("lib/modules").toUri(),
            boot.modulesStamp,
            "a".repeat(64),
        )
        assertEquals(boot, inputs.withCompilerSdk(sdk).bootJdk)
    }

    @Test
    fun `boot image observation preserves request cancellation`() {
        val cancellation = CancellationException("stop")
        assertSame(
            cancellation,
            assertFailsWith<CancellationException> { NativeJdkIdentity.observe { throw cancellation } },
        )
    }
}
