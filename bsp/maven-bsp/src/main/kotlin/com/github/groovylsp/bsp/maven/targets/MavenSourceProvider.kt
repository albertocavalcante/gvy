package com.github.groovylsp.bsp.maven.targets

import ch.epfl.scala.bsp4j.SourceItem
import ch.epfl.scala.bsp4j.SourceItemKind
import ch.epfl.scala.bsp4j.SourcesItem
import ch.epfl.scala.bsp4j.SourcesResult
import com.github.groovylsp.bsp.maven.workspace.MavenModuleInfo
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Maps source directories to BSP build targets.
 */
class MavenSourceProvider {

    companion object {
        // Standard Maven source directories
        private val MAIN_SOURCE_DIRS = listOf(
            "src/main/java",
            "src/main/groovy",
            "src/main/kotlin",
        )
        private val TEST_SOURCE_DIRS = listOf(
            "src/test/java",
            "src/test/groovy",
            "src/test/kotlin",
        )
        private val GENERATED_SOURCE_DIRS = listOf(
            "target/generated-sources",
        )
    }

    private val targetProvider = MavenBuildTargetProvider()

    /**
     * Gets source directories for a module's main target.
     */
    fun getMainSources(module: MavenModuleInfo): SourcesItem {
        val targetId = targetProvider.buildTargetId(module, MavenBuildTargetProvider.Scope.MAIN)
        val baseDir = module.baseDir

        val sources = mutableListOf<SourceItem>()

        // Custom source directory from pom.xml
        module.sourceDirectory?.let { customDir ->
            val path = baseDir.resolve(customDir)
            if (path.exists() && path.isDirectory()) {
                sources.add(createSourceItem(path, generated = false))
            }
        }

        // Standard Maven directories (if custom not specified or in addition)
        if (module.sourceDirectory == null) {
            MAIN_SOURCE_DIRS.forEach { dir ->
                val path = baseDir.resolve(dir)
                if (path.exists() && path.isDirectory()) {
                    sources.add(createSourceItem(path, generated = false))
                }
            }
        }

        // Generated sources
        GENERATED_SOURCE_DIRS.forEach { dir ->
            val path = baseDir.resolve(dir)
            if (path.exists() && path.isDirectory()) {
                // Add each subdirectory as a generated source
                path.toFile().listFiles()?.filter { it.isDirectory }?.forEach { genDir ->
                    sources.add(createSourceItem(genDir.toPath(), generated = true))
                }
            }
        }

        return SourcesItem(targetId, sources)
    }

    /**
     * Gets source directories for a module's test target.
     */
    fun getTestSources(module: MavenModuleInfo): SourcesItem {
        val targetId = targetProvider.buildTargetId(module, MavenBuildTargetProvider.Scope.TEST)
        val baseDir = module.baseDir

        val sources = mutableListOf<SourceItem>()

        // Custom test source directory from pom.xml
        module.testSourceDirectory?.let { customDir ->
            val path = baseDir.resolve(customDir)
            if (path.exists() && path.isDirectory()) {
                sources.add(createSourceItem(path, generated = false))
            }
        }

        // Standard Maven test directories (if custom not specified or in addition)
        if (module.testSourceDirectory == null) {
            TEST_SOURCE_DIRS.forEach { dir ->
                val path = baseDir.resolve(dir)
                if (path.exists() && path.isDirectory()) {
                    sources.add(createSourceItem(path, generated = false))
                }
            }
        }

        // Generated test sources
        val genTestPath = baseDir.resolve("target/generated-test-sources")
        if (genTestPath.exists() && genTestPath.isDirectory()) {
            genTestPath.toFile().listFiles()?.filter { it.isDirectory }?.forEach { genDir ->
                sources.add(createSourceItem(genDir.toPath(), generated = true))
            }
        }

        return SourcesItem(targetId, sources)
    }

    /**
     * Gets all source directories for a list of modules.
     */
    fun getSources(modules: List<MavenModuleInfo>): SourcesResult {
        val items = modules.flatMap { module ->
            listOf(getMainSources(module), getTestSources(module))
        }
        return SourcesResult(items)
    }

    private fun createSourceItem(path: Path, generated: Boolean): SourceItem = SourceItem(
        path.toUri().toString(),
        SourceItemKind.DIRECTORY,
        generated,
    )
}
