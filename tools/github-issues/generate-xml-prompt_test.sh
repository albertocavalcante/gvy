#!/usr/bin/env bash
set -euo pipefail

script_dir=$(dirname -- "${BASH_SOURCE[0]}")
script_dir=$(cd -- "${script_dir}" && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "${test_dir}"' EXIT

cat >"${test_dir}/gh" <<'STUB'
#!/bin/sh
printf '%s\n' '{"number":1,"title":"Example","body":"Issue body","url":"https://example.invalid/1","labels":"bug"}'
STUB
cat >"${test_dir}/codex" <<'STUB'
#!/bin/sh
: "${SPDX_TEST_CAPTURE:?}"
cat > "${SPDX_TEST_CAPTURE}"
while [ "$#" -gt 0 ]; do
    if [ "$1" = --output-last-message ]; then
        shift
        printf '<prompt/>\n' > "$1"
        exit 0
    fi
    shift
done
exit 1
STUB
chmod +x "${test_dir}/gh" "${test_dir}/codex"
export PATH="${test_dir}:${PATH}"
export SPDX_TEST_CAPTURE="${test_dir}/prompt"
bash "${script_dir}/generate-xml-prompt.sh" 1 >"${test_dir}/stdout" 2>"${test_dir}/stderr"
grep -Fq 'start with ```xml' "${SPDX_TEST_CAPTURE}"
grep -Fq 'end with ```.' "${SPDX_TEST_CAPTURE}"
test ! -s "${test_dir}/stderr"
grep -Fxq '<prompt/>' "${test_dir}/stdout"
printf '%s\n' 'Prompt fences remain literal and no commands run.'
