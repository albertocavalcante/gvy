package com.github.albertocavalcante.gvy.gls.gradle

import com.github.albertocavalcante.gvy.build.BuildTool
import com.github.albertocavalcante.gvy.build.DependencyProvenance
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.gradle.GradleBuildTool
import com.github.albertocavalcante.gvy.build.gradle.GradleScopedDependencyProducer
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Observes Gradle source-set classpaths with [GradleScopedDependencyProducer] (#1217) for Gradle
 * workspaces; every other tool keeps Legacy evidence. This runs one more Gradle Tooling API operation
 * per resolution attempt, so it is opt-in (`groovy.gradle.targetClasspaths.enabled`, machine-scoped).
 */
class GradleTargetObserver(
    private val producer: GradleScopedDependencyProducer,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : TargetEvidenceObserver {
    override suspend fun observe(tool: BuildTool, workspaceRoot: Path, generation: Long): TargetDependencyEvidence =
        if (tool is GradleBuildTool) {
            producer.resolve(workspaceRoot, DependencyProvenance(PRODUCER, generation), timeout)
        } else {
            TargetDependencyEvidence.Legacy
        }

    companion object {
        /** The provenance producer name; generations are this manager's dependency attempts. */
        const val PRODUCER = "gradle-scoped-tooling-api"
        val DEFAULT_TIMEOUT: Duration = 2.minutes
    }
}
