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
# A nonexistent literal or regular file is not a runner directory.
"${BASH}" "${script}" --path "${test_dir}/absent" --skip-unregister </dev/null >"${test_dir}/out"
grep -Fq 'No runner directories found' "${test_dir}/out"
printf 'file' >"${test_dir}/runner-file"
"${BASH}" "${script}" --path "${test_dir}/runner-file" --skip-unregister </dev/null >"${test_dir}/out"
grep -Fq 'No runner directories found' "${test_dir}/out"
rm "${test_dir}/runner-file"
# --all must continue to cache cleanup without prompting for a nonexistent runner.
mkdir -p "${test_dir}/isolated/tools/github/actions" "${test_dir}/isolated/.github/.cache/runners"
cp "${script}" "${test_dir}/isolated/tools/github/actions/cleanup-macos-runner.sh"
printf 'fixture' >"${test_dir}/isolated/.github/.cache/runners/fixture"
"${BASH}" "${test_dir}/isolated/tools/github/actions/cleanup-macos-runner.sh" \
    --path "${test_dir}/absent" --all --skip-unregister </dev/null >"${test_dir}/out"
grep -Fq 'No runner directories found' "${test_dir}/out"
grep -Fq 'Cache removed.' "${test_dir}/out"
[[ ! -e "${test_dir}/isolated/.github/.cache/runners" ]]
newline_dir="${test_dir}/runner-three"$'\n''four'
space_dir="${test_dir}/runner-with spaces"
mkdir "${test_dir}/runner-one" "${test_dir}/runner-two" "${newline_dir}" "${space_dir}"
printf 'n\n' | "${BASH}" "${script}" --path "${test_dir}/runner-*" --skip-unregister >"${test_dir}/out"
[[ -d "${test_dir}/runner-one" && -d "${test_dir}/runner-two" && -d "${newline_dir}" && -d "${space_dir}" ]]
printf 'y\n' | "${BASH}" "${script}" --path "${test_dir}/runner-*" --skip-unregister >"${test_dir}/out"
[[ ! -e "${test_dir}/runner-one" && ! -e "${test_dir}/runner-two" && ! -e "${newline_dir}" && ! -e "${space_dir}" ]]
echo 'Runner cleanup handles no matches and confirmation with Bash 3.'
