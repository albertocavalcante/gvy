# Diagnostics and formatting

**Prerequisites:** install gvy and open a Groovy file. For example, save:

```groovy
class Example {
    String greet() { 'hello' }
}
```

Open the editor's Problems view for diagnostics. To format, choose **Format Document** and select gvy if the editor asks
for a formatter. Review the resulting diff before saving. Formatting and diagnostics depend on the installed server,
configuration and project context; no particular clean result proves the program is correct. The extension setting
`groovy.format.enable` controls formatting, and CodeNarc settings control its static analysis; see the
[settings reference](reference-settings.md).

If formatting is unavailable or diagnostics seem stale, check the selected formatter and server output, then follow
[troubleshooting](troubleshooting.md). For a project-specific ruleset, consult the installed extension's settings:
development source accepts a machine-scoped properties file and does not evaluate workspace Groovy rulesets.
