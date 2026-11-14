# Edit Groovy

**Prerequisites:** complete the [quickstart](quickstart.md) and open a folder in the editor.

Save the following as `Example.groovy`, then hover over `message` or request completion after `message.`:

```groovy
String message = 'hello'
println message.toUpperCase()
```

The editor should highlight Groovy syntax and can request language results from the server. Completion and type
information depend on available project dependencies and can be incomplete for dynamic Groovy. Check the output panel if
the server does not start; [troubleshooting](troubleshooting.md) explains what to collect.

For build dependencies, continue with [Gradle](gradle.md). For formatting and errors, see
[diagnostics and formatting](diagnostics-formatting.md).
