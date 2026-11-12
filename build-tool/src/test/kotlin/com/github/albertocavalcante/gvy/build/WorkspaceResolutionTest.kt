// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkspaceResolutionTest {

    @Test
    fun `empty resolution has Success status and is usable`() {
        val resolution = WorkspaceResolution.empty()

        assertEquals(ResolutionStatus.Success, resolution.status)
        assertTrue(resolution.isUsable)
        assertTrue(resolution.dependencies.isEmpty())
        assertTrue(resolution.sourceDirectories.isEmpty())
    }

    @Test
    fun `failed factory creates Failed status`() {
        val resolution = WorkspaceResolution.failed(
            code = "TOOLCHAIN_PROVISIONING_FAILED",
            message = "Failed to provision toolchain",
        )

        assertFalse(resolution.isUsable)
        assertTrue(resolution.dependencies.isEmpty())
        assertTrue(resolution.sourceDirectories.isEmpty())

        val status = resolution.status
        assertTrue(status is ResolutionStatus.Failed)
        assertEquals("TOOLCHAIN_PROVISIONING_FAILED", status.code)
        assertEquals("Failed to provision toolchain", status.message)
    }

    @Test
    fun `failed factory with cause creates Failed status with cause`() {
        val cause = RuntimeException("Root cause")
        val resolution = WorkspaceResolution.failed(
            code = "DEPENDENCY_RESOLUTION_FAILED",
            message = "Resolution failed",
            cause = cause,
        )

        assertFalse(resolution.isUsable)

        val status = resolution.status
        assertTrue(status is ResolutionStatus.Failed)
        assertEquals(cause, status.cause)
    }

    @Test
    fun `resolution with Warning status is usable`() {
        val resolution = WorkspaceResolution(
            dependencies = emptyList(),
            sourceDirectories = emptyList(),
            status = ResolutionStatus.Warning("ZERO_DEPENDENCIES", "No dependencies found"),
        )

        assertTrue(resolution.status is ResolutionStatus.Warning)
        assertTrue(resolution.isUsable)
    }

    @Test
    fun `resolution with Failed status is not usable`() {
        val resolution = WorkspaceResolution(
            dependencies = emptyList(),
            sourceDirectories = emptyList(),
            status = ResolutionStatus.Failed("INIT_SCRIPT_ERROR", "Init script failed"),
        )

        assertTrue(resolution.status is ResolutionStatus.Failed)
        assertFalse(resolution.isUsable)
    }

    @Test
    fun `legacy positional constructor named copy and destructuring preserve flat behavior`() {
        val dependencies = listOf(Path.of("first.jar"), Path.of("second.jar"))
        val sources = listOf(Path.of("src", "main", "groovy"))
        val status = ResolutionStatus.Warning("PARTIAL", "Legacy workspace result")
        val resolution = WorkspaceResolution(dependencies, sources, status)
        val (actualDependencies, actualSources, actualStatus) = resolution
        val copied = resolution.copy(dependencies = dependencies.reversed())
        val positionalCopy = resolution.copy(dependencies, sources, status)

        assertEquals(dependencies, actualDependencies)
        assertEquals(sources, actualSources)
        assertSame(status, actualStatus)
        assertEquals(dependencies.reversed(), copied.dependencies)
        assertEquals(sources, copied.sourceDirectories)
        assertSame(status, copied.status)
        assertTrue(copied.isUsable)
        assertSame(TargetDependencyEvidence.Legacy, resolution.targetDependencies)
        assertSame(TargetDependencyEvidence.Legacy, copied.targetDependencies)
        assertEquals(WorkspaceResolution(dependencies, sources, status), resolution)
        assertEquals(resolution, positionalCopy)
    }

    @Test
    fun `legacy factory statuses do not establish target absence`() {
        assertSame(TargetDependencyEvidence.Legacy, WorkspaceResolution.empty().targetDependencies)
        assertSame(
            TargetDependencyEvidence.Legacy,
            WorkspaceResolution.failed("FAILED", "Unavailable").targetDependencies,
        )
    }

    @Test
    fun `scoped evidence does not alter flat dependencies status or copy defaults`() {
        val target = DependencyTarget(URI("bsp:app:test"), Path.of("app").toAbsolutePath())
        val targetArtifacts = listOf(ResolvedDependencyArtifact(target.projectDirectory.resolve("target-only.jar")))
        val evidence = TargetDependencyEvidence.Scoped(
            listOf(
                TargetDependencySnapshot(
                    target = target,
                    role = DependencyRole.TEST_RUNTIME,
                    provenance = DependencyProvenance("Build server", 4L),
                    state = TargetDependencyState.COMPLETE,
                    artifacts = targetArtifacts,
                    sourceRoots = listOf(target.projectDirectory.resolve("target-sources")),
                ),
            ),
        )
        val dependencies = listOf(Path.of("legacy.jar"))
        val resolution = WorkspaceResolution(
            dependencies = dependencies,
            sourceDirectories = emptyList(),
            status = ResolutionStatus.Failed("FAILED", "Target data does not override status"),
            targetDependencies = evidence,
        )
        val copied = resolution.copy(sourceDirectories = listOf(Path.of("src")))

        assertEquals(dependencies, resolution.dependencies)
        assertFalse(resolution.isUsable)
        assertFalse(copied.isUsable)
        assertSame(evidence, copied.targetDependencies)
        assertEquals(resolution.status, copied.status)
    }
}
