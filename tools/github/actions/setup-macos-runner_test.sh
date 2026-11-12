#!/usr/bin/env bash
# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

script_dir=$(dirname -- "${BASH_SOURCE[0]}")
script_dir=$(cd -- "${script_dir}" && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "${test_dir}"' EXIT
mkdir -p "${test_dir}/tools/github/actions" "${test_dir}/bin"
cp "${script_dir}/setup-macos-runner.sh" "${test_dir}/tools/github/actions/setup-macos-runner.sh"
cat >"${test_dir}/bin/curl" <<'STUB'
#!/bin/sh
: "${RUNNER_TEST_FILENAME:?}" "${RUNNER_TEST_MARKER:?}"
case "$*" in
    *api.github.com*)
        printf '{"assets":[{"name":"%s","digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}\n' "${RUNNER_TEST_FILENAME}"
        exit 22
        ;;
    *) touch "${RUNNER_TEST_MARKER}"; exit 1 ;;
esac
STUB
chmod +x "${test_dir}/bin/curl"
arch=$(uname -m)
case "${arch}" in
    arm64) runner_arch=arm64 ;;
    x86_64) runner_arch=x64 ;;
    *)
        echo 'Unsupported test architecture' >&2
        exit 1
        ;;
esac
export PATH="${test_dir}/bin:${PATH}"
export RUNNER_TEST_FILENAME="actions-runner-osx-${runner_arch}-2.329.0.tar.gz"
export RUNNER_TEST_MARKER="${test_dir}/download-called"
if printf 'n\n' | "${BASH}" "${test_dir}/tools/github/actions/setup-macos-runner.sh" --no-svc --name test --dir "${test_dir}/runner" test-token >"${test_dir}/out" 2>&1; then
    echo 'Failed checksum lookup must not continue without confirmation.' >&2
    exit 1
fi
if [[ -e "${RUNNER_TEST_MARKER}" ]]; then
    echo 'Download started after a failed checksum lookup.' >&2
    exit 1
fi
grep -Fq 'Could not fetch checksum' "${test_dir}/out"
echo 'Runner download does not trust output from a failed checksum lookup.'
