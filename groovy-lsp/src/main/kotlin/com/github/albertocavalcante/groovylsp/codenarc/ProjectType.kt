package com.github.albertocavalcante.groovylsp.codenarc

/**
 * Represents the type of a Groovy project, which influences the ruleset selection.
 */
sealed class ProjectType {
    /**
     * Plain Groovy project without specific framework dependencies.
     */
    object PlainGroovy : ProjectType()

    /**
     * Jenkins shared library or pipeline project.
     */
    object JenkinsLibrary : ProjectType()

    /**
     * Grails web application project.
     */
    object GrailsApplication : ProjectType()

    /**
     * Gradle project with optional Spock testing framework.
     */
    data class GradleProject(val hasSpock: Boolean = false) : ProjectType()

    /**
     * Maven-based Groovy project.
     */
    object MavenProject : ProjectType()

    /**
     * Spring Boot project with Groovy.
     */
    object SpringBootProject : ProjectType()

    override fun toString(): String = when (this) {
        is PlainGroovy -> "PlainGroovy"
        is JenkinsLibrary -> "JenkinsLibrary"
        is GrailsApplication -> "GrailsApplication"
        is GradleProject -> "GradleProject(spock=$hasSpock)"
        is MavenProject -> "MavenProject"
        is SpringBootProject -> "SpringBootProject"
    }
}
