plugins {
    kotlin("jvm")
}

dependencies {
    // Zero external dependencies for the core markdown module
    
    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
