# Test library fixture

`Greeter.java` is the source for `libs/testlib.jar` and its paired source archive. The binary must run on Java 17, the
project's minimum runtime. Run the following from the repository root using the configured JDK 21; `--release 17` keeps
the output compatible with the minimum runtime. No download is needed:

```sh
(
  set -eu
  cd tests/e2e/resources/workspaces/gradle-dependency/testlib-build
  testlib_tmp="$(mktemp -d)"
  trap 'rm -rf "$testlib_tmp"' 0
  mkdir -p "$testlib_tmp/classes" "$testlib_tmp/manifest/META-INF"
  printf 'Manifest-Version: 1.0\n\n' > "$testlib_tmp/manifest/META-INF/MANIFEST.MF"
  javac -J-Xmx128m --release 17 -proc:none -encoding UTF-8 -g:source,lines \
    -d "$testlib_tmp/classes" com/example/testlib/Greeter.java
  jar -J-Xmx128m -J-Duser.timezone=UTC --create --file ../libs/testlib.jar --no-manifest --no-compress \
    --date=2024-01-01T00:00:00Z \
    -C "$testlib_tmp/manifest" META-INF -C "$testlib_tmp/classes" com
)
```

The explicit manifest and fixed UTC archive date avoid local JDK and time metadata. Stored entries avoid compressor
variation. Rebuilding with the same compiler produces identical bytes. `GradleDependencyScenarioTest` checks Java 17
bytecode compatibility and compiles the fixture consumer both without and with this JAR.

If `Greeter.java` changes, also update `libs/testlib-sources.jar` from the same source so external navigation and
Javadoc tests continue to use the matching source.
