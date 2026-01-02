# Gradle Tooling + JDK Compatibility Fix Plan

## Root cause (confirmed)

- The Tooling API uses the project's Gradle wrapper distribution. In the failing project, that wrapper is
  `gradle-8.0.2`.
- The LSP is running on JDK 21, which produces class files with major version 65.
- Gradle 8.0.2's Groovy/ASM stack cannot read class file major version 65, so script compilation fails with:
  - `Unsupported class file major version 65`
- The current resolver interprets that error as an init script issue and retries with an isolated Gradle user home. That
  retry cannot fix a JDK/Gradle mismatch, so it fails again.

## Evidence from the report (sanitized)

- `Could not fetch model of type 'IdeaProject' ...`
- `Could not open cp_proj generic class cache for build file '.../build.gradle' (~/.groovy-lsp/gradle-user-home/...)`
- `BUG! exception in phase 'semantic analysis' ... Unsupported class file major version 65`

## Why the current fallback fails

- The isolated user home only bypasses user init scripts under `~/.gradle/init.d`.
- The failure happens while compiling the build script itself, not in a user init script.
- The fallback is triggered because `Unsupported class file major version` is currently treated as an
  init-script-related error.

## Goal

Make Gradle dependency resolution resilient when the Gradle wrapper version is incompatible with the JDK running the
LSP, and provide clear, actionable guidance or automated fallback paths.

## Non-goals

- Do not change the default Gradle wrapper of user projects.
- Do not silently upgrade Gradle without opt-in (unless explicitly configured).

## Plan of record (detailed)

### Phase 0: Confirm environment and capture signals

1. Add a small utility to capture:
   - Runtime JDK major version (from `System.getProperty("java.version")` or `Runtime.version()`).
   - The Gradle distribution version being used, by fetching the `BuildEnvironment` model. This is a lightweight
     operation that does not compile build scripts and reliably provides the Gradle version.
2. Ensure any logging avoids absolute home paths (use `$HOME` or `~` in messages).
3. Add a single debug-level log line that prints: JDK major, Gradle version, and whether wrapper is in use.

### Phase 1: Compatibility detection and classification

1. Create a compatibility map for Gradle vs JDK:
   - Examples (based on Gradle's compatibility matrix; keep this in sync with official docs):
     - Gradle 8.0.2 supports running on JDK 19, but not on JDK 20 or JDK 21.
     - Gradle 8.3 adds (experimental) support for running on JDK 20, but still does not support JDK 21.
     - Gradle < 8.5 does not support running on JDK 21.
   - Source this from Gradle release notes / compatibility matrix (verify exact thresholds and update as Gradle releases
     evolve).
2. Add a compatibility check before `modelBuilder.get()` is called:
   - If JDK version is newer than supported for the chosen Gradle version, classify as `INCOMPATIBLE_JDK`.
3. Update `shouldRetryWithIsolatedGradleUserHome`:
   - Only retry on clear init-script indicators (`init.d`, `init script`, `cp_init`).
   - Do NOT retry solely on `Unsupported class file major version` unless the message explicitly references init
     scripts.
4. Add a new error classification helper (example enum):
   - `INIT_SCRIPT_FAILURE`
   - `TRANSIENT_LOCK`
   - `JDK_GRADLE_MISMATCH`
   - `OTHER`
5. Surface the classification to callers to decide the next action.

### Phase 2: Add configuration knobs (user control)

1. Add new server configuration options:
   - `groovy.gradle.userHomeMode` with values:
     - `auto` (current behavior, default)
     - `always` (always use isolated Gradle user home)
     - `never` (never isolate; always use default Gradle user home)
   - Optional: `groovy.gradle.userHomePath` to allow a custom isolated path.
2. Plumb config through:
   - `ServerConfiguration` parsing
   - `ProjectStartupManager` -> `DependencyManager` -> `BuildToolManager` -> `GradleBuildTool` ->
     `GradleDependencyResolver`
3. Update tests to cover each mode and verify the correct user home selection logic.

### Phase 3: Allow configuring the JDK used by Gradle (preferred fix)

1. Add a new config option:
   - `groovy.gradle.java.home` (path to a JDK for Gradle execution).
2. When set:
   - Validate the path exists and contains `bin/java`.
   - Use the Tooling API to set the Java home:
     - Prefer `ModelBuilder.setJavaHome(File)` if available.
     - Otherwise, pass `-Dorg.gradle.java.home=...` as a JVM/system property.
3. Add tests for:
   - Valid path -> `setJavaHome` is invoked.
   - Invalid path -> log warning and fallback to default behavior.
4. Document the new setting for the VS Code extension (settings JSON) and README.

### Phase 4: Optional Gradle distribution override (safe opt-in)

1. Add config option to override Gradle distribution (opt-in only):
   - `groovy.gradle.distributionVersion`
   - `groovy.gradle.wrapper.enabled` (boolean, default true)
2. If `wrapper.enabled = false` and `distributionVersion` is set:
   - Use `GradleConnector.useGradleVersion(...)`.
3. If `wrapper.enabled = true` but `JDK_GRADLE_MISMATCH` is detected:
   - If `distributionVersion` is set and is compatible, retry once using override.
   - Otherwise, fail with actionable guidance.
4. Tests:
   - Wrapper present + override disabled -> wrapper stays in use.
   - Override enabled -> connector uses specified version.

### Phase 5: User-facing guidance and diagnostics

1. When `JDK_GRADLE_MISMATCH` is detected, log a concise error:
   - Explain the mismatch: "Gradle 8.0.2 cannot run on JDK 21."
   - Provide next steps:
     - Upgrade Gradle wrapper (recommended).
     - Set `groovy.gradle.java.home` to a compatible JDK.
     - Configure a Gradle distribution override (if allowed).
2. Surface the message via progress callback for better UX in editors.

## Test plan (TDD-compliant)

1. **RED**: Add tests for:
   - JDK/Gradle mismatch classification (mock Gradle version + JDK version).
   - No isolated retry for `Unsupported class file major version` without init-script indicators.
   - New config modes for `userHomeMode`.
   - `setJavaHome` usage when configured.
2. **RUN**: Execute targeted tests to ensure failure before implementation.
3. **GREEN**: Implement changes to pass tests.
4. **RUN**: Re-run tests.
5. **REFACTOR**: Clean up detection logic and ensure logging is clear.

## Risks and mitigations

- **Risk:** Overriding Gradle version may break builds with strict wrapper usage.
  - **Mitigation:** Keep override opt-in and default to wrapper.
- **Risk:** Incorrect compatibility map leads to false positives.
  - **Mitigation:** Document sources and add regression tests for known versions.
- **Risk:** Logging absolute paths violates repo policy.
  - **Mitigation:** Sanitize paths in messages (use `$HOME`).

## Rollout checklist

- Update `docs/` and editor settings docs for new config keys.
- Add release note entry in `CHANGELOG.md`.
- Consider adding a small FAQ entry in `kb/TROUBLESHOOTING.md`.
