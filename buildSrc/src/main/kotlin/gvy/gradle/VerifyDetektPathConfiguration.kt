package gvy.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.TaskAction

data class DetektPathConsumer(
    @get:Input val taskPath: String,
    @get:Input val configFiles: List<String>,
    @get:Input val expectedConfigFile: String,
    @get:Input val defaultsEnabled: Boolean,
    @get:Input val reports: List<String>,
)

/** Checks actual task configuration without executing unrelated analyses or regenerating baselines. */
abstract class VerifyDetektPathConfiguration : DefaultTask() {
    @get:Nested
    abstract val consumers: ListProperty<DetektPathConsumer>

    @TaskAction
    fun verify() {
        val configured = consumers.get()
        check(configured.isNotEmpty()) { "No Detekt consumers were configured" }
        val unanchored = configured.filter { it.configFiles != listOf(it.expectedConfigFile) || !it.defaultsEnabled }
        check(unanchored.isEmpty()) {
            "Detekt consumers must use the generated path policy with defaults enabled: " +
                unanchored.joinToString { it.taskPath }
        }
        val reports = configured.flatMap { consumer -> consumer.reports.map { report -> report to consumer.taskPath } }
        val collisions = reports.groupBy { it.first }.values.filter { it.size > 1 }
        check(collisions.isEmpty()) {
            "Detekt report outputs overlap between tasks: " +
                collisions.flatten().joinToString { it.second }
        }
        logger.lifecycle("Verified path policy and unique reports for ${configured.size} Detekt consumers")
    }
}
