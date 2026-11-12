// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TargetDependencyEvidenceTest {
    private val projectA = Path.of("workspace", "a").toAbsolutePath()
    private val projectB = Path.of("workspace", "b").toAbsolutePath()
    private val spock23 = artifact("spock-2.3.jar", "2.3-groovy-4.0")
    private val spock24 = artifact("spock-2.4.jar", "2.4-groovy-4.0")

    @Test
    fun `scoped records preserve project configuration role versions and artifact order`() {
        val compile = snapshot(
            target = DependencyTarget(URI("gradle:a:test"), projectA, "test", "testCompileClasspath"),
            artifacts = listOf(spock23, spock24, spock23),
        )
        val runtime = snapshot(
            target = DependencyTarget(URI("gradle:a:test"), projectA, "test", "testRuntimeClasspath"),
            role = DependencyRole.TEST_RUNTIME,
            artifacts = listOf(spock24, spock23),
        )
        val custom = snapshot(
            target = DependencyTarget(URI("gradle:b:integrationTest"), projectB, "integrationTest", "itCompile"),
            artifacts = listOf(spock24),
        )

        val evidence = TargetDependencyEvidence.Scoped(listOf(compile, runtime, custom))

        assertEquals(listOf(compile, runtime, custom), evidence.snapshots)
        assertEquals(listOf(spock23, spock24, spock23), compile.artifacts)
        assertEquals(listOf(spock24, spock23), runtime.artifacts)
        assertEquals(projectA, compile.target.projectDirectory)
        assertEquals(projectB, custom.target.projectDirectory)
        assertNotEquals(compile.target, runtime.target)
        assertEquals("test", runtime.target.sourceSet)
        assertEquals("integrationTest", custom.target.sourceSet)
        assertEquals(DependencyRole.COMPILE, compile.role)
        assertEquals(DependencyRole.TEST_RUNTIME, runtime.role)
        assertEquals("2.3-groovy-4.0", compile.artifacts.first().coordinates?.version)
        assertEquals("2.4-groovy-4.0", custom.artifacts.single().coordinates?.version)
    }

    @Test
    fun `opaque BSP target identity does not invent source set or configuration names`() {
        val target = DependencyTarget(URI("maven:example:app:test"), projectA)
        val localClasses = ResolvedDependencyArtifact(projectA.resolve("build/classes"))
        val record = snapshot(target = target, artifacts = listOf(localClasses))

        assertTrue(target.targetId.isOpaque)
        assertNull(target.sourceSet)
        assertNull(target.configuration)
        assertNull(record.artifacts.single().coordinates)
        assertEquals(projectA.resolve("build/classes"), record.artifacts.single().path)
    }

    @Test
    fun `complete empty target and legacy or missing target evidence remain distinct`() {
        val complete = snapshot(artifacts = emptyList())
        val legacy = WorkspaceResolution.empty()
        val noTargetRecords = TargetDependencyEvidence.Scoped(emptyList())

        assertEquals(TargetDependencyState.COMPLETE, complete.state)
        assertTrue(complete.artifacts.isEmpty())
        assertSame(TargetDependencyEvidence.Legacy, legacy.targetDependencies)
        assertTrue(noTargetRecords.snapshots.isEmpty())
    }

    @Test
    fun `incomplete unresolved ambiguous and stale records retain their uncertainty and provenance`() {
        val states = listOf(
            TargetDependencyState.INCOMPLETE,
            TargetDependencyState.UNRESOLVED,
            TargetDependencyState.AMBIGUOUS,
            TargetDependencyState.STALE,
        )
        val records = states.map { state -> snapshot(state = state, artifacts = emptyList()) }

        assertEquals(states, records.map { it.state })
        assertEquals(List(states.size) { "Gradle Tooling API" }, records.map { it.provenance.producer })
        assertEquals(List(states.size) { 7L }, records.map { it.provenance.generation })
        assertTrue(records.all { it.artifacts.isEmpty() })
    }

    @Test
    fun `stale and ambiguous records preserve old or conflicting facts without selecting a version`() {
        val stale = snapshot(state = TargetDependencyState.STALE, artifacts = listOf(spock23))
        val ambiguous = snapshot(state = TargetDependencyState.AMBIGUOUS, artifacts = listOf(spock24, spock23))

        assertEquals(listOf(spock23), stale.artifacts)
        assertEquals(listOf(spock24, spock23), ambiguous.artifacts)
        assertEquals(7L, stale.provenance.generation)
    }

    @Test
    fun `snapshots detach producer collection aliases and prevent mutation through Java lists`() {
        val artifacts = mutableListOf(spock23, spock24, spock23)
        val roots = mutableListOf(projectA.resolve("src/test/groovy"))
        val record = snapshot(artifacts = artifacts, sourceRoots = roots)
        val records = mutableListOf(record)
        val evidence = TargetDependencyEvidence.Scoped(records)

        artifacts.clear()
        roots.clear()
        records.clear()

        assertEquals(listOf(spock23, spock24, spock23), record.artifacts)
        assertEquals(listOf(projectA.resolve("src/test/groovy")), record.sourceRoots)
        assertEquals(listOf(record), evidence.snapshots)
        assertFailsWith<UnsupportedOperationException> { (record.artifacts as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (record.sourceRoots as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (evidence.snapshots as MutableList<*>).clear() }
    }

    @Test
    fun `artifact coordinates preserve exact classifier and extension without filename parsing`() {
        val coordinates = ResolvedArtifactCoordinates("example", "engine", "2.4-M5-groovy-4.0", "tests", "zip")
        val resolved = ResolvedDependencyArtifact(projectA.resolve("unrelated-name.bin"), coordinates)

        assertSame(coordinates, resolved.coordinates)
        assertEquals("tests", resolved.coordinates?.classifier)
        assertEquals("zip", resolved.coordinates?.extension)
        assertEquals("2.4-M5-groovy-4.0", resolved.coordinates?.version)
    }

    @Test
    fun `target ownership and producer generation require explicit identities`() {
        assertFailsWith<IllegalArgumentException> { DependencyTarget(URI("relative-target"), projectA) }
        assertFailsWith<IllegalArgumentException> {
            DependencyTarget(URI("maven:example:app"), Path.of("relative-project"))
        }
        assertFailsWith<IllegalArgumentException> {
            DependencyProvenance("Gradle Tooling API", -1L)
        }
        assertFailsWith<IllegalArgumentException> {
            DependencyProvenance(" ", 0L)
        }
    }

    private fun artifact(fileName: String, version: String): ResolvedDependencyArtifact = ResolvedDependencyArtifact(
        projectA.resolve(fileName),
        ResolvedArtifactCoordinates("org.spockframework", "spock-core", version),
    )

    private fun snapshot(
        target: DependencyTarget = DependencyTarget(URI("gradle:a:test"), projectA, "test", "testCompileClasspath"),
        role: DependencyRole = DependencyRole.COMPILE,
        state: TargetDependencyState = TargetDependencyState.COMPLETE,
        artifacts: List<ResolvedDependencyArtifact> = listOf(spock23),
        sourceRoots: List<Path> = listOf(projectA.resolve("src/test/groovy")),
    ): TargetDependencySnapshot = TargetDependencySnapshot(
        target = target,
        role = role,
        provenance = DependencyProvenance("Gradle Tooling API", 7L),
        state = state,
        artifacts = artifacts,
        sourceRoots = sourceRoots,
    )
}
