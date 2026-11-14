# Contribute to gvy

**Prerequisites:** a Git checkout, the JDKs described in the
[contributor guide](../../CONTRIBUTING.md#set-up-the-jvm-build), and a focused change or reproducible issue.

For example, to begin investigating server behavior, read the [repository overview](../overview.md), then build the
server from the repository root:

```bash
./gradlew :gls:shadowJar
```

A successful build produces `gls/build/libs/gls-<version>-all.jar`. On shared machines use the build-slot guidance in
the contributor guide. Building does not install the editor extension or validate a change in an editor. Follow
[contributor setup and validation](../../CONTRIBUTING.md) for tests, documentation checks, editor work, and pull
requests. Include a minimal reproduction when
[reporting a bug](../../CONTRIBUTING.md#report-bugs-and-dependency-issues).
