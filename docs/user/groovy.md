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

## Organize imports

Run **Organize Imports** (`Shift+Alt+O` in VS Code), or save with
`"editor.codeActionsOnSave": { "source.organizeImports": "explicit" }`. gvy rewrites the leading import block:

- removes imports it can prove unused, and exact duplicates (same binding, written the same way);
- sorts by fully qualified name, case-sensitive, with `javax` and `java` imports in their own group and static imports
  last, one blank line between groups;
- keeps star imports, annotated imports (`@Grab`), and any import whose name still appears elsewhere in the file, such
  as in Groovydoc, a string or a comment;
- moves a comment on an import's line, or directly above it, together with the import. A comment that belongs to no
  single import turns off reordering, and only unused imports are removed.

The action returns nothing when the imports are already organized, when the editor's text is newer than gvy's last
compile (save again after a moment), or, for removal, when the file has syntax errors: a file with syntax errors is
sorted but no import is removed. Imports after the first statement of a script stay where they are, and so do the imports after an annotated import
(`@Grab`), which ends the block.

Every unused import is also dimmed and offers a **Remove unused import** quick fix, plus **Remove all unused imports**
when there are several.

The layout is fixed for now; a `groovy.imports.layout` setting is planned.
