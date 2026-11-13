#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

proof="$RUNNER_TEMP/noise-proof"
candidate="$GITHUB_WORKSPACE/candidate"
red="$GITHUB_WORKSPACE/red"
init="$GITHUB_WORKSPACE/support/.publication/parity.init.gradle"
parent=f04406dac415a5b7cc1698ca8feb0d0de3e9e956
candidate_head=f4ee9eb5ccaa7ab17f44f7fbec4db8654cfb361f
candidate_tree=20b4322f3bdc25dba8b0cdb044c87ba1fd156cc9
red_head=a2baf77a1f5808e1fc2f222e3d178e4d6fd51a2c
jenkins_test=jenkins/src/test/kotlin/com/github/albertocavalcante/gvy/jenkins/JenkinsContextLoggingTest.kt
paths=(
  build-tool/src/test/kotlin/com/github/albertocavalcante/gvy/build/gradle/GradleBuildToolTest.kt
  build-tool/src/test/kotlin/com/github/albertocavalcante/gvy/build/gradle/GradleDependencyResolverRetryTest.kt
  build-tool/src/test/kotlin/com/github/albertocavalcante/gvy/build/maven/MavenDependencyResolverTest.kt
  jenkins/src/main/kotlin/com/github/albertocavalcante/gvy/jenkins/JenkinsContext.kt
  "$jenkins_test"
  jenkins/src/test/kotlin/com/github/albertocavalcante/gvy/jenkins/config/UserMetadataLoaderTest.kt
)
printf '%s\n' "${paths[@]}" >"$RUNNER_TEMP/noise-expected-paths"
printf '%s  %s\n' 7ed933ec81e68c3b98066f2b3273f475ad9a82cdcb19258ce7f835dd56797f5f "$init" | sha256sum -c -
[[ "$(git -C "$GITHUB_WORKSPACE/support" rev-parse HEAD)" == "$GITHUB_SHA" ]]
[[ -z "$(git -C "$GITHUB_WORKSPACE/support" status --porcelain)" ]]
for checkout in "$red" "$candidate"; do
  git -C "$checkout" worktree list --porcelain >"$proof/$(basename "$checkout")-worktrees.txt"
  git -C "$checkout" branch --show-current >"$proof/$(basename "$checkout")-initial-branch.txt"
  [[ -z "$(git -C "$checkout" status --porcelain)" ]]
done
[[ "$(git -C "$red" rev-parse HEAD)" == "$red_head" ]]
[[ "$(git -C "$candidate" rev-parse HEAD)" == "$candidate_head" ]]
[[ "$(git -C "$candidate" rev-parse 'HEAD^{tree}')" == "$candidate_tree" ]]
git -C "$candidate" merge-base --is-ancestor "$parent" HEAD
cmp "$red/$jenkins_test" "$candidate/$jenkins_test"
git -C "$candidate" diff --name-only "$parent" HEAD >"$RUNNER_TEMP/noise-input-paths"
cmp "$RUNNER_TEMP/noise-expected-paths" "$RUNNER_TEMP/noise-input-paths"
env JDK_JAVA_OPTIONS= JAVA_TOOL_OPTIONS= "$JAVA_HOME_17_X64/bin/java" -Xmx64m -XX:MaxMetaspaceSize=128m -XshowSettings:properties -version >"$proof/setup-java.log" 2>&1
grep -Eq '^[[:space:]]*java\.specification\.version[[:space:]]*=[[:space:]]*17[[:space:]]*$' "$proof/setup-java.log"

cd "$candidate"
git switch -c test/backend-noise-publication
git reset --soft "$parent"
[[ "$(git write-tree)" == "$candidate_tree" ]]
git diff --quiet
direnv allow
export GRADLE_OPTS="-Dorg.gradle.jvmargs=\"-Xmx1536m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC\" -Dorg.gradle.workers.max=1 -Dorg.gradle.parallel=false -Dorg.gradle.daemon=false -Dorg.gradle.java.installations.paths=$JAVA_HOME_17_X64,$JAVA_HOME_21_X64"
gradle_common=(--no-daemon --no-parallel --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC' "-Dorg.gradle.java.installations.paths=$JAVA_HOME_17_X64,$JAVA_HOME_21_X64")
direnv exec . ./gradlew lintFix "${gradle_common[@]}" 2>&1 | tee "$proof/lintFix.log"
git diff --name-only >"$RUNNER_TEMP/noise-formatted-unstaged"
while IFS= read -r path; do
  [[ -z "$path" ]] || grep -Fxq -- "$path" "$RUNNER_TEMP/noise-expected-paths"
done <"$RUNNER_TEMP/noise-formatted-unstaged"
git add -- "${paths[@]}"
git diff --quiet
git diff --cached --check
git diff --cached --name-only >"$RUNNER_TEMP/noise-formatted-paths"
cmp "$RUNNER_TEMP/noise-expected-paths" "$RUNNER_TEMP/noise-formatted-paths"
git write-tree >"$proof/formatted-tree"
git diff --cached --binary >"$proof/formatted.patch"
sha256sum "$proof/formatted.patch" >"$proof/formatted.sha256"

bootstrap="$RUNNER_TEMP/license-bootstrap"
python3 -m venv "$bootstrap"
"$bootstrap/bin/python" -m pip install --require-hashes --only-binary=:all: -r .reuse/bootstrap-requirements.txt 2>&1 | tee "$proof/license-bootstrap.log"
export PATH="$bootstrap/bin:$PATH"
pin="$(sed -n 's/^uv==\([0-9][0-9.]*\).*/\1/p' .reuse/bootstrap-requirements.txt)"
[[ "$pin" == 0.13.7 && "$(command -v uv)" == "$bootstrap/bin/uv" ]]
[[ "$("$bootstrap/bin/python" -I -c 'from importlib.metadata import version; print(version("uv"))')" == "$pin" ]]
timeout 10s "$bootstrap/bin/uv" --version >"$proof/uv-version.txt"
sha256sum "$bootstrap/bin/uv" >"$proof/uv.sha256"
uv sync --project .reuse --locked 2>&1 | tee "$proof/license-sync.log"
make license-check 2>&1 | tee "$proof/license-check.log"
make license-test 2>&1 | tee "$proof/license-test.log"
git diff --quiet
[[ "$(git write-tree)" == "$(cat "$proof/formatted-tree")" ]]

cd "$red"
direnv allow
rm -f jenkins/build/test-results/test/TEST-com.github.albertocavalcante.gvy.jenkins.JenkinsContextLoggingTest.xml
set +e
direnv exec . ./gradlew :jenkins:test --tests 'com.github.albertocavalcante.gvy.jenkins.JenkinsContextLoggingTest' --rerun-tasks --no-build-cache --info -I "$init" "${gradle_common[@]}" 2>&1 | tee "$proof/red.log"
red_statuses=("${PIPESTATUS[@]}")
set -e
printf '%s %s\n' "${red_statuses[0]}" "${red_statuses[1]}" >"$proof/red.status"
[[ "${red_statuses[0]}" != 0 && "${red_statuses[1]}" == 0 ]]
cp jenkins/build/test-results/test/TEST-com.github.albertocavalcante.gvy.jenkins.JenkinsContextLoggingTest.xml "$proof/red.xml"
python3 - "$proof/red.xml" "$jenkins_test" <<'PYRED'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
cases = root.findall('testcase')
if (root.get('tests'), root.get('failures'), root.get('errors'), root.get('skipped')) != ('3', '2', '0', '0') or len(cases) != 3:
    raise SystemExit('red must contain exactly two assertion failures and one passing control')
source = open(sys.argv[2], encoding='utf-8').read().splitlines()
expected = {
    'an absent optional Maven repository keeps known classpath and metadata without warnings': 'assertEquals(listOf(Level.DEBUG), absence.map',
    'a missing library reference emits one resolver warning and keeps the known library': 'assertEquals(1, missingWarnings.size',
}
control = 'a Maven lookup failure remains a warning and keeps known classpath and metadata'
def name(case):
    return (case.get('name') or '').removesuffix('()')
if {name(c) for c in cases} != set(expected) | {control}:
    raise SystemExit('unexpected red cases')
for case in cases:
    failure = case.find('failure')
    if case.find('error') is not None or case.find('skipped') is not None:
        raise SystemExit('red error or skip')
    if name(case) == control:
        if failure is not None:
            raise SystemExit('lookup IOException control did not pass')
        continue
    marker = expected[name(case)]
    lines = [i for i, line in enumerate(source, 1) if marker in line]
    if len(lines) != 1 or failure is None or 'AssertionFailedError' not in failure.get('type', '') or f'JenkinsContextLoggingTest.kt:{lines[0]}' not in (failure.text or ''):
        raise SystemExit('red failure did not originate at intended assertion')
    failure_text = (failure.get('message') or '') + (failure.text or '')
    terms = ('DEBUG', 'WARN') if name(case) != 'a missing library reference emits one resolver warning and keeps the known library' else ('<1>', '<2>')
    if not all(term in failure_text for term in terms):
        raise SystemExit('red assertion values do not match the intended regression')
PYRED
[[ -z "$(git status --porcelain)" ]]

cd "$candidate"
rm -f build-tool/build/test-results/test/TEST-com.github.albertocavalcante.gvy.build.*.xml jenkins/build/test-results/test/TEST-com.github.albertocavalcante.gvy.jenkins.*.xml
direnv exec . ./gradlew :build-tool:test \
  --tests 'com.github.albertocavalcante.gvy.build.gradle.GradleDependencyResolverRetryTest' \
  --tests 'com.github.albertocavalcante.gvy.build.maven.MavenDependencyResolverTest' \
  --tests 'com.github.albertocavalcante.gvy.build.gradle.GradleBuildToolTest.getDependencyMetadata returns empty when the model is rejected' \
  --tests 'com.github.albertocavalcante.gvy.build.LoggingProviderTest' \
  :jenkins:test --tests 'com.github.albertocavalcante.gvy.jenkins.JenkinsContextLoggingTest' \
  --tests 'com.github.albertocavalcante.gvy.jenkins.config.UserMetadataLoaderTest' \
  --tests 'com.github.albertocavalcante.gvy.jenkins.LoggingProviderTest' \
  :build-tool:lint :jenkins:lint lint --rerun-tasks --no-build-cache --info -I "$init" "${gradle_common[@]}" 2>&1 | tee "$proof/green.log"
python3 - "$candidate" "$proof" <<'PYGREEN'
import pathlib
import shutil
import sys
import xml.etree.ElementTree as ET
checkout, proof = map(pathlib.Path, sys.argv[1:])
expected = {
    ('build-tool', 'com.github.albertocavalcante.gvy.build.gradle.GradleDependencyResolverRetryTest'): 11,
    ('build-tool', 'com.github.albertocavalcante.gvy.build.maven.MavenDependencyResolverTest'): 2,
    ('build-tool', 'com.github.albertocavalcante.gvy.build.gradle.GradleBuildToolTest'): 1,
    ('build-tool', 'com.github.albertocavalcante.gvy.build.LoggingProviderTest'): 2,
    ('jenkins', 'com.github.albertocavalcante.gvy.jenkins.JenkinsContextLoggingTest'): 3,
    ('jenkins', 'com.github.albertocavalcante.gvy.jenkins.config.UserMetadataLoaderTest'): 9,
    ('jenkins', 'com.github.albertocavalcante.gvy.jenkins.LoggingProviderTest'): 2,
}
for (module, name), count in expected.items():
    path = checkout / module / 'build/test-results/test' / ('TEST-' + name + '.xml')
    root = ET.parse(path).getroot()
    cases = root.findall('testcase')
    if root.get('tests') != str(count) or any(root.get(k) != '0' for k in ('failures', 'errors', 'skipped')) or len(cases) != count:
        raise SystemExit('wrong green test count: ' + name)
    if any(c.get('classname') != name or any(c.find(k) is not None for k in ('failure', 'error', 'skipped')) for c in cases):
        raise SystemExit('unexpected green case: ' + name)
    if name.endswith('.GradleBuildToolTest') and (cases[0].get('name') or '').removesuffix('()') != 'getDependencyMetadata returns empty when the model is rejected':
        raise SystemExit('wrong GradleBuildToolTest method')
    shutil.copyfile(path, proof / (module + '-' + name + '.xml'))
PYGREEN

for phase in red green; do
  phase_checkout="$candidate"
  if [[ "$phase" == red ]]; then
    phase_checkout="$red"
  fi
  python3 - "$proof/$phase.log" "$JAVA_HOME_17_X64/bin/java" "$phase_checkout" "$phase" >"$proof/$phase-java.txt" <<'PYJVM'
import os
import re
import shlex
import subprocess
import sys
log, setup_java, checkout, phase = sys.argv[1:]
allowed = {setup_java, '/usr/lib/jvm/temurin-17-jdk-amd64/bin/java'}
expected = {os.path.join(checkout, 'jenkins')}
if phase == 'green':
    expected.add(os.path.join(checkout, 'build-tool'))
with open(log, encoding='utf-8', errors='replace') as stream:
    lines = [line.strip() for line in stream if "Starting process 'Gradle Test Executor " in line]
seen = set()
for line in lines:
    if ' Working directory: ' not in line or ' Command: ' not in line:
        raise SystemExit('missing executor directory or command')
    directory = line.split(' Working directory: ', 1)[1].split(' Command: ', 1)[0]
    if directory not in expected or directory in seen:
        raise SystemExit('unexpected or duplicate test worker')
    seen.add(directory)
    args = shlex.split(line.split(' Command: ', 1)[1])
    heaps = [arg for arg in args if arg.startswith('-Xmx')]
    metas = [arg for arg in args if arg.startswith('-XX:MaxMetaspaceSize=')]
    if not args or args[0] not in allowed or set(heaps) != {'-Xmx512m'} or set(metas) != {'-XX:MaxMetaspaceSize=512m'}:
        raise SystemExit('wrong executor or memory flags')
    env = os.environ.copy()
    env.pop('JDK_JAVA_OPTIONS', None)
    env.pop('JAVA_TOOL_OPTIONS', None)
    version = subprocess.run([args[0], '-Xmx64m', '-XX:MaxMetaspaceSize=128m', '-XshowSettings:properties', '-version'], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=15, check=True).stdout
    if not re.search(r'(?m)^\s*java\.specification\.version\s*=\s*17\s*$', version):
        raise SystemExit('executor is not Java 17')
    print(directory, args[0], heaps, metas, version)
if seen != expected:
    raise SystemExit('missing test executor')
PYJVM
done

git diff --quiet
[[ "$(git write-tree)" == "$(cat "$proof/formatted-tree")" ]]
[[ "$(git branch --show-current)" == test/backend-noise-publication ]]
[[ -z "${LEFTHOOK:-}" && -z "${LEFTHOOK_EXCLUDE:-}" && -z "${LEFTHOOK_SKIP:-}" ]]
[[ -z "$(git config --get core.hooksPath || true)" ]]
lefthook install
test -x .git/hooks/pre-commit && test -x .git/hooks/commit-msg
[[ "$(direnv exec . bash -c 'command -v uv' 2>/dev/null)" == "$bootstrap/bin/uv" ]]
sha256sum -c "$proof/uv.sha256"
git config user.name 'gvy publication automation'
git config user.email '41898282+github-actions[bot]@users.noreply.github.com'
direnv exec . git commit -m 'fix(logging): reduce backend warning noise' 2>&1 | tee "$proof/hooks.log"
[[ "$(git rev-parse HEAD^)" == "$parent" ]]
[[ "$(git rev-parse 'HEAD^{tree}')" == "$(cat "$proof/formatted-tree")" ]]
[[ -z "$(git status --porcelain)" ]]
git bundle create "$proof/candidate.bundle" HEAD "^$parent"
git bundle verify "$proof/candidate.bundle"
git rev-parse HEAD >"$proof/head"
git rev-parse 'HEAD^{tree}' >"$proof/tree"
(cd "$proof" && sha256sum candidate.bundle >bundle.sha256)
