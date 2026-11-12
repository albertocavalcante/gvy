#!/usr/bin/env bash
set -euo pipefail

script_dir=$(dirname -- "${BASH_SOURCE[0]}")
script_dir=$(cd -- "${script_dir}" && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "${test_dir}"' EXIT
mkdir -p "${test_dir}/tools/devcontainer" "${test_dir}/.devcontainer/cache" "${test_dir}/bin"
cp "${script_dir}/download-vsix.sh" "${test_dir}/tools/devcontainer/download-vsix.sh"
printf '%s\n' '{"build":{"args":{"KOTLIN_LSP_VERSION":"1.2","KOTLIN_LSP_SHA256":"expected"}}}' >"${test_dir}/.devcontainer/devcontainer.json"
printf 'cached content\n' >"${test_dir}/.devcontainer/cache/kotlin-1.2.vsix"
cat >"${test_dir}/bin/sha256sum" <<'STUB'
#!/bin/sh
echo 'simulated checksum error' >&2
exit 1
STUB
cat >"${test_dir}/bin/curl" <<'STUB'
#!/bin/sh
: "${VSIX_TEST_MARKER:?}"
touch "${VSIX_TEST_MARKER}"
exit 1
STUB
chmod +x "${test_dir}/bin/sha256sum" "${test_dir}/bin/curl"
export PATH="${test_dir}/bin:${PATH}"
export VSIX_TEST_MARKER="${test_dir}/curl-called"
if "${BASH}" "${test_dir}/tools/devcontainer/download-vsix.sh" >"${test_dir}/out" 2>&1; then
    echo 'Checksum failure must stop the download.' >&2
    exit 1
fi
if [[ ! -f "${test_dir}/.devcontainer/cache/kotlin-1.2.vsix" || -e "${VSIX_TEST_MARKER}" ]]; then
    echo 'Checksum failure removed the cache or attempted a download.' >&2
    exit 1
fi
echo 'Checksum errors preserve the cached VSIX.'
