#!/usr/bin/env bash
# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

script_dir=$(dirname -- "${BASH_SOURCE[0]}")
script_dir=$(cd -- "${script_dir}" && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "${test_dir}"' EXIT
mkdir -p "${test_dir}/tools/editor" "${test_dir}/.devcontainer" "${test_dir}/bin"
cp "${script_dir}/install-kotlin-lsp" "${test_dir}/tools/editor/install-kotlin-lsp"
cat >"${test_dir}/bin/curl" <<'STUB'
#!/bin/sh
case "$*" in
    *.sha256*) printf 'untrusted\n'; exit 22 ;;
esac
while [ "$#" -gt 0 ]; do
    if [ "$1" = -o ]; then shift; printf 'payload\n' > "$1"; exit 0; fi
    shift
done
exit 1
STUB
cat >"${test_dir}/bin/sha256sum" <<'STUB'
#!/bin/sh
printf 'untrusted  file\n'
STUB
cat >"${test_dir}/bin/test-editor" <<'STUB'
#!/bin/sh
: "${LSP_TEST_MARKER:?}"
touch "${LSP_TEST_MARKER}"
STUB
chmod +x "${test_dir}/bin/curl" "${test_dir}/bin/sha256sum" "${test_dir}/bin/test-editor"
export PATH="${test_dir}/bin:${PATH}"
export LSP_TEST_MARKER="${test_dir}/installed"
if "${BASH}" "${test_dir}/tools/editor/install-kotlin-lsp" --version 1.2.3 --no-update-config test-editor >"${test_dir}/out" 2>&1; then
    echo 'A failed checksum fetch must fail the install.' >&2
    exit 1
fi
if [[ -e "${LSP_TEST_MARKER}" ]]; then
    echo 'The editor was invoked despite a failed checksum fetch.' >&2
    exit 1
fi
echo 'A failed checksum fetch cannot install an extension.'
