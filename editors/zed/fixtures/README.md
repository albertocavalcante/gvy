# Zed test fixture

Copy `basic` to a scratch directory outside the repository and open that copy as a project in Zed. For example, from the
repository root, choose a private scratch parent and run:

```sh
cp -R editors/zed/fixtures/basic "$SCRATCH_PARENT/gvy-zed-basic"
```

Set `SCRATCH_PARENT` to an existing directory first. Add the project-local `.zed/settings.json` described in the
[adapter guide](../README.md); substitute your own Java and JAR paths there and keep that settings file private. Trust
only the scratch project when testing execution. Do not commit local settings, personal paths, or test artifacts.

`Main.groovy` calls the method declared in `Greeter.groovy`, giving completion, hover, definition and diagnostics a
small shared example. Edit the scratch copy, including unsaved edits, so the checked-in fixture stays reproducible.
