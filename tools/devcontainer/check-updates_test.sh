#!/usr/bin/env bash
set -euo pipefail

script_dir=$(dirname -- "${BASH_SOURCE[0]}")
script_dir=$(cd -- "${script_dir}" && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "${test_dir}"' EXIT
mkdir -p "${test_dir}/bin" "${test_dir}/.devcontainer"
printf '%s\n' '{"JQ_VERSION": "1.0"}' >"${test_dir}/.devcontainer/devcontainer.json"
cat >"${test_dir}/bin/gh" <<'STUB'
#!/bin/sh
printf 'name\tLatest\tv9.9.9\n'
[ "${UPDATES_TEST_GH_FAIL-}" != yes ]
STUB
cat >"${test_dir}/bin/curl" <<'STUB'
#!/bin/sh
: "${UPDATES_TEST_MARKER:?}"
touch "${UPDATES_TEST_MARKER}"
exit 1
STUB
chmod +x "${test_dir}/bin/gh" "${test_dir}/bin/curl"
export PATH="${test_dir}/bin:${PATH}"
export UPDATES_TEST_MARKER="${test_dir}/curl-called" UPDATES_TEST_GH_FAIL=yes
if (cd "${test_dir}" && "${BASH}" "${script_dir}/check-updates.sh") >"${test_dir}/out" 2>&1; then
    echo 'Failed release lookup must fail the update check.' >&2
    exit 1
fi
if [[ -e "${UPDATES_TEST_MARKER}" ]]; then
    echo 'A failed release lookup must not start a checksum download.' >&2
    exit 1
fi
UPDATES_TEST_GH_FAIL=no
if (cd "${test_dir}" && "${BASH}" "${script_dir}/check-updates.sh") >"${test_dir}/out" 2>&1; then
    echo 'Failed checksum download must fail the update check.' >&2
    exit 1
fi
if grep -Fq 'JQ_SHA256_X64' "${test_dir}/out"; then
    echo 'Failed checksum download produced a checksum.' >&2
    exit 1
fi
echo 'Release lookup and checksum download errors are reported.'
