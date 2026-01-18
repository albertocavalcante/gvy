plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":groovy-common"))
    implementation(project(":parser:native"))

    // Logging
    // kotlin-logging provides the API; SLF4J implementation needed at runtime.
    // Tests initialize classes with loggers, requiring logback on test classpath.
    implementation(libs.kotlin.logging)
    testRuntimeOnly(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.spock.core) // Needed for classpath-aware detection tests (spock.lang.Specification)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Detekt formatting
    detektPlugins(libs.detekt.formatting)
}

tasks.test {
    useJUnitPlatform()
}
