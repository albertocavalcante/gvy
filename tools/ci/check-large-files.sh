#!/usr/bin/env bash
# Fail if a PR adds or modifies a file larger than a size threshold.
#
# Usage: check-large-files.sh <base-ref> [max-bytes]
# Env:   COMMENT_FILE  if set, a markdown report is written there on violation.
set -euo pipefail

base_ref="${1:?usage: check-large-files.sh <base-ref> [max-bytes]}"
max_size="${2:-716800}" # 700KB

human() {
  awk -v b="$1" 'BEGIN { split("B KiB MiB GiB", u, " "); i = 1; while (b >= 1024 && i < 4) { b /= 1024; i++ } printf (i == 1 ? "%d%s" : "%.1f%s"), b, u[i] }'
}

checked=0
violations=0
report=""

# Capture to a file first so a bad base ref fails loudly (set -e). Paths are
# NUL-delimited (-z) so unusual names are not quoted, and every status except
# deletions (d) is checked, including renames and type changes. Reading from a
# file keeps the loop in the current shell, so the counters survive.
paths=$(mktemp)
trap 'rm -f "${paths}"' EXIT
git diff -z --name-only --diff-filter=d "${base_ref}...HEAD" >"${paths}"
while IFS= read -r -d '' file; do
  [[ -f "${file}" ]] || continue
  checked=$((checked + 1))
  size=$(wc -c <"${file}" | tr -d ' ')
  if [[ "${size}" -gt "${max_size}" ]]; then
    violations=$((violations + 1))
    hs=$(human "${size}")
    echo "VIOLATION: ${file} (${hs}, ${size} bytes) exceeds ${max_size} bytes"
    report="${report}| \`${file}\` | ${hs} |"$'\n'
  fi
done <"${paths}"

echo "checked ${checked} files"

if [[ "${violations}" -gt 0 ]]; then
  if [[ -n "${COMMENT_FILE:-}" ]]; then
    limit=$(human "${max_size}")
    {
      printf '### ⚠️ Large File Detected\n\nThe following files exceed the %s limit:\n\n| File | Size |\n|---|---|\n%s\n' "${limit}" "${report}"
      printf 'Please remove them, reduce their size, or use Git LFS.\n'
    } >"${COMMENT_FILE}"
  fi
  exit 1
fi
