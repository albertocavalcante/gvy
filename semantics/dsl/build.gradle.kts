plugins {
    kotlin("jvm")
}

tasks.jar {
    archiveBaseName.set("groovy-semantics-dsl")
}

dependencies {
    // Dependency on semantics:core
    api(project(":semantics:core"))

    // Groovy AST for DSL matching
    implementation(libs.groovy.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // kotlin-logging in transitive deps needs SLF4J impl at test runtime
    testRuntimeOnly(libs.logback.classic)
}

tasks.test {
    useJUnitPlatform()
}
