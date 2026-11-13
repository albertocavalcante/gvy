#!/usr/bin/env bash
# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
test "${RUNNER_ENVIRONMENT:-}" = github-hosted
test "${RUNNER_OS:-}" = Linux
bins="${RUNNER_TEMP:?}/detekt-proof-bin"
download="${RUNNER_TEMP:?}/detekt-proof-download"
mkdir -m 700 "${bins}" "${download}"
gh release download v2.38.2 --repo direnv/direnv --pattern direnv.linux-amd64 --dir "${download}"
printf '%s  %s\n' 148820a187930d36ca6a204504e5cdb30d8396cd1a97e64c3b3f10d793c11247 "${download}/direnv.linux-amd64" | sha256sum -c -
install -m 755 "${download}/direnv.linux-amd64" "${bins}/direnv"
printf '%s\n' "${bins}" >>"${GITHUB_PATH:?}"
