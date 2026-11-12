#!/bin/sh
# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

set -eu

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <app-bundle> <installation-directory>" >&2
    exit 2
fi

source_app=$1
install_dir=$2
script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
rename_helper="${script_dir}/rename-exclusive.js"
if [ ! -r "${rename_helper}" ] || ! command -v osascript >/dev/null 2>&1; then
    echo "The macOS exclusive-rename helper is unavailable" >&2
    exit 1
fi
if ! osascript -l JavaScript "${rename_helper}" --check; then
    echo "Cannot initialize the macOS exclusive-rename helper" >&2
    exit 1
fi
if [ ! -d "${source_app}" ] || [ -L "${source_app}" ] || [ ! -f "${source_app}/Contents/Info.plist" ]; then
    echo "Invalid application bundle: ${source_app}" >&2
    exit 1
fi
if [ ! -d "${install_dir}" ]; then
    echo "Installation directory does not exist: ${install_dir}" >&2
    exit 1
fi

app_name=$(basename "${source_app}")
case "${app_name}" in
    *.app) ;;
    *)
        echo "Expected an .app bundle: ${source_app}" >&2
        exit 1
        ;;
esac
target="${install_dir}/${app_name}"
lock="${install_dir}/.gvy-visualizer-install.lock"
if ! mkdir "${lock}"; then
    echo "Cannot acquire install lock in ${install_dir}; check permissions or another install" >&2
    exit 1
fi

stage=
backup_pending=0
cleanup() {
    result=$?
    trap - 0 HUP INT TERM
    keep_stage=0
    if [ "${backup_pending}" -eq 1 ] && { [ -e "${stage}/previous/${app_name}" ] || [ -L "${stage}/previous/${app_name}" ]; }; then
        if [ -e "${target}" ] || [ -L "${target}" ]; then
            echo "Cannot restore the previous app because the target exists; backup: ${stage}/previous/${app_name}" >&2
            keep_stage=1
        elif osascript -l JavaScript "${rename_helper}" "${stage}/previous/${app_name}" "${target}"; then
            echo "Restored previous app: ${target}" >&2
        else
            echo "Could not restore previous app; backup: ${stage}/previous/${app_name}" >&2
            keep_stage=1
        fi
    fi
    if [ "${keep_stage}" -eq 0 ] && [ -n "${stage}" ]; then
        if ! rm -rf "${stage}"; then
            echo "Could not remove install staging directory: ${stage}" >&2
            result=1
        fi
    fi
    if ! rmdir "${lock}"; then
        echo "Could not remove install lock: ${lock}" >&2
        result=1
    fi
    exit "${result}"
}
trap cleanup 0
trap 'exit 1' HUP INT TERM

if ! stage=$(mktemp -d "${install_dir}/.gvy-visualizer-stage.XXXXXX"); then
    echo "Cannot create install staging directory in ${install_dir}" >&2
    exit 1
fi
mkdir "${stage}/incoming" "${stage}/previous"
if ! cp -R "${source_app}" "${stage}/incoming/${app_name}"; then
    echo "Could not copy the replacement app; the existing app was not changed" >&2
    exit 1
fi

if [ -e "${target}" ] || [ -L "${target}" ]; then
    backup_pending=1
    if ! osascript -l JavaScript "${rename_helper}" "${target}" "${stage}/previous/${app_name}"; then
        echo "Could not move the previous app into backup: ${target}" >&2
        exit 1
    fi
fi
if [ -e "${target}" ] || [ -L "${target}" ]; then
    echo "Install target appeared during replacement: ${target}" >&2
    exit 1
fi
if ! osascript -l JavaScript "${rename_helper}" "${stage}/incoming/${app_name}" "${target}"; then
    echo "Could not install the staged application: ${target}" >&2
    exit 1
fi
if [ -e "${stage}/incoming/${app_name}" ] || [ -L "${stage}/incoming/${app_name}" ]; then
    echo "Install target appeared during replacement: ${target}" >&2
    exit 1
fi
backup_pending=0
echo "Installed '${app_name}' to ${install_dir}"
