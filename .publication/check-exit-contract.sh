#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail
[[ $# -eq 3 ]]
worker=${1:?}
previous=${2:?}
proof=${3:?}
[[ -f ${worker} && -f ${previous} && ! -e ${proof} ]]
mkdir -p "${proof}"
# Read the actual pinned function, stopping at its caller after the closing heredoc/function.
awk '
    /^export_preparation\(\) \{$/ { active = 1 }
    active && /^trap export_preparation EXIT$/ { exit }
    active { print }
' "${worker}" >"${proof}/actual-function.sh"
awk '
    /^export_preparation\(\) \{$/ { active = 1 }
    active && /^trap export_preparation EXIT$/ { exit }
    active { print }
' "${previous}" >"${proof}/previous-function.sh"
exit_lines=$(grep -Fxc "    exit \"\${status}\"" "${proof}/actual-function.sh")
[[ ${exit_lines} -eq 1 ]]
sed "s/^    exit \"\${status}\"$/    return \"\${status}\"/" "${proof}/actual-function.sh" >"${proof}/return-mutation.sh"
cat >"${proof}/case.sh" <<'CASE'
#!/usr/bin/env bash
set -euo pipefail
node() { return "${CONTROL_EXPORT_STATUS:?}"; }
git() { :; }
# Keep any nested Bash command inside this synthetic fixture as well.
export -f node git
# The owned function file is generated above from the checksum-pinned worker.
# shellcheck source=inspect-and-package.sh
source "${CONTROL_FUNCTION_FILE:?}"
cd "${CONTROL_WORKSPACE:?}"
proof=${CONTROL_OUTPUT:?}
base=synthetic-base
bazel_started=true
bazel_command=(bash -c "exit \"\${CONTROL_SHUTDOWN_STATUS:?}\"" --)
bazel_startup=()
case "${CONTROL_WRITE_FAULT:?}" in
    none) ;;
    shutdown-status) mkdir "${proof}/bazel-shutdown-status.json" ;;
    preparation-status) mkdir "${proof}/preparation-status.json" ;;
    *) exit 64 ;;
esac
trap export_preparation EXIT
exit "${CONTROL_BODY_STATUS:?}"
CASE
run_case() {
    local name=${1:?} function_file=${2:?} body=${3:?} shutdown=${4:?} export_code=${5:?}
    local expected=${6:?} kind=${7:?} fault=${8:-none} actual reported shutdown_write
    local workspace="${proof}/${name}"
    mkdir "${workspace}" "${workspace}/output"
    printf '{}\n' >"${workspace}/MODULE.bazel.lock"
    set +e
    CONTROL_FUNCTION_FILE="${function_file}" CONTROL_WORKSPACE="${workspace}" CONTROL_OUTPUT="${workspace}/output" \
        CONTROL_BODY_STATUS="${body}" CONTROL_SHUTDOWN_STATUS="${shutdown}" CONTROL_EXPORT_STATUS="${export_code}" \
        CONTROL_WRITE_FAULT="${fault}" \
        bash "${proof}/case.sh" >"${workspace}/case.log" 2>&1
    actual=$?
    set -e
    # Both historical handlers must expose false success in the specific failing control.
    if [[ ${kind} != none ]]; then
        [[ ${actual} -eq 0 && ${expected} -ne 0 ]]
    else
        [[ ${actual} -eq ${expected} ]]
    fi
    reported=${expected}
    if [[ ${kind} == previous ]]; then reported=0; fi
    shutdown_write=0
    if [[ ${kind} == none && ${fault} == shutdown-status ]]; then shutdown_write=1; fi
    if [[ ${fault} == preparation-status ]]; then
        [[ -d ${workspace}/output/preparation-status.json ]]
        grep -F preparation-status.json "${workspace}/case.log" >/dev/null
        reported=null
    else
        jq -e --arg kind "${kind}" --argjson body "${body}" --argjson shutdown "${shutdown}" --argjson export_code "${export_code}" \
            --argjson final "${reported}" --argjson write_code "${shutdown_write}" \
            '.bodyExitCode == $body and .shutdownExitCode == $shutdown and .exportExitCode == $export_code and .commandExitCode == $final and (if $kind == "previous" then (.shutdownStatusWriteExitCode // 0) else .shutdownStatusWriteExitCode end) == $write_code' \
            "${workspace}/output/preparation-status.json" >/dev/null
    fi
    printf '{"name":"%s","bodyExitCode":%s,"shutdownExitCode":%s,"exportExitCode":%s,"expectedExitCode":%s,"actualExitCode":%s,"reportedExitCode":%s,"controlKind":"%s","writeFault":"%s"}\n' \
        "${name}" "${body}" "${shutdown}" "${export_code}" "${expected}" "${actual}" "${reported}" "${kind}" "${fault}" \
        >>"${proof}/cases.jsonl"
}
run_case return-mutation "${proof}/return-mutation.sh" 0 7 0 7 return
run_case previous-shutdown-status-write "${proof}/previous-function.sh" 0 0 0 1 previous shutdown-status
run_case previous-final-status-write "${proof}/previous-function.sh" 0 0 0 1 previous preparation-status
run_case healthy "${proof}/actual-function.sh" 0 0 0 0 none
run_case body-failure "${proof}/actual-function.sh" 2 0 0 2 none
run_case shutdown-failure "${proof}/actual-function.sh" 0 7 0 7 none
run_case body-precedes-shutdown "${proof}/actual-function.sh" 2 7 0 2 none
run_case export-failure "${proof}/actual-function.sh" 0 0 9 9 none
run_case shutdown-status-write "${proof}/actual-function.sh" 0 0 0 1 none shutdown-status
run_case final-status-write "${proof}/actual-function.sh" 0 0 0 1 none preparation-status
run_case body-precedes-shutdown-status-write "${proof}/actual-function.sh" 2 0 0 2 none shutdown-status
run_case body-precedes-final-status-write "${proof}/actual-function.sh" 2 0 0 2 none preparation-status
jq -s '{mutationControls: map(select(.controlKind != "none")), passingControls: map(select(.controlKind == "none"))}' \
    "${proof}/cases.jsonl" >"${proof}/exit-contract.json"
sha256sum "${worker}" "${previous}" "${proof}/actual-function.sh" "${proof}/previous-function.sh" \
    "${proof}/return-mutation.sh" "${proof}/exit-contract.json" \
    >"${proof}/files.sha256"
