<!-- SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# JetBrains GVY client

The client is disabled per project by default. It targets IntelliJ IDEA builds 253.29346.138 through 262.* and requires
the IDE's LSP module. It supports local `.groovy` files. GVY does not download a server automatically.

## Connect a project

1. Trust the project using IntelliJ IDEA's project security controls.
2. Open **Settings > Languages & Frameworks > GVY**.
3. Select an absolute path to a local Java executable and a runnable GVY server JAR. The server requires Java 17 or
   newer; this is separate from the IDE's own Java runtime.
4. Enter one complete JVM argument per line, for example `-Xmx1g`. Paths and arguments containing spaces or Unicode are
   preserved as individual arguments. Do not add shell quotes or a main class, `-jar`, module selector or argument file.
5. Optionally supply a JSON object with canonical server settings, for example `{"groovy.codenarc.enabled": false}`.
6. Select **Enable GVY in this trusted project** and apply. Open a `.groovy` file.

Paths and settings are saved in this project's local workspace settings. Applying settings restarts GVY for this
project. The IDE LSP client owns process startup, shutdown, project-close cleanup and plugin unloading.

## Contributor validation

From the repository root, run `./gradlew -p editors/jetbrains spotlessCheck test testIde20262 buildPlugin verifyPlugin`
with JDK 21. This uses the repository's wrapper without including the plugin in the root Gradle build. Verification
downloads IDE distributions; run it on a machine with sufficient storage. Native acceptance on IDEA 2025.3.1 and
2026.2.5 remains required.

To use an existing local IDEA 2025.3.1 installation for development, pass `-PgvyLocalIde="/path/to/IntelliJ IDEA.app"`.
The build checks its product and exact build number before using it; CI uses the pinned distribution by default.
