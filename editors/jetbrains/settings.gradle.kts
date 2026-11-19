// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

pluginManagement {
    repositories {
        // Mirror first: the Plugin Portal answers 404 for Central-proxied jars when Central throttles. See #1908.
        maven {
            name = "MavenCentralMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
            allowInsecureContinueWhenDisabled = true
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "gvy-jetbrains"
