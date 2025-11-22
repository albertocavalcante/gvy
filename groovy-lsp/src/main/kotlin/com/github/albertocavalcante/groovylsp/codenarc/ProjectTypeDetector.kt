package com.github.albertocavalcante.groovylsp.codenarc

import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Interface for detecting the type of a project based on its workspace structure.
 */
interface ProjectTypeDetector {
    /**
     * Detects the project type from the workspace root.
     *
     * @param workspaceRoot The root directory of the workspace
     * @return The detected project type
     */
    fun detect(workspaceRoot: Path): ProjectType
}

/**
 * Default implementation of ProjectTypeDetector that examines common project indicators.
 */
@Suppress("TooGenericExceptionCaught") // Project detection needs robust error handling for file system access
class DefaultProjectTypeDetector : ProjectTypeDetector {

    companion object {
        private val logger = LoggerFactory.getLogger(DefaultProjectTypeDetector::class.java)

        // Jenkins project indicators
        private val jenkinsIndicators = listOf(
            "Jenkinsfile",
            "vars", // Jenkins shared library
            "resources", // Jenkins shared library resources
            "src/com/", // Common Jenkins shared library structure
            "src/org/", // Common Jenkins shared library structure
        )

        // Spring Boot indicators
        private val springBootIndicators = listOf(
            "src/main/groovy",
            "src/main/resources/application.properties",
            "src/main/resources/application.yml",
        )
    }

    override fun detect(workspaceRoot: Path): ProjectType {
        logger.debug("Detecting project type for workspace: $workspaceRoot")

        val result = when {
            isJenkinsProject(workspaceRoot) -> {
                logger.info("Detected Jenkins project: $workspaceRoot")
                ProjectType.JenkinsLibrary
            }
            isGradleProject(workspaceRoot) -> {
                val hasSpock = hasSpockTesting(workspaceRoot)
                logger.info("Detected Gradle project: $workspaceRoot (spock=$hasSpock)")
                ProjectType.GradleProject(hasSpock)
            }
            isGrailsProject(workspaceRoot) -> {
                logger.info("Detected Grails project: $workspaceRoot")
                ProjectType.GrailsApplication
            }
            isSpringBootProject(workspaceRoot) -> {
                logger.info("Detected Spring Boot project: $workspaceRoot")
                ProjectType.SpringBootProject
            }
            isMavenProject(workspaceRoot) -> {
                logger.info("Detected Maven project: $workspaceRoot")
                ProjectType.MavenProject
            }
            else -> {
                logger.info("No specific project type detected, using plain Groovy: $workspaceRoot")
                ProjectType.PlainGroovy
            }
        }

        return result
    }

    /**
     * Checks if the workspace appears to be a Jenkins project.
     */
    private fun isJenkinsProject(workspaceRoot: Path): Boolean = jenkinsIndicators.any { indicator ->
        val path = workspaceRoot.resolve(indicator)
        path.exists() && (path.isDirectory() || path.name == "Jenkinsfile")
    }

    /**
     * Checks if the workspace appears to be a Grails project.
     */
    private fun isGrailsProject(workspaceRoot: Path): Boolean {
        // Grails-app directory is the strongest indicator
        if (workspaceRoot.resolve("grails-app").exists()) {
            return true
        }

        // Check for Grails-specific files (must have grails.gradle or specific Grails markers)
        val hasGrailsGradle = workspaceRoot.resolve("grails.gradle").exists()
        if (hasGrailsGradle) {
            return true
        }

        // Additional check: look for Grails in build.gradle content
        val buildFile = workspaceRoot.resolve("build.gradle")
        if (buildFile.exists()) {
            try {
                val content = buildFile.readText()
                if (content.contains("grails") &&
                    (content.contains("org.grails") || content.contains("grails-core"))
                ) {
                    return true
                }
            } catch (e: Exception) {
                logger.debug("Failed to read build.gradle for Grails detection", e)
            }
        }

        return false
    }

    /**
     * Checks if the workspace appears to be a Spring Boot project.
     */
    private fun isSpringBootProject(workspaceRoot: Path): Boolean {
        val buildFile = workspaceRoot.resolve("build.gradle")
        if (buildFile.exists()) {
            try {
                val content = buildFile.readText()
                if (content.contains("spring-boot") || content.contains("org.springframework.boot")) {
                    return true
                }
            } catch (e: Exception) {
                logger.debug("Failed to read build.gradle for Spring Boot detection", e)
            }
        }

        val pomFile = workspaceRoot.resolve("pom.xml")
        if (pomFile.exists()) {
            try {
                val content = pomFile.readText()
                if (content.contains("spring-boot") || content.contains("org.springframework.boot")) {
                    return true
                }
            } catch (e: Exception) {
                logger.debug("Failed to read pom.xml for Spring Boot detection", e)
            }
        }

        return springBootIndicators.any { indicator ->
            workspaceRoot.resolve(indicator).exists()
        }
    }

    /**
     * Checks if the workspace is a Gradle project.
     */
    private fun isGradleProject(workspaceRoot: Path): Boolean = workspaceRoot.resolve("build.gradle").exists() ||
        workspaceRoot.resolve("build.gradle.kts").exists() ||
        workspaceRoot.resolve("gradle").exists()

    /**
     * Checks if the workspace is a Maven project.
     */
    private fun isMavenProject(workspaceRoot: Path): Boolean = workspaceRoot.resolve("pom.xml").exists()

    /**
     * Checks if the project uses Spock testing framework.
     */
    private fun hasSpockTesting(workspaceRoot: Path): Boolean {
        val buildFile = workspaceRoot.resolve("build.gradle")
        if (buildFile.exists()) {
            try {
                val content = buildFile.readText()
                if (content.contains("spock-core") || content.contains("org.spockframework")) {
                    return true
                }
            } catch (e: Exception) {
                logger.debug("Failed to read build.gradle for Spock detection", e)
            }
        }

        // Check for Spock specs in the test directory
        val testDir = workspaceRoot.resolve("src/test/groovy")
        if (testDir.exists()) {
            try {
                return testDir.toFile().walk()
                    .filter { it.name.endsWith("Spec.groovy") || it.name.endsWith("Test.groovy") }
                    .any { file ->
                        try {
                            file.readText().contains("extends Specification") ||
                                file.readText().contains("import spock.")
                        } catch (e: Exception) {
                            logger.debug("Failed to read file for Spock detection: ${file.name}", e)
                            false
                        }
                    }
            } catch (e: Exception) {
                logger.debug("Failed to scan test directory for Spock specs", e)
            }
        }

        return false
    }
}
