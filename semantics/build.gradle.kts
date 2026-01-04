plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":groovy-common"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
