plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
        // Gradle repository for Tooling API
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
    }
}

rootProject.name = "groovy-lsp-root"

include("groovy-formatter")
include("groovy-parser")
include("groovy-common")
include("groovy-lsp")
include("tests")
include("groovy-diagnostics:api")
include("groovy-diagnostics:codenarc")
include("groovy-jenkins")
include("groovy-gdsl")
include("groovy-build-tool")
include("groovy-spock")
include("groovy-testing")
include("groovy-junit")
include("jupyter:kernel-core")
include("jupyter:kernels:groovy")
include("jupyter:kernels:jenkins")
include("groovy-repl")
include("tools:jenkins-extractor")

// Standalone GroovyParser library (JavaParser-inspired API)
include("groovyparser-core")