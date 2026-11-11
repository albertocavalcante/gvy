# Snapshots

Expected output for `snapshot:` expectations in scenarios, for example:

```yaml
- assert:
    source: hoverResult
    checks:
      - jsonPath: $.contents.value
        expect:
          snapshot: hover/println.md
```

The name is a path under this directory. A string value is stored as is; any other value is stored as pretty JSON with
sorted keys. The workspace location is stored as `{{workspace.uri}}` and `{{workspace.path}}`, so files do not depend on
the machine. Text containing an identity hash such as `Foo@1a2b3c4d` is rejected, even when updating.

## Updating

Review the diff the failing test prints. If the new output is right, rewrite the files locally:

```sh
./gradlew :tests:e2eTest --rerun --tests '*ScenarioRunnerTest' -Dgroovy.lsp.e2e.updateSnapshots=true
```

Then review `git diff` before committing. Update mode is refused when `CI` or `GITHUB_ACTIONS` is set and under
Bazel. It also refuses to write `[]` or `null` (usually a filter or wildcard that matched nothing); write such a file by
hand if it is really expected. Pass `--rerun` so Gradle does not serve the result from its build cache.

This `README.md` also marks the directory for Bazel, which has no directory labels.

Inside `waitNotification`, a snapshot does not select the notification: while waiting it only requires the JSONPath to
exist. Add a content check, or use `saveAs` and snapshot it in a later `assert` step.
