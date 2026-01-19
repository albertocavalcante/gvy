plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
        // Google Maven repository for androidx dependencies (required by Compose)
        google()
        // Gradle repository for Tooling API
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
    }
}

rootProject.name = "groovy-lsp-root"

include("groovy-formatter")
include("markdown")
include("parser:api")
include("parser:native")
include("parser:core")
include("parser:rewrite")
include("common")
include("groovy-lsp")
include("indexer:core")
include("indexer:scip")
include("indexer:lsif")
include("tests")
include("groovy-diagnostics:api")
include("groovy-diagnostics:codenarc")
include("groovy-diagnostics:sarif")
include("groovy-jenkins")
include("groovy-gdsl")
include("groovy-build-tool")
include("groovy-spock")
include("groovy-testing")
include("groovy-test-utils")
include("groovy-junit")
include("jupyter:kernel-core")
include("jupyter:kernels:groovy")
include("jupyter:kernels:jenkins")
include("repl")
include("tools:jenkins-extractor")
include("viz:ast-model")
include("viz:desktop")
include("semantics:core")
include("semantics:dsl")
// Use non-colliding name to avoid conflict with parser:native
include("semantics-native")
project(":semantics-native").projectDir = file("semantics/native")
// OpenRewrite adapter for semantics layer
include("semantics-openrewrite")
project(":semantics-openrewrite").projectDir = file("semantics/openrewrite")

// OpenRewrite CodeNarc auto-fix recipes
include("refactor:openrewrite:rewrite-codenarc")
project(":refactor:openrewrite:rewrite-codenarc").projectDir = file("refactor/openrewrite/rewrite-codenarc")

// BSP (Build Server Protocol) modules - reusable infrastructure
include("bsp:bsp-core")
project(":bsp:bsp-core").projectDir = file("bsp/bsp-core")

// Maven BSP Server
include("bsp:maven-bsp")
project(":bsp:maven-bsp").projectDir = file("bsp/maven-bsp")

// Schemas module - Wire protobuf definitions for type sharing
include("schemas")

// Reports modules - shared utilities for report parsing
include("reports:api")
project(":reports:api").projectDir = file("reports/api")
include("reports:coverage")
project(":reports:coverage").projectDir = file("reports/coverage")
include("reports:results")
project(":reports:results").projectDir = file("reports/results")
