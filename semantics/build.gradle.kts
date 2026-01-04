plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":groovy-common"))

    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
