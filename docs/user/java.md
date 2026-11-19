# Choose Java

**Prerequisites:** install a Java 17 or newer runtime for the language server and a JDK compatible with your project
build. They can be different installations. Check a terminal runtime with:

```bash
java -version
```

To select them explicitly, open editor settings as JSON and replace the example paths with real JDK home directories:

```json
{
  "groovy.languageServer.javaHome": "/path/to/java-17",
  "groovy.project.javaHome": "/path/to/project-jdk"
}
```

Restart the language server when prompted. The first setting selects the server runtime; the second is for project build
and test execution. A terminal's `java -version` alone does not prove which runtime the editor selected. Paths and
available settings may vary by installed extension version; consult [settings](reference-settings.md) and the
[editor guide](../../editors/code/README.md#java-settings-explained). If startup fails, continue to
[troubleshooting](troubleshooting.md).
