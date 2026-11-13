#!/usr/bin/env bash
# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
case "${1:-}" in
  ./gradlew | node) ;;
  *) exit 75 ;;
esac
# One owned process group, no pipeline or unbounded capture-side inherited-pipe wait.
exec "$@" 2>&1
