// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.build.DependencyProvenance
import com.github.albertocavalcante.gvy.build.DependencyRole
import com.github.albertocavalcante.gvy.build.DependencyTarget
import com.github.albertocavalcante.gvy.build.ResolvedDependencyArtifact
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.TargetDependencySnapshot
import com.github.albertocavalcante.gvy.build.TargetDependencyState
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Under scoped evidence every request asks which target a document belongs to. Canonicalising the document
 * (`Files.exists` up the ancestors, then `toRealPath`) is filesystem I/O, so it must happen once per document
 * per model, not on every hover, completion or navigation request (#1316 debt, #1235 slice 2a).
 */
class CanonicalPathCacheTest {
    private val root = Path.of("project").toAbsolutePath()
    private val document = root.resolve("src/main/groovy/A.groovy")
    private val documentCanonicalisations = AtomicInteger()
    private val canonicalise: (Path) -> Path = { path ->
        if (path == document) documentCanonicalisations.incrementAndGet()
        path.toAbsolutePath().normalize()
    }

    private val scoped = TargetDependencyEvidence.Scoped(
        listOf(
            TargetDependencySnapshot(
                target = DependencyTarget(URI.create("test:main"), root, "main"),
                role = DependencyRole.COMPILE,
                provenance = DependencyProvenance("test-producer", 1),
                state = TargetDependencyState.COMPLETE,
                artifacts = listOf(ResolvedDependencyArtifact(root.resolve("main.jar"))),
                sourceRoots = listOf(root.resolve("src/main/groovy")),
            ),
        ),
    )

    private fun model(evidence: TargetDependencyEvidence) = WorkspaceModel(
        root,
        emptyList(),
        emptyList(),
        emptyList(),
        evidence,
        canonicalise = canonicalise,
    ) { deps, roots -> "fp:$deps:$roots" }

    @Test
    fun `repeated lookups for one document on one model canonicalise it once`() {
        val workspace = model(scoped)

        repeat(5) { assertIs<TargetSelection.Selected>(workspace.compileContextFor(document).selection) }

        assertEquals(1, documentCanonicalisations.get())
    }

    @Test
    fun `legacy evidence does no filesystem lookups`() {
        val workspace = model(TargetDependencyEvidence.Legacy)
        val calls = AtomicInteger()
        val counting = WorkspaceModel(
            root,
            emptyList(),
            emptyList(),
            emptyList(),
            TargetDependencyEvidence.Legacy,
            canonicalise = {
                calls.incrementAndGet()
                it
            },
        ) { _, _ -> "fp" }

        repeat(3) {
            workspace.compileContextFor(document)
            counting.compileContextFor(document)
        }

        assertEquals(0, calls.get())
        assertEquals(0, documentCanonicalisations.get())
    }

    @Test
    fun `a new model canonicalises again`() {
        model(scoped).compileContextFor(document)
        model(scoped).compileContextFor(document)

        assertEquals(2, documentCanonicalisations.get())
    }
}
