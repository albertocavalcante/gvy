#!/usr/bin/env bash
# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

script_dir=$(dirname -- "${BASH_SOURCE[0]}")
script_dir=$(cd -- "${script_dir}" && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "${test_dir}"' EXIT
mkdir "${test_dir}/bin"
cat >"${test_dir}/bin/curl" <<'STUB'
#!/bin/sh
case "${RUNNER_TEST_RESPONSE-}" in
    fail) echo 'offline' >&2; exit 22 ;;
    invalid) printf 'not json\n' ;;
    *) printf '{"runners":[]}\n' ;;
esac
STUB
chmod +x "${test_dir}/bin/curl"
export PATH="${test_dir}/bin:${PATH}"
export GITHUB_REPOSITORY=owner/repo GITHUB_TOKEN=test LABEL_NAME=test MODE=check
export GITHUB_OUTPUT="${test_dir}/output" GITHUB_STEP_SUMMARY="${test_dir}/summary"
export RUNNER_TEST_RESPONSE=fail
if "${BASH}" "${script_dir}/runner-status.sh" >"${test_dir}/log" 2>&1; then
    echo 'API failure was not reported.' >&2
    exit 1
fi
grep -Fq 'Failed to fetch runners' "${test_dir}/log"
export RUNNER_TEST_RESPONSE=invalid
if "${BASH}" "${script_dir}/runner-status.sh" >"${test_dir}/log" 2>&1; then
    echo 'Invalid API JSON was accepted.' >&2
    exit 1
fi
export RUNNER_TEST_RESPONSE=empty
"${BASH}" "${script_dir}/runner-status.sh" >"${test_dir}/log" 2>&1
grep -Fxq 'runner_count=0' "${GITHUB_OUTPUT}"
grep -Fxq 'has_runners=false' "${GITHUB_OUTPUT}"
echo 'Runner check rejects API failures and malformed JSON.'
