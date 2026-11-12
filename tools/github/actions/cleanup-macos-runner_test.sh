#!/usr/bin/env bash
set -euo pipefail

script_dir=$(dirname -- "${BASH_SOURCE[0]}")
script_dir=$(cd -- "${script_dir}" && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "${test_dir}"' EXIT
export HOME="${test_dir}"
script="${script_dir}/cleanup-macos-runner.sh"

"${BASH}" "${script}" --path "${test_dir}/runner-*" --skip-unregister >"${test_dir}/out"
grep -Fq 'No runner directories found' "${test_dir}/out"
mkdir "${test_dir}/runner-one" "${test_dir}/runner-two"
printf 'n\n' | "${BASH}" "${script}" --path "${test_dir}/runner-*" --skip-unregister >"${test_dir}/out"
[[ -d "${test_dir}/runner-one" && -d "${test_dir}/runner-two" ]]
printf 'y\n' | "${BASH}" "${script}" --path "${test_dir}/runner-*" --skip-unregister >"${test_dir}/out"
[[ ! -e "${test_dir}/runner-one" && ! -e "${test_dir}/runner-two" ]]
echo 'Runner cleanup handles no matches and confirmation with Bash 3.'
