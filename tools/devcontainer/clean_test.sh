#!/usr/bin/env bash
set -euo pipefail

script_dir=$(dirname -- "${BASH_SOURCE[0]}")
script_dir=$(cd -- "${script_dir}" && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "${test_dir}"' EXIT
mkdir -p "${test_dir}/bin" "${test_dir}/work"
cat >"${test_dir}/bin/docker" <<'STUB'
#!/bin/sh
: "${CLEAN_TEST_LOG:?}"
printf '%s\n' "$*" >> "${CLEAN_TEST_LOG}"
case "$1 $2" in
    'ps -a') printf '%s' "${MOCK_CONTAINERS-}" ;;
    'volume ls') printf '%s' "${MOCK_VOLUMES-}" ;;
esac
STUB
chmod +x "${test_dir}/bin/docker"
export PATH="${test_dir}/bin:${PATH}"
export CLEAN_TEST_LOG="${test_dir}/calls"
export MOCK_CONTAINERS="" MOCK_VOLUMES=""

run_clean() {
    local answer=$1
    (cd "${test_dir}/work" && printf '%s\n' "${answer}" | bash "${script_dir}/clean.sh") >"${test_dir}/output" 2>&1
}

# No matches must succeed without a removal or confirmation.
run_clean n
if grep -Eq '^(rm|volume rm) ' "${CLEAN_TEST_LOG}"; then
    echo 'No-match cleanup attempted a removal.' >&2
    exit 1
fi

# Two IDs must remain separate Docker arguments, and rejection keeps volumes.
: >"${CLEAN_TEST_LOG}"
MOCK_CONTAINERS=$'c1\nc2\n'
MOCK_VOLUMES=$'vsc-work-a\nvsc-work-b\nunrelated\n'
run_clean n
grep -Fxq 'rm -f c1 c2' "${CLEAN_TEST_LOG}"
if grep -q '^volume rm ' "${CLEAN_TEST_LOG}"; then
    echo 'Rejected volume removal was executed.' >&2
    exit 1
fi

# A single container and confirmed matching volumes use separate arguments.
: >"${CLEAN_TEST_LOG}"
MOCK_CONTAINERS=$'c1\n'
run_clean y
grep -Fxq 'rm -f c1' "${CLEAN_TEST_LOG}"
grep -Fxq 'volume rm vsc-work-a vsc-work-b' "${CLEAN_TEST_LOG}"

: >"${CLEAN_TEST_LOG}"
MOCK_CONTAINERS=""
MOCK_VOLUMES=$'vsc-work-a\n'
run_clean y
grep -Fxq 'volume rm vsc-work-a' "${CLEAN_TEST_LOG}"
echo 'Cleanup preserves Docker arguments and honors confirmation.'
