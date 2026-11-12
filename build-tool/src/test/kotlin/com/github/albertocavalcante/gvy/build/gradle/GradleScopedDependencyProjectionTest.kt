// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

import com.github.albertocavalcante.gvy.build.DependencyProvenance
import com.github.albertocavalcante.gvy.build.DependencyRole
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.TargetDependencyState
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GradleScopedDependencyProjectionTest {
    private val directory = Path.of("model-root").toAbsolutePath().toString()
    private val provenance = DependencyProvenance("explicit-test-observation", 17)

    @Test
    fun `preserves target scope order repeated entries exact versions and caller provenance`() {
        val module = Coordinates("org.spockframework", "spock-core", "2.4-M4-groovy-4.0")
        val entries = listOf(Entry(path("local")), Entry(path("binary"), listOf(module)), Entry(path("local")))
        val source = Target(artifacts = entries)
        val result = scoped(Model(targets = listOf(source, Target(sourceSet = "integrationTest"))))

        val snapshot = result.snapshots.first()
        assertSame(provenance, snapshot.provenance)
        assertEquals(DependencyRole.COMPILE, snapshot.role)
        assertEquals(TargetDependencyState.COMPLETE, snapshot.state)
        assertEquals(listOf("test", "integrationTest"), result.snapshots.map { it.target.sourceSet })
        assertEquals(entries.map { Path.of(requireNotNull(it.path)) }, snapshot.artifacts.map { it.path })
        assertNull(snapshot.artifacts.first().coordinates)
        assertEquals("2.4-M4-groovy-4.0", snapshot.artifacts[1].coordinates?.version)
        assertNull(snapshot.artifacts[1].coordinates?.classifier)
        assertEquals(listOf(Path.of(path("custom-groovy"))), snapshot.sourceRoots)
    }

    @Test
    fun `conflicting coordinate candidates remain unknown while identical candidates retain exact facts`() {
        val first = Coordinates("group", "module", "1")
        val second = Coordinates("group", "module", "2")
        val entries = listOf(Entry(path("conflict"), listOf(first, second)), Entry(path("same"), listOf(first, first)))

        val result = scoped(Model(targets = listOf(Target(artifacts = entries)))).snapshots.single()

        assertNull(result.target.configuration)
        assertNull(result.artifacts.first().coordinates)
        assertEquals("1", result.artifacts.last().coordinates?.version)
    }

    @Test
    fun `retains unresolved siblings and supported empty observations without inventing freshness`() {
        val result =
            scoped(
                Model(
                    targets = listOf(
                        Target(state = "UNRESOLVED"),
                        Target(
                            sourceSet = "main",
                            configuration = "compileClasspath",
                            enrichmentConfiguration = "compileClasspath",
                        ),
                    ),
                ),
            )

        assertEquals(
            listOf(TargetDependencyState.UNRESOLVED, TargetDependencyState.COMPLETE),
            result.snapshots.map {
                it.state
            },
        )
        assertEquals("compileClasspath", result.snapshots.last().target.configuration)
        assertSame(provenance, result.snapshots.last().provenance)
        assertTrue(scoped(Model(targets = emptyList())).snapshots.isEmpty())
    }

    @Test
    fun `encodes build project and source set identity without delimiter collisions`() {
        val models = listOf(
            Model(targets = listOf(Target(projectPath = ":a#b", sourceSet = "c / Δ"))),
            Model(targets = listOf(Target(projectPath = ":a", sourceSet = "b#c / Δ"))),
            Model(
                buildDirectory = path("other#build"),
                targets = listOf(Target(projectPath = ":a", sourceSet = "b#c / Δ")),
            ),
        )
        val identities = models.map { scoped(it).snapshots.single().target.targetId }

        assertEquals(3, identities.distinct().size)
        assertTrue(identities.all { it.isAbsolute && ' ' !in it.toASCIIString() })
        assertNotEquals(identities[0], identities[1])
    }

    @Test
    fun `distinguishes reported project paths that share a project directory`() {
        val result = scoped(Model(targets = listOf(Target(projectPath = ":one"), Target(projectPath = ":two"))))

        assertEquals(1, result.snapshots.map { it.target.projectDirectory }.distinct().size)
        assertEquals(2, result.snapshots.map { it.target.targetId }.distinct().size)
    }

    @Test
    fun `rejects malformed transport rather than dropping entries into empty evidence`() {
        val invalidEntry = Entry(path("jar"), listOf(Coordinates(null, "module", "1")))
        val invalidTargets = listOf(
            Target(projectDirectory = "relative"),
            Target(sourceSet = null),
            Target(state = "PARTIALLY_TRUSTED"),
            Target(sourceRoots = listOf(null)),
            Target(artifacts = listOf(null)),
            Target(artifacts = listOf(Entry("relative"))),
            Target(artifacts = listOf(Entry(path("jar"), null))),
            Target(artifacts = listOf(invalidEntry)),
        )
        val invalid = listOf(
            Model(buildDirectory = null),
            Model(buildDirectory = "relative"),
            Model(targets = null),
            Model(targets = listOf(null)),
            Model(targets = listOf(Target(), Target())),
        ) + invalidTargets.map { Model(targets = listOf(it)) }
        invalid.forEach { model -> assertFailsWith<IllegalArgumentException> { scoped(model) } }
    }

    private fun scoped(model: Model) = assertIs<TargetDependencyEvidence.Scoped>(
        GradleScopedDependencyProjection.project(model, provenance),
    )

    private fun path(name: String) = Path.of(directory).resolve(name).toString()

    private data class Model(
        override val buildDirectory: String? = Path.of("model-root").toAbsolutePath().toString(),
        override val targets: List<GradleSourceSetModel?>? = listOf(Target()),
    ) : GradleScopedDependencyModel

    private data class Target(
        override val projectPath: String? = ":project",
        override val projectDirectory: String? = Path.of("model-root").toAbsolutePath().toString(),
        override val sourceSet: String? = "test",
        override val configuration: String? = null,
        override val enrichmentConfiguration: String? = "testCompileClasspath",
        override val state: String? = "COMPLETE",
        override val sourceRoots: List<String?>? =
            listOf(Path.of("model-root/custom-groovy").toAbsolutePath().toString()),
        override val artifacts: List<GradleClasspathEntryModel?>? = emptyList(),
    ) : GradleSourceSetModel

    private data class Entry(
        override val path: String?,
        override val coordinateCandidates: List<GradleModuleCoordinatesModel?>? = emptyList(),
    ) : GradleClasspathEntryModel

    private data class Coordinates(
        override val group: String?,
        override val module: String?,
        override val version: String?,
    ) : GradleModuleCoordinatesModel
}
