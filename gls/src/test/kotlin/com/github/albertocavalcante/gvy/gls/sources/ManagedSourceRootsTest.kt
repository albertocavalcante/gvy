// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.build.defaultVerifiedSourceCacheRoot
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertTrue

class ManagedSourceRootsTest {
    @Test
    fun `verified repository downloads are managed before any source descriptor exists`() {
        assertTrue(defaultVerifiedSourceCacheRoot() in defaultManagedSourceRoots())
    }

    @Test
    fun `dedicated legacy extraction namespace stays managed when entry metadata is missing`() {
        val legacyRoot = Path.of("dedicated-sources").toAbsolutePath()
        val roots = defaultManagedSourceRoots(legacyRoot)
        assertTrue(legacyRoot in roots)
        assertTrue(roots.any { legacyRoot.resolve("old-library/example/Foo.groovy").startsWith(it) })
    }
}
