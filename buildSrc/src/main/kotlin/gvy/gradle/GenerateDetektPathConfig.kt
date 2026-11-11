package gvy.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** The absolute root is an input because this output is intentionally specific to its checkout. */
@CacheableTask
abstract class GenerateDetektPathConfig : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val customConfig: RegularFileProperty

    @get:Classpath
    abstract val toolClasspath: ConfigurableFileCollection

    @get:Input
    abstract val repositoryRoots: ListProperty<String>

    @get:Input
    abstract val consumerClasspaths: MapProperty<String, List<String>>

    @get:OutputFile
    abstract val outputConfig: RegularFileProperty

    @TaskAction
    fun generate() {
        require(consumerClasspaths.get().isNotEmpty()) {
            "Run a Detekt check, autocorrect or baseline task to generate its path configuration"
        }
        require(consumerClasspaths.get().values.distinct().size == 1) {
            "Detekt consumers in one project must share their tool and plugin classpaths: " +
                consumerClasspaths.get().keys.joinToString()
        }
        val generated = DetektPathConfig.overlay(
            DetektPathConfig.defaults(toolClasspath.files),
            customConfig.get().asFile.readText(),
            repositoryRoots.get(),
        )
        val output = outputConfig.get().asFile
        output.parentFile.mkdirs()
        output.writeText(generated)
    }
}
