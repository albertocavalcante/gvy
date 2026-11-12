#!/usr/bin/env bash
set -euo pipefail

script_dir=$(dirname -- "${BASH_SOURCE[0]}")
script_dir=$(cd -- "${script_dir}" && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "${test_dir}"' EXIT
mkdir -p "${test_dir}/bin" "${test_dir}/home"

for command in pkill killall open docker; do
    cat >"${test_dir}/bin/${command}" <<'STUB'
#!/bin/sh
: "${RESET_TEST_MARKER:?}"
printf '%s\n' "$0" >> "${RESET_TEST_MARKER}"
exit 0
STUB
    chmod +x "${test_dir}/bin/${command}"
done
export PATH="${test_dir}/bin:${PATH}"
export HOME="${test_dir}/home"
export RESET_TEST_MARKER="${test_dir}/calls"

if printf '%s\n' '\RESET' | bash "${script_dir}/factory-reset-docker-macos.sh" >"${test_dir}/out" 2>&1; then
    echo 'Backslash-prefixed confirmation must be rejected.' >&2
    exit 1
fi
if [[ -e "${RESET_TEST_MARKER}" ]]; then
    echo 'Rejected confirmation ran a reset command.' >&2
    exit 1
fi
printf '%s\n' 'RESET' | bash "${script_dir}/factory-reset-docker-macos.sh" >"${test_dir}/out" 2>&1
[[ -s "${RESET_TEST_MARKER}" ]]
echo 'Reset requires an exact confirmation.'
