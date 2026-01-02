plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":groovy-common"))
    implementation(project(":parser:native"))
    implementation(libs.slf4j.api)

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
