# gvy developer commands. Gradle and pnpm own incremental builds.
set dotenv-load := false
set positional-arguments
set windows-shell := ["powershell.exe", "-NoLogo", "-Command"]

# List developer commands.
default:
    @just --list --unsorted

# ─── Setup ───────────────────────────────────────────────

[script("node")]
setup:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.command("lefthook", ["install"]); dev.extension(["install", "--frozen-lockfile"], {SKIP_PREPARE_SERVER: "true"});

# ─── Build ───────────────────────────────────────────────

[script("node")]
build *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.gradle(["build", ...process.argv.slice(2)]);

[script("node")]
jar *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.gradle([":gls:stageEditorServer", ...process.argv.slice(2)]);

[script("node")]
rebuild *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.gradle([...["build","--rerun-tasks"], ...process.argv.slice(2)]);

[script("node")]
clean *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.gradle([...["clean"], ...process.argv.slice(2)]);

[script("node")]
version *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.gradle([...["printVersion"], ...process.argv.slice(2)]);

# ─── Test ───────────────────────────────────────────────

[script("node")]
test *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.gradle([...["test"], ...process.argv.slice(2)]);

[script("node")]
retest *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.gradle([...["test","--rerun-tasks"], ...process.argv.slice(2)]);

[script("node")]
e2e *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.gradle([...[":tests:e2eTest","--console=plain"], ...process.argv.slice(2)]);

[script("node")]
e2e-single scenario *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    const [scenario, ...args] = process.argv.slice(2); dev.gradle([":tests:e2eTest", `-Dgroovy.lsp.e2e.filter=${scenario}`, ...args]);

# ─── Lint/Format ─────────────────────────────────────────

[script("node")]
lint *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.gradle([...["lint"], ...process.argv.slice(2)]);

[script("node")]
fmt *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.gradle([...["lintFix"], ...process.argv.slice(2)]); dev.command("dprint", ["fmt"]);

[script("node")]
fix-imports *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.gradle([...["detektAutoCorrect","--parallel"], ...process.argv.slice(2)]);

[script("node")]
quality *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.gradle([...["quality"], ...process.argv.slice(2)]);

[script("node")]
fmt-check:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.command("dprint", ["check"]); dev.extension(["run", "format:check"]);

alias format := fmt

# ─── Check/CI ────────────────────────────────────────────

# Includes JVM lint, tests, coverage verification, and editor checks.
check: fmt-check quality test check-editor check-commands

[script("node")]
check-editor: ext-install
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.extension(["run", "check"]);

[script("node")]
check-commands:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.command("just", ["--fmt", "--check"]); dev.command(process.execPath, ["--test", "tools/test-just.js"]);

# ─── Run/Dev ─────────────────────────────────────────────

[script("node")]
run-stdio *args: jar
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.command(process.env.JAVA_HOME ? require("node:path").join(process.env.JAVA_HOME, "bin", "java") : "java", ["-jar", "gls/build/editor-server/gls.jar", ...process.argv.slice(2)]);

[script("node")]
run-socket *args: jar
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.command(process.env.JAVA_HOME ? require("node:path").join(process.env.JAVA_HOME, "bin", "java") : "java", ["-jar", "gls/build/editor-server/gls.jar", "socket", "8080", ...process.argv.slice(2)]);

[script("node")]
viz-run *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.gradle([":viz:desktop:run", ...process.argv.slice(2)]);

# ─── Install ─────────────────────────────────────────────

[script("node")]
doctor *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.installSource(["--doctor", ...process.argv.slice(2)]);

[script("node")]
install-vscode-source *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.installSource(["--from-source", "--installer", "code", ...process.argv.slice(2)]);

[script("node")]
package-source *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.installSource(["--from-source", "--package-only", ...process.argv.slice(2)]);

# ─── Editor ──────────────────────────────────────────────

[script("node")]
ext-install:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.extension(["install", "--frozen-lockfile"], {SKIP_PREPARE_SERVER: "true"});

[script("node")]
ext-build: ext-install
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.extension(["run", "compile"]);

[script("node")]
ext-test: ext-install
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.extension(["test"]);

alias ext-package := package-source
alias ext-vscode := install-vscode-source
alias ext-vscode-source := install-vscode-source
alias ext-package-source := package-source
alias ext-doctor := doctor

[script("node")]
ext-cursor *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.installSource(["--from-source", "--installer", "cursor", ...process.argv.slice(2)]);

[script("node")]
ext-agy *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.installSource(["--from-source", "--installer", "agy", ...process.argv.slice(2)]);

[script("node")]
ext-kiro *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.installSource(["--from-source", "--installer", "kiro", ...process.argv.slice(2)]);

[script("node")]
ext-windsurf *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.installSource(["--from-source", "--installer", "windsurf", ...process.argv.slice(2)]);

[script("node")]
ext-editors:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    throw new Error("Choose an editor explicitly: just install-vscode-source, ext-cursor, ext-agy, ext-kiro, or ext-windsurf");

# Desktop packaging retains its existing nested Make entry point for now.
[script("node")]
viz-install:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.command("make", ["-C", "viz/desktop", "install"]);

# ─── Releases and experimental builds ─────────────────────

[script("node")]
release-build:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.command("bash", ["tools/release.sh", "build", "--version", "v0.0.0-dryrun", "--dry-run"]);

alias release := release-build

[script("node")]
release-publish version *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    const [version, ...args] = process.argv.slice(2); dev.command("bash", ["tools/release.sh", "build", "--version", version, ...args]);

[script("node")]
bazel-build *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.command("bazel", [...["build","//..."], ...process.argv.slice(2)]);

[script("node")]
bazel-test *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.command("bazel", [...["test","//..."], ...process.argv.slice(2)]);

[script("node")]
bazel-lint *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.command("bazel", [...["build","--config=lint","//..."], ...process.argv.slice(2)]);

[script("node")]
bazel-clean *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.command("bazel", [...["clean","--expunge"], ...process.argv.slice(2)]);

[script("node")]
bazel-jar *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.command("bazel", [...["build","//gls:gls_deploy_deploy.jar"], ...process.argv.slice(2)]);

[script("node")]
bazel-sync *args:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    dev.command("bazel", [...["run","@maven//:pin"], ...process.argv.slice(2)]);

[script("node")]
verify-builds:
    const dev = require(require("node:path").resolve("tools/dev-command.js"));
    throw new Error("Artifact equivalence is not implemented; compare explicit Gradle and Bazel artifacts with a defined compatibility check");
