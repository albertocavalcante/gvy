package com.github.groovylsp.bsp.maven

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Utility for locating test resources in both Gradle and Bazel environments.
 *
 * In Gradle, resources are at: src/test/resources/...
 * In Bazel, resources are in runfiles at: bsp/maven-bsp/src/test/resources/...
 */
object TestResources {

    private const val MODULE_PATH = "bsp/maven-bsp"
    private const val FIXTURES_DIR = "src/test/resources/fixtures"

    /**
     * Get the path to a test fixture directory.
     * Works in both Gradle (relative path) and Bazel (runfiles) environments.
     *
     * @param fixtureName The fixture directory name under src/test/resources/fixtures/
     * @return Path to the fixture directory
     */
    fun getFixturePath(fixtureName: String): Path {
        val resourcePath = "$FIXTURES_DIR/$fixtureName"

        val candidates = listOf(
            // Gradle: running from module directory
            resourcePath,
            // Gradle: running from project root
            "$MODULE_PATH/$resourcePath",
            // Bazel: runfiles with workspace prefix (_main is the default workspace name)
            System.getenv("TEST_SRCDIR")?.let { "$it/_main/$MODULE_PATH/$resourcePath" },
            // Bazel: runfiles without workspace prefix
            System.getenv("TEST_SRCDIR")?.let { "$it/$MODULE_PATH/$resourcePath" },
        )

        for (candidate in candidates.filterNotNull()) {
            val path = Paths.get(candidate)
            if (Files.exists(path)) {
                return path.toAbsolutePath()
            }
        }

        throw IllegalStateException(
            "Test fixture not found: $fixtureName. Searched: ${candidates.filterNotNull().joinToString()}",
        )
    }

    /**
     * Get the path to the single-module fixture.
     */
    fun getSingleModuleFixture(): Path = getFixturePath("single-module")

    /**
     * Get the path to the multi-module fixture.
     */
    fun getMultiModuleFixture(): Path = getFixturePath("multi-module")

    /**
     * Get the path to the jenkins-style fixture.
     */
    fun getJenkinsStyleFixture(): Path = getFixturePath("jenkins-style")
}
