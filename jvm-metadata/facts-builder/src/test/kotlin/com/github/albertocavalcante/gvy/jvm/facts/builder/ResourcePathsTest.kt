// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

import com.github.albertocavalcante.gvy.jvm.facts.FactsFormat
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourcePathsTest {
    @Test
    fun `maps a root class path to its internal name with release 0`() {
        assertEquals(ResourceClass.Candidate("p/q/A", 0), ResourcePaths.classify("p/q/A.class"))
        assertEquals(ResourceClass.Candidate("A", 0), ResourcePaths.classify("A.class"))
        assertEquals(ResourceClass.Candidate("p/A\$B", 0), ResourcePaths.classify("p/A\$B.class"))
    }

    @Test
    fun `maps META-INF versions N to release N for N from 1 to 255`() {
        for (release in 1..FactsFormat.MAX_RELEASE) {
            assertEquals(
                ResourceClass.Candidate("p/A", release),
                ResourcePaths.classify("META-INF/versions/$release/p/A.class"),
                "release $release",
            )
        }
    }

    @Test
    fun `skips versioned directories that are not a canonical decimal from 1 to 255`() {
        val directories = listOf("0", "09", "+9", "-1", "256", "1000", "x", "", "9x", "١")
        for (directory in directories) {
            assertEquals(
                ResourceClass.Skip(SkipReason.UNSUPPORTED_RELEASE),
                ResourcePaths.classify("META-INF/versions/$directory/p/A.class"),
                "directory '$directory'",
            )
        }
    }

    @Test
    fun `skips module-info and package-info at the root, under versions and in modules`() {
        val descriptors = listOf(
            "module-info.class",
            "package-info.class",
            "p/q/package-info.class",
            "META-INF/versions/9/module-info.class",
            "META-INF/versions/9/p/package-info.class",
        )
        for (path in descriptors) {
            assertEquals(ResourceClass.NotAClass, ResourcePaths.classify(path), path)
        }
        assertEquals(ResourceClass.Candidate("p/module-info2", 0), ResourcePaths.classify("p/module-info2.class"))
    }

    @Test
    fun `skips other META-INF class files`() {
        val paths = listOf(
            "META-INF/Foo.class",
            "META-INF/services/p/A.class",
            "META-INF/versions/Foo.class",
            "META-INF/MANIFEST.MF",
            "p/A.txt",
            "p/",
        )
        for (path in paths) {
            assertEquals(ResourceClass.NotAClass, ResourcePaths.classify(path), path)
        }
    }

    @Test
    fun `rejects dotted, empty-segment and bracketed internal names`() {
        val invalid = listOf("", "a.b/C", "a//C", "/a/C", "a/C/", "a/[C", "a/C;", "a/b;c", "a/b[")
        for (name in invalid) {
            assertFalse(ResourcePaths.isValidInternalName(name), "name '$name'")
        }
        for (name in listOf("a/b/C", "C", "a/B\$1", "a/Ünicode")) {
            assertTrue(ResourcePaths.isValidInternalName(name), "name '$name'")
        }
        for (path in listOf("a.b/C.class", "a//C.class", "/a/C.class", "a/[C.class", ".class", "a/C;.class")) {
            assertEquals(ResourceClass.Skip(SkipReason.INVALID_NAME), ResourcePaths.classify(path), path)
        }
    }

    @Test
    fun `checks descriptor file names before the release`() {
        for (path in listOf("META-INF/versions/0/module-info.class", "META-INF/versions/x/p/package-info.class")) {
            assertEquals(ResourceClass.NotAClass, ResourcePaths.classify(path), path)
        }
    }

    @Test
    fun `rejects internal names that cannot be stored`() {
        val tooLong = "a".repeat(FactsFormat.MAX_STRING_BYTES + 1)
        assertFalse(ResourcePaths.isValidInternalName(tooLong))
        assertTrue(ResourcePaths.isValidInternalName("a".repeat(FactsFormat.MAX_STRING_BYTES)))
        assertFalse(ResourcePaths.isValidInternalName("p/\uD800A"))
        assertEquals(ResourceClass.Skip(SkipReason.INVALID_NAME), ResourcePaths.classify("$tooLong.class"))
        assertEquals(ResourceClass.Skip(SkipReason.INVALID_NAME), ResourcePaths.classify("p/\uDC00A.class"))
        assertEquals(
            ResourceClass.Skip(SkipReason.INVALID_NAME),
            ResourcePaths.classify("META-INF/versions/9/p/\uD800A.class"),
        )
    }
}
