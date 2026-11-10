#!/usr/bin/env bash
# Self-test for check-conflict-markers.sh: proves each marker form is caught,
# that clean and documentation-only cases pass, and that --staged works.
set -euo pipefail

script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-conflict-markers.sh"
tmp=$(mktemp -d)
trap 'rm -rf "${tmp}"' EXIT

fails=0
assert() {
  local desc=$1
  shift
  if "$@" >/dev/null 2>&1; then
    echo "ok   - ${desc}"
  else
    echo "FAIL - ${desc}"
    fails=$((fails + 1))
  fi
}
refute() {
  local desc=$1
  shift
  if "$@" >/dev/null 2>&1; then
    echo "FAIL - ${desc}"
    fails=$((fails + 1))
  else
    echo "ok   - ${desc}"
  fi
}

git -C "${tmp}" init -q -b main
git_() { git -C "${tmp}" -c user.name=t -c user.email=t@example.com -c commit.gpgsign=false "$@"; }
check() { (cd "${tmp}" && bash "${script}" "$@"); }

echo base >"${tmp}/base.txt"
git_ add base.txt
git_ commit -q -m base

# Commit a file with the given content on a branch and check it against main.
branch_with() {
  local name=$1 file=$2 content=$3
  git_ checkout -q -b "${name}" main
  printf '%b' "${content}" >"${tmp}/${file}"
  git_ add "${file}"
  git_ commit -q -m "${name}"
}

branch_with clean T.kt 'fun a() = 1\n'
assert "clean change passes" check main

branch_with open T.kt 'x\n<<<<<<< HEAD\nmine\n'
refute "<<<<<<< is caught" check main
branch_with base T.kt 'x\n||||||| base\norig\n'
refute "||||||| (diff3 base) is caught" check main
branch_with close T.kt 'x\n>>>>>>> branch\n'
refute ">>>>>>> is caught" check main
branch_with sep T.kt 'x\n=======\ny\n'
refute "bare ======= in code is caught" check main
branch_with full T.kt 'x\n<<<<<<< HEAD\na\n||||||| b\nb\n=======\nc\n>>>>>>> br\n'
refute "full diff3 block is caught" check main

branch_with setext README.md 'Title\n=======\ntext\n'
assert "heading underline in markdown passes" check main
branch_with docmarker README.md 'Title\n=======\n<<<<<<< HEAD\n'
refute "real markers in markdown are caught" check main

branch_with eight T.kt 'a\n======== not a marker\n<<<<<<<<< also not\n'
assert "longer runs and mid-line text pass" check main

# Staged mode on the index, before anything is committed.
git_ checkout -q main
printf 'x\n<<<<<<< HEAD\n' >"${tmp}/S.kt"
git_ add S.kt
refute "--staged catches staged markers" check --staged
git_ reset -q S.kt
assert "--staged passes when nothing is staged" check --staged

if [[ "${fails}" -gt 0 ]]; then
  echo "${fails} self-test failure(s)"
  exit 1
fi
echo "all self-tests passed"
