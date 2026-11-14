# Quickstart

Install the stable extension and open a Groovy file. The v0.5.4 VSIX contains an older 0.4.8 server; see the
[release limitation](../installation.md#stable-release-limitation) before evaluating server fixes.

**Prerequisites:** a compatible VS Code-based editor and Java 17 or newer. Homebrew can supply Java on macOS and Linux.
Follow [installation](../installation.md) for the editor version, downloads, and alternatives.

1. Install the VSIX as described in the installation guide and reload an already open editor window.
2. Open a folder and save this as `Hello.groovy`:

   ```groovy
   class Greeter {
       String greet(String name) { "Hello, ${name}" }
   }
   println new Greeter().greet('Groovy')
   ```

3. Open the file and place the cursor on `greet`. Try hover or **Go to Definition**.

The extension should activate for the file and start its bundled server. Syntax highlighting should appear; language
results depend on server startup and project context. If they do not, open the **Groovy Language Server** output and
follow [troubleshooting](troubleshooting.md). The example is for editing; gvy does not execute it merely by opening it.

Coverage and correctness vary. The [feature matrix](../lsp/feature-support.md) describes development-source
capabilities, not a guarantee for the stable VSIX. Next, choose [Groovy](groovy.md), [Gradle](gradle.md),
[Jenkins](jenkins.md), or [Spock](spock.md).
