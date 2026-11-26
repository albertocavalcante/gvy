// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.nativeapi.ParseMode
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferenceSourceProjectionTest {
    @Test
    fun `hiding one of a thousand listed sources retains at most one selected path per index entry`() {
        val paths = List(1000) { Path.of("source$it.groovy") }
        val workspace = WorkspaceModel(null, emptyList(), emptyList(), paths) { _, _ -> "fingerprint" }
        val uri = URI.create("file:///source0.groovy")
        val sources = ReferenceSources(
            workspace,
            ArtifactOwnership().begin(uri),
            Any(),
            ParseMode.WORKSPACE,
            paths.drop(1),
            emptyMap(),
        )
        val retainedPaths = sources.javaClass.declaredFields.sumOf { field ->
            field.trySetAccessible()
            (field.get(sources) as? Collection<*>)?.count { it is Path } ?: 0
        }
        assertTrue(retainedPaths <= 1, "retained $retainedPaths paths for a one-file difference")
        val nearFull = assertNotNull(ReferenceSourceProjection.capture(paths, paths.drop(1)))
        assertEquals(1, nearFull.retainedPositions)
        assertTrue(nearFull.matches(paths, paths.drop(1)))
        assertFalse(nearFull.matches(paths, paths.dropLast(1)))
        val one = assertNotNull(ReferenceSourceProjection.capture(paths, listOf(paths.last())))
        assertEquals(1, one.retainedPositions)
        assertTrue(one.matches(paths, listOf(paths.last())))
        assertEquals(0, assertNotNull(ReferenceSourceProjection.capture(paths, paths)).retainedPositions)
        assertEquals(0, assertNotNull(ReferenceSourceProjection.capture(paths, emptyList())).retainedPositions)
    }

    @Test
    fun `duplicate paths keep their multiplicity and unsupported order is unavailable`() {
        val a = Path.of("a")
        val b = Path.of("b")
        val c = Path.of("c")
        val model = listOf(a, b, a, c)
        val selected = listOf(a, a, c)
        val projection = assertNotNull(ReferenceSourceProjection.capture(model, selected))
        assertTrue(projection.matches(model, selected))
        assertTrue(projection.sameAs(ReferenceSourceProjection.capture(model, selected.toList())))
        assertFalse(projection.matches(model, listOf(a, b, c)))
        assertFalse(projection.matches(model, listOf(a, c)))
        assertNull(ReferenceSourceProjection.capture(model, listOf(c, b)))
        assertNull(ReferenceSourceProjection.capture(model, listOf(b, b)))
        assertNull(ReferenceSourceProjection.capture(model, listOf(Path.of("outside"))))
    }
}
