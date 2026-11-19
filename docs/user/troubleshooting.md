# Troubleshooting

**Prerequisites:** note the installed extension and server versions, editor version, operating system, and the project
and Java versions. The command **Groovy: Show Language Server Version** can report the server version after it starts;
v0.5.4's VSIX bundles an older server as described in [installation](../installation.md#stable-release-limitation).

For a file with no language results:

1. Confirm its filename and language mode, then run `java -version` in a terminal. The server needs Java 17 or newer.
2. Open **Output** and select **Groovy Language Server**. Look for startup or dependency errors.
3. Run **Groovy: Restart Language Server**, reopen the file, and retry hover on a symbol. If it works, the server
   recovered; if not, retain a redacted error and a minimal example.

The terminal Java may differ from the server's configured Java. A restart cannot correct missing credentials, an
incompatible project JDK, or an unavailable repository. See [Java](java.md), [Gradle](gradle.md), and
[private environments](private-environments.md). If the problem persists, report the example, expected and actual
results, versions, and redacted output in [gvy issues](https://github.com/albertocavalcante/gvy/issues). Never include
secrets or proprietary code without permission.
