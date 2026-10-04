#!/usr/bin/env bash
# Self-test for check-large-files.sh: proves the gate can fail, and that it
# reports a non-zero examined count so a silent no-op run is visible.
set -euo pipefail

script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-large-files.sh"
tmp=$(mktemp -d)
trap 'rm -rf "${tmp}"' EXIT

fails=0
# assert <description> <command...>: passes when the command succeeds.
assert() {
  local desc=$1
  shift
  if "$@"; then
    echo "ok   - ${desc}"
  else
    echo "FAIL - ${desc}"
    fails=$((fails + 1))
  fi
}
# refute <description> <command...>: passes when the command fails.
refute() {
  local desc=$1
  shift
  if "$@"; then
    echo "FAIL - ${desc}"
    fails=$((fails + 1))
  else
    echo "ok   - ${desc}"
  fi
}

git -C "${tmp}" init -q -b main
git_() { git -C "${tmp}" -c user.name=t -c user.email=t@example.com -c commit.gpgsign=false "$@"; }

echo base >"${tmp}/base.txt"
git_ add base.txt
git_ commit -q -m base

# Runs the gate (threshold 1000 bytes) against main; captures output and status.
run() {
  rc=0
  out=$(cd "${tmp}" && bash "${script}" main 1000 2>&1) || rc=$?
}

# (a) a large file must fail the gate and be reported
git_ checkout -q -b big
head -c 2000 /dev/zero >"${tmp}/big.bin"
head -c 10 /dev/zero >"${tmp}/small.bin"
git_ add big.bin small.bin
git_ commit -q -m big
run
assert "large file: exits non-zero" test "${rc}" -ne 0
assert "large file: reported" grep -q 'VIOLATION: big.bin' <<<"${out}"
refute "large file: small file not reported" grep -q 'VIOLATION: small.bin' <<<"${out}"
assert "large file: examined count > 0" grep -Eq 'checked [1-9][0-9]* files' <<<"${out}"

# (b) only small files must pass
git_ checkout -q main
git_ checkout -q -b small
head -c 10 /dev/zero >"${tmp}/ok1.bin"
head -c 999 /dev/zero >"${tmp}/ok2.bin"
git_ add ok1.bin ok2.bin
git_ commit -q -m small
run
assert "small files: exits zero" test "${rc}" -eq 0
assert "small files: examined count > 0" grep -Eq 'checked [1-9][0-9]* files' <<<"${out}"

# (c) a renamed-and-grown file, a symlink replaced by a large file, and a
# non-ASCII path must all be checked
git_ checkout -q main
seq 1 200 >"${tmp}/grow.bin" # ~700 bytes, under the threshold
ln -s base.txt "${tmp}/link"
git_ add grow.bin link
git_ commit -q -m seed
git_ checkout -q -b edge
git_ mv grow.bin grown.bin
seq 201 300 >>"${tmp}/grown.bin" # still similar, so git reports a rename (R)
rm "${tmp}/link"
head -c 2000 /dev/zero >"${tmp}/link"
head -c 2000 /dev/zero >"${tmp}/café.bin"
git_ add -A
git_ commit -q -m edge
status=$(git_ diff --name-status main...HEAD)
assert "edge: git sees a rename" grep -q "^R" <<<"${status}"
run
assert "edge: exits non-zero" test "${rc}" -ne 0
assert "edge: renamed file reported" grep -q 'VIOLATION: grown.bin' <<<"${out}"
assert "edge: symlink-to-file reported" grep -q 'VIOLATION: link ' <<<"${out}"
assert "edge: non-ASCII path reported" grep -q 'VIOLATION: café.bin' <<<"${out}"

if [[ "${fails}" -gt 0 ]]; then
  echo "${fails} assertion(s) failed"
  exit 1
fi
echo "all assertions passed"
