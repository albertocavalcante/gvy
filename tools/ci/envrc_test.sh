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
unset ORG_GRADLE_PROJECT_org_gradle_jvmargs
export GRADLE_OPTS='-Dorg.gradle.jvmargs="-Xmx768m -XX:MaxMetaspaceSize=384m"'
export JAVA_OPTS='-Xmx128m'
export JDK_JAVA_OPTIONS='-Xmx256m'
export GRADLE_USER_HOME='/explicit/existing/gradle-home'
PATH_add() { :; }
brew() { printf '%s\n' "${GVY_ENVRC_TEST_BREW_PREFIX}"; }
source "$1"
[[ -z "${ORG_GRADLE_PROJECT_org_gradle_jvmargs+x}" ]]
[[ "$GRADLE_OPTS" == '-Dorg.gradle.jvmargs="-Xmx768m -XX:MaxMetaspaceSize=384m"' ]]
[[ "$JAVA_OPTS" == '-Xmx128m' ]]
[[ "$JDK_JAVA_OPTIONS" == '-Xmx256m' ]]
[[ "$GRADLE_USER_HOME" == '/explicit/existing/gradle-home' ]]
PROFILE
    ) >/dev/null
done
printf '%s\n' 'The direnv profile handles nounset and preserves explicit Gradle/JVM settings.'

# Without SDKMAN, a keg-only Homebrew JDK for the .sdkmanrc major becomes JAVA_HOME.
# Arguments: keg layout holding bin/java ('' for an uninstalled formula), preset JAVA_HOME,
# expected JAVA_HOME ('' for unset), .sdkmanrc content, and whether an SDKMAN install
# without the .sdkmanrc JDK exists (then expect its warning only when no fallback is found).
check_homebrew_fallback() {
    local layout=$1 existing_java_home=$2 expected=$3 sdkmanrc=$4 sdkman=${5:-}
    local case_dir sdkman_dir=''
    case_dir=$(mktemp -d "${test_dir}/brew.XXXXXX")
    # .java-version names the test runtime; the fallback must ignore it and follow .sdkmanrc.
    printf '17\n' >"${case_dir}/.java-version"
    printf '%s' "${sdkmanrc}" >"${case_dir}/.sdkmanrc"
    mkdir -p "${case_dir}/jdk-keg"
    if [[ -n "${layout}" ]]; then
        mkdir -p "${case_dir}/jdk-keg/${layout}/bin"
        printf '#!/bin/sh\n' >"${case_dir}/jdk-keg/${layout}/bin/java"
        chmod +x "${case_dir}/jdk-keg/${layout}/bin/java"
    fi
    if [[ -n "${sdkman}" ]]; then
        sdkman_dir="${case_dir}/sdkman"
        mkdir -p "${sdkman_dir}/candidates/java"
    fi
    expected=${expected//@KEG@/${case_dir}/jdk-keg}
    local output
    output=$(
        cd "${case_dir}"
        GVY_ENVRC_TEST_HOME="${case_dir}/missing-home" GVY_ENVRC_TEST_JDK_PREFIX="${case_dir}/jdk-keg" \
            GVY_ENVRC_TEST_JAVA_HOME="${existing_java_home}" GVY_ENVRC_TEST_EXPECTED="${expected}" \
            GVY_ENVRC_TEST_SDKMAN_DIR="${sdkman_dir}" \
            "${BASH}" -u -s -- "${test_dir}/no-sdk-profile" <<'PROFILE'
unset SDKMAN_DIR JAVA_HOME
[[ -z "${GVY_ENVRC_TEST_SDKMAN_DIR}" ]] || SDKMAN_DIR="${GVY_ENVRC_TEST_SDKMAN_DIR}"
[[ -z "${GVY_ENVRC_TEST_JAVA_HOME}" ]] || export JAVA_HOME="${GVY_ENVRC_TEST_JAVA_HOME}"
PATH_add() { :; }
# An if chain, not case: bash 3.2 misparses case labels inside a heredoc in $(...).
brew() {
    if [[ "$2" == openjdk@21 ]]; then
        printf '%s\n' "${GVY_ENVRC_TEST_JDK_PREFIX}"
    else
        printf '%s\n' "${PWD}/missing-brew"
    fi
}
source "$1"
if [[ "${JAVA_HOME:-}" != "${GVY_ENVRC_TEST_EXPECTED}" ]]; then
    printf 'expected JAVA_HOME=%s, got %s\n' "${GVY_ENVRC_TEST_EXPECTED}" "${JAVA_HOME:-<unset>}" >&2
    exit 1
fi
PROFILE
    )
    if [[ -n "${sdkman}" ]]; then
        if [[ -n "${expected}" ]] && [[ "${output}" == *"not found at"* ]]; then
            printf 'SDKMAN warning printed although the Homebrew fallback succeeded\n' >&2
            return 1
        elif [[ -z "${expected}" ]] && [[ "${output}" != *"not found at"* ]]; then
            printf 'SDKMAN warning missing although no JDK was found\n' >&2
            return 1
        fi
    fi
}
check_homebrew_fallback libexec/openjdk.jdk/Contents/Home '' '@KEG@/libexec/openjdk.jdk/Contents/Home' $'java=21.0.5-tem\n'
check_homebrew_fallback libexec '' '@KEG@/libexec' $'java=21.0.5-tem\n'
check_homebrew_fallback libexec /opt/preset-jdk /opt/preset-jdk $'java=21.0.5-tem\n'
check_homebrew_fallback libexec '' '@KEG@/libexec' $'java=21.0.5-tem\r\n'
check_homebrew_fallback libexec '' '@KEG@/libexec' $'java = 21.0.5-tem \n'
check_homebrew_fallback libexec '' '@KEG@/libexec' $'java=21\n'
check_homebrew_fallback libexec '' '' $'java=17.0.2-tem\n'
check_homebrew_fallback '' '' '' $'java=21.0.5-tem\n'
check_homebrew_fallback libexec '' '@KEG@/libexec' $'java=21.0.5-tem\n' sdkman
check_homebrew_fallback '' '' '' $'java=21.0.5-tem\n' sdkman
printf '%s\n' 'The direnv profile falls back to a keg-only Homebrew JDK and respects JAVA_HOME.'
