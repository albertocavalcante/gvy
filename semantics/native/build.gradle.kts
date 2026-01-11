plugins {
    kotlin("jvm")
}

group = "com.github.albertocavalcante"
version = rootProject.version

dependencies {
    implementation(project(":semantics:core"))
    implementation(project(":parser:core")) // For TypeSolver
    implementation(project(":groovy-common")) // For FQN and type utilities

    // Native Groovy AST
    implementation(libs.groovy.core)

    testImplementation(kotlin("test"))
    testImplementation(project(":parser:native"))
    testImplementation(project(":parser:api"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // kotlin-logging in transitive deps needs SLF4J impl at test runtime
    testRuntimeOnly(libs.logback.classic)
}

tasks.test {
    useJUnitPlatform()
}
