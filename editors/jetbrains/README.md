<!-- SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# JetBrains plugin foundation

This standalone build is an inert foundation for the future opt-in GVY client. It does not start or download a server.
It targets IntelliJ IDEA builds 253.29346.138 through 262.* and requires the IDE's LSP module.

From the repository root, run `./gradlew -p editors/jetbrains spotlessCheck test testIde20262 buildPlugin verifyPlugin`
with JDK 21. This uses the repository's wrapper without including the plugin in the root Gradle build. Verification
downloads IDE distributions; run it on a machine with sufficient storage. Native acceptance on IDEA 2025.3.1 and
2026.2.5 remains required.
