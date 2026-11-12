#!/usr/bin/env bash
# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "${test_dir}"' EXIT
printf 'java=missing-version\n' >"${test_dir}/.sdkmanrc"
# Also exercise the absent-SDK branch on machines with SDKMAN installed.
sed "s|\${HOME}/.sdkman|\${GVY_ENVRC_TEST_HOME}/.sdkman|g" "${repo_root}/.envrc" >"${test_dir}/no-sdk-profile"
for profile in "${repo_root}/.envrc" "${test_dir}/no-sdk-profile"; do
    (
        cd "${test_dir}"
        GVY_ENVRC_TEST_HOME="${test_dir}/missing-home" GVY_ENVRC_TEST_BREW_PREFIX="${test_dir}/missing-brew" \
            "${BASH}" -u -s -- "${profile}" <<'PROFILE'
unset SDKMAN_DIR
PATH_add() { :; }
brew() { printf '%s\n' "${GVY_ENVRC_TEST_BREW_PREFIX}"; }
source "$1"
[[ "${ORG_GRADLE_PROJECT_org_gradle_jvmargs}" == *"-Xmx3g"* ]]
PROFILE
    ) >/dev/null
done
printf '%s\n' 'The direnv profile handles an unset SDKMAN_DIR under nounset.'
