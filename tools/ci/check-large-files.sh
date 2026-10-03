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

# Capture first so a bad base ref fails loudly (set -e), then feed the loop via a
# here-string: it runs in the current shell, so the counters survive.
files=$(git diff --name-only --diff-filter=AM "${base_ref}...HEAD")
while IFS= read -r file; do
  [[ -f "${file}" ]] || continue
  checked=$((checked + 1))
  size=$(wc -c <"${file}" | tr -d ' ')
  if [[ "${size}" -gt "${max_size}" ]]; then
    violations=$((violations + 1))
    hs=$(human "${size}")
    echo "VIOLATION: ${file} (${hs}, ${size} bytes) exceeds ${max_size} bytes"
    report="${report}| \`${file}\` | ${hs} |"$'\n'
  fi
done <<<"${files}"

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
