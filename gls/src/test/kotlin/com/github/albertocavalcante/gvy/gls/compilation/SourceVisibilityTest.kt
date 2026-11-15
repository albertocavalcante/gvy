// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.build.DependencyProvenance
import com.github.albertocavalcante.gvy.build.DependencyRole
import com.github.albertocavalcante.gvy.build.DependencyTarget
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.TargetDependencySnapshot
import com.github.albertocavalcante.gvy.build.TargetDependencyState
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SourceVisibilityTest {
    private val root = Path.of("visibility-project").toAbsolutePath()
    private val main = root.resolve("src/main/groovy")
    private val test = root.resolve("src/test/groovy")
    private val use = test.resolve("Use.groovy")
    private val dependency = main.resolve("Main.groovy")
    private val excluded = main.resolve("Broken.groovy")

    private fun target(id: String, dir: Path, files: List<Path>?) = TargetDependencySnapshot(
        DependencyTarget(URI.create("test:$id"), root, id),
        DependencyRole.COMPILE,
        DependencyProvenance("test", 1),
        TargetDependencyState.COMPLETE,
        emptyList(),
        listOf(dir),
        files,
    )

    private fun model(complete: Boolean, mainFiles: List<Path>? = listOf(dependency)) = WorkspaceModel(
        root,
        emptyList(),
        listOf(main, test),
        listOf(use, dependency, excluded),
        TargetDependencyEvidence.Scoped(
            listOf(target("main", main, mainFiles), target("test", test, listOf(use))),
            enumerationComplete = complete,
        ),
    ) { _, _ -> "fingerprint" }

    @Test
    fun `selected source retains unknown dependency but excludes proven excluded file`() {
        val scope = model(true).sourceScopeFor(use)
        assertEquals(SourceMode.SELECTED, scope.mode)
        assertEquals(SourceCandidate.VISIBLE, scope.classify(use))
        assertEquals(SourceCandidate.UNKNOWN, scope.classify(dependency))
        assertEquals(SourceCandidate.EXCLUDED, scope.classify(excluded))
        assertFalse(scope.visibilityKnown)
    }

    @Test
    fun `incomplete enumeration cannot prove exclusion`() {
        assertEquals(SourceCandidate.UNKNOWN, model(false).sourceScopeFor(use).classify(excluded))
        assertEquals(SourceCandidate.UNKNOWN, model(true, null).sourceScopeFor(use).classify(excluded))
    }

    @Test
    fun `unmodeled source is independent when enumeration is complete`() {
        val scope = model(true).sourceScopeFor(test.resolve("New.groovy"))
        assertEquals(SourceMode.INDEPENDENT, scope.mode)
        assertEquals(SourceCandidate.EXCLUDED, scope.classify(dependency))
        assertEquals(false, model(true).compileContextFor(test.resolve("New.groovy")).sourceMembershipKnown)
    }

    @Test
    fun `ambiguous owners still reject positively excluded sources`() {
        val shared = main.resolve("Shared.groovy")
        val sibling = main.resolve("Sibling.groovy")
        val evidence = TargetDependencyEvidence.Scoped(
            listOf(target("one", main, listOf(shared)), target("two", main, listOf(shared, sibling))),
            enumerationComplete = true,
        )
        val model = WorkspaceModel(root, emptyList(), listOf(main), listOf(shared, sibling, excluded), evidence) {
                _,
                _,
            ->
            "fingerprint"
        }
        val scope = model.sourceScopeFor(shared)
        assertEquals(SourceMode.LEGACY_UNKNOWN, scope.mode)
        assertEquals(SourceCandidate.UNKNOWN, scope.classify(sibling))
        assertEquals(SourceCandidate.EXCLUDED, scope.classify(excluded))
        assertEquals(listOf(shared, sibling), model.sourcesFor(shared, model.sources))
        assertFalse(requireNotNull(model.sourceFilterFor(shared))(excluded))
    }
}
