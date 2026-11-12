#!/usr/bin/env bash
# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

script_dir=$(dirname -- "${BASH_SOURCE[0]}")
script_dir=$(cd -- "${script_dir}" && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "${test_dir}"' EXIT
mkdir -p "${test_dir}/bin" "${test_dir}/scripts"
cp "${script_dir}/setup-labels.sh" "${test_dir}/scripts/setup-labels.sh"
printf '%s\n' '[{"name":"one","color":"aaaaaa","description":"first"},{"name":"two","color":"bbbbbb","description":"second"}]' >"${test_dir}/scripts/github-labels.json"
cat >"${test_dir}/bin/gh" <<'STUB'
#!/bin/sh
: "${LABEL_TEST_LOG:?}"
case "$1 $2" in
    'auth status' | 'repo view') exit 0 ;;
    'label list') if [ "${LABEL_TEST_DEFAULTS-}" = yes ]; then printf '[{"name":"duplicate"}]\n'; else printf '[]\n'; fi; [ "${LABEL_TEST_FAIL-}" != yes ] ;;
    'label create' | 'label edit' | 'label delete') printf '%s\n' "$*" >> "${LABEL_TEST_LOG}" ;;
    *) echo "unexpected gh command" >&2; exit 1 ;;
esac
STUB
chmod +x "${test_dir}/bin/gh"
export PATH="${test_dir}/bin:${PATH}"
export LABEL_TEST_LOG="${test_dir}/calls" LABEL_TEST_FAIL=yes
if "${BASH}" "${test_dir}/scripts/setup-labels.sh" --groovy-lsp-only >"${test_dir}/out" 2>&1; then
    echo 'Failed label listing must stop the operation.' >&2
    exit 1
fi
if [[ -e "${LABEL_TEST_LOG}" ]]; then
    echo 'Failed label listing led to a label mutation.' >&2
    exit 1
fi
LABEL_TEST_FAIL=no
"${BASH}" "${test_dir}/scripts/setup-labels.sh" --groovy-lsp-only >"${test_dir}/out" 2>&1
count=$(wc -l <"${LABEL_TEST_LOG}")
[[ "${count}" -eq 2 ]]

rm "${LABEL_TEST_LOG}"
printf '%s\n' 'not json' >"${test_dir}/scripts/github-labels.json"
if "${BASH}" "${test_dir}/scripts/setup-labels.sh" --groovy-lsp-only >"${test_dir}/out" 2>&1; then
    echo 'Malformed label metadata must stop the operation.' >&2
    exit 1
fi
[[ ! -e "${LABEL_TEST_LOG}" ]]
# Invalid records must be rejected before even existing default labels are removed.
LABEL_TEST_DEFAULTS=yes
export LABEL_TEST_DEFAULTS
for invalid in '' 'null' '{}' '[{"name":"one","color":"aaaaaa","description":"first"},42]' \
    '[{"name":"one","color":"nothex","description":"first"}]' \
    '[{"name":"one","color":"aaaaaa"}]' '[{"name":null,"color":"aaaaaa","description":"first"}]' \
    '[] []'; do
    printf '%s' "${invalid}" >"${test_dir}/scripts/github-labels.json"
    if "${BASH}" "${test_dir}/scripts/setup-labels.sh" --groovy-lsp-only >"${test_dir}/out" 2>&1; then
        echo "Invalid label schema unexpectedly succeeded: ${invalid}" >&2
        exit 1
    fi
    [[ ! -e "${LABEL_TEST_LOG}" ]] || {
        echo 'Invalid input triggered mutation.' >&2
        exit 1
    }
done
LABEL_TEST_DEFAULTS=no
printf '%s\n' '[]' >"${test_dir}/scripts/github-labels.json"
"${BASH}" "${test_dir}/scripts/setup-labels.sh" --groovy-lsp-only >"${test_dir}/out" 2>&1
[[ ! -e "${LABEL_TEST_LOG}" ]]
echo 'Label listing failures stop mutations and both valid labels are processed.'
