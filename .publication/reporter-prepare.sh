#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

proof="$RUNNER_TEMP/reporter-proof"
[[ "$(git -C "$GITHUB_WORKSPACE/support" rev-parse HEAD)" == "$GITHUB_SHA" ]]
[[ -z "$(git -C "$GITHUB_WORKSPACE/support" status --porcelain --untracked-files=all)" ]]
cd "$GITHUB_WORKSPACE/product"
paths=(MODULE.bazel tools/BUILD.bazel tools/build_defs/junit.bzl tools/build_defs/kotlin.bzl)
product_root="$(pwd -P)"
buildifier_paths=()
for path in "${paths[@]}"; do
  buildifier_paths+=("$product_root/$path")
done
final_paths=(MODULE.bazel MODULE.bazel.lock tools/BUILD.bazel tools/build_defs/junit.bzl tools/build_defs/kotlin.bzl)
[[ "$(git rev-parse HEAD)" == a0bdf38c2d32f4bdf72a43df52501186cf716eb7 ]]
[[ "$(git rev-parse 'HEAD^{tree}')" == 51a4e33ca0fb759b22d36873314fd4fc5be23ab1 ]]
[[ "$(git rev-parse 'HEAD^')" == 78dcf942382405e58d90f4728920ff31dbf96890 ]]
[[ "$(git rev-parse '78dcf942382405e58d90f4728920ff31dbf96890^{tree}')" == 85c8fc3df82c038398c3667b2a821837f6725c30 ]]
[[ -z "$(git status --porcelain --untracked-files=all)" ]]
git worktree list --porcelain >"$proof/worktrees.txt"
git branch --show-current >"$proof/initial-branch.txt"
printf '%s\n' "${paths[@]}" >"$RUNNER_TEMP/reporter-paths"
git diff --name-only 78dcf942382405e58d90f4728920ff31dbf96890 HEAD >"$RUNNER_TEMP/reporter-input-paths"
cmp "$RUNNER_TEMP/reporter-paths" "$RUNNER_TEMP/reporter-input-paths"
git switch -c fix/junit-reporting-runtime
git reset --soft 78dcf942382405e58d90f4728920ff31dbf96890
[[ "$(git write-tree)" == 51a4e33ca0fb759b22d36873314fd4fc5be23ab1 ]]
git diff --quiet
direnv allow
export GRADLE_OPTS="-Dorg.gradle.jvmargs=\"-Xmx1536m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC\" -Dorg.gradle.workers.max=1 -Dorg.gradle.parallel=false -Dorg.gradle.daemon=false -Dorg.gradle.java.installations.paths=$JAVA_HOME_17_X64,$JAVA_HOME_21_X64"
direnv exec . ./gradlew lintFix --no-daemon --no-parallel --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC' 2>&1 | tee "$proof/lintFix.log"
git diff --name-only >"$RUNNER_TEMP/reporter-unstaged"
while IFS= read -r path; do
  [[ -z "$path" ]] || grep -Fxq -- "$path" "$RUNNER_TEMP/reporter-paths"
done <"$RUNNER_TEMP/reporter-unstaged"
run_bounded() {
  local label="$1"
  shift
  set +e
  timeout --kill-after=10s 1200s "$@" 2>&1 | python3 -c '
import sys
remaining = 8 * 1024 * 1024
overflow = False
while chunk := sys.stdin.buffer.read(65536):
    keep = chunk[:remaining]
    sys.stdout.buffer.write(keep)
    remaining -= len(keep)
    overflow = overflow or len(keep) != len(chunk)
sys.exit(75 if overflow else 0)
' | tee "$proof/$label.log"
  local statuses=("${PIPESTATUS[@]}")
  set -e
  printf '%s %s %s\n' "${statuses[0]}" "${statuses[1]}" "${statuses[2]}" >"$proof/$label.status"
  [[ "${statuses[0]}" == 0 && "${statuses[1]}" == 0 && "${statuses[2]}" == 0 ]]
}
trap 'timeout --kill-after=5s 20s bazel --host_jvm_args=-Xmx1024m shutdown >/dev/null 2>&1 || true' EXIT
run_bounded resolve-update bazel --host_jvm_args=-Xmx1024m query --lockfile_mode=update \
  'set(@junit_platform_reporting_jar//file @maven//:org_jspecify_jspecify @maven//:org_opentest4j_reporting_open_test_reporting_tooling_spi)'
run_bounded resolve-error bazel --host_jvm_args=-Xmx1024m query --lockfile_mode=error \
  'set(@junit_platform_reporting_jar//file @maven//:org_jspecify_jspecify @maven//:org_opentest4j_reporting_open_test_reporting_tooling_spi)'
git diff -- MODULE.bazel.lock >"$proof/lock.patch"
sha256sum MODULE.bazel.lock >"$proof/lock.sha256"
if git diff --quiet -- MODULE.bazel.lock; then
  printf '%s\n' unchanged >"$proof/lock-state.txt"
else
  printf '%s\n' changed >"$proof/lock-state.txt"
fi
run_bounded buildifier-fix bazel --host_jvm_args=-Xmx1024m run --lockfile_mode=error @buildifier_prebuilt//:buildifier -- -mode=fix "${buildifier_paths[@]}"
run_bounded buildifier-check bazel --host_jvm_args=-Xmx1024m run --lockfile_mode=error @buildifier_prebuilt//:buildifier -- -mode=check -lint=warn "${buildifier_paths[@]}"
sha256sum -c "$proof/lock.sha256"
git add -- "${final_paths[@]}"
git diff --quiet
git diff --cached --check
git diff --cached --name-only >"$RUNNER_TEMP/reporter-formatted-paths"
printf '%s\n' "${final_paths[@]}" >"$RUNNER_TEMP/reporter-final-paths"
if ! cmp -s "$RUNNER_TEMP/reporter-final-paths" "$RUNNER_TEMP/reporter-formatted-paths"; then
  cmp "$RUNNER_TEMP/reporter-paths" "$RUNNER_TEMP/reporter-formatted-paths"
fi
git write-tree >"$proof/formatted-tree"
git diff --cached --binary >"$proof/formatted.patch"
bootstrap="$RUNNER_TEMP/reporter-license"
python3 -m venv "$bootstrap"
"$bootstrap/bin/python" -m pip install --require-hashes --only-binary=:all: -r .reuse/bootstrap-requirements.txt 2>&1 | tee "$proof/license-bootstrap.log"
export PATH="$bootstrap/bin:$PATH"
pin="$(sed -n 's/^uv==\([0-9][0-9.]*\).*/\1/p' .reuse/bootstrap-requirements.txt)"
[[ "$pin" == 0.13.7 && "$(command -v uv)" == "$bootstrap/bin/uv" ]]
[[ "$("$bootstrap/bin/python" -I -c 'from importlib.metadata import version; print(version("uv"))')" == "$pin" ]]
sha256sum "$bootstrap/bin/uv" >"$proof/uv.sha256"
uv sync --project .reuse --locked 2>&1 | tee "$proof/license-sync.log"
make license-check 2>&1 | tee "$proof/license-check.log"
make license-test 2>&1 | tee "$proof/license-test.log"
[[ "$(git branch --show-current)" == fix/junit-reporting-runtime ]]
[[ "$(git write-tree)" == "$(cat "$proof/formatted-tree")" ]]
git diff --quiet
[[ -z "${LEFTHOOK:-}" && -z "${LEFTHOOK_EXCLUDE:-}" && -z "${LEFTHOOK_SKIP:-}" ]]
[[ -z "$(git config --get core.hooksPath || true)" ]]
lefthook install
test -x .git/hooks/pre-commit && test -x .git/hooks/commit-msg
[[ "$(direnv exec . bash -c 'command -v uv' 2>/dev/null)" == "$bootstrap/bin/uv" ]]
sha256sum -c "$proof/uv.sha256"
git config user.name 'gvy publication automation'
git config user.email '41898282+github-actions[bot]@users.noreply.github.com'
direnv exec . git commit -m 'fix(bazel): include JUnit reporting runtime' 2>&1 | tee "$proof/hooks.log"
[[ "$(git rev-parse HEAD^)" == 78dcf942382405e58d90f4728920ff31dbf96890 ]]
[[ "$(git rev-parse 'HEAD^{tree}')" == "$(cat "$proof/formatted-tree")" ]]
[[ -z "$(git status --porcelain --untracked-files=all)" ]]
git bundle create "$proof/candidate.bundle" HEAD ^78dcf942382405e58d90f4728920ff31dbf96890
git bundle verify "$proof/candidate.bundle"
timeout --kill-after=5s 20s bazel --host_jvm_args=-Xmx1024m shutdown 2>&1 | tee "$proof/bazel-shutdown.log"
trap - EXIT
git rev-parse HEAD >"$proof/head"
git rev-parse 'HEAD^{tree}' >"$proof/tree"
(cd "$proof" && sha256sum candidate.bundle formatted.patch >evidence.sha256)
