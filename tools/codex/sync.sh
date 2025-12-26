#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: tools/codex/sync.sh [--dry-run] [--prune]

Sync in-repo Codex prompts to ~/.codex/prompts.

Options:
  --dry-run  Print actions without changing files.
  --prune    Move destination-only prompts to an _orphaned folder.
USAGE
}

DRY_RUN=0
PRUNE=0
TMP_DIR="$(mktemp -d)"

cleanup() {
  rm -rf "${TMP_DIR}"
}

trap cleanup EXIT

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN=1
      ;;
    --prune)
      PRUNE=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
  shift
done

run() {
  if [[ "${DRY_RUN}" -eq 1 ]]; then
    echo "+ $*"
  else
    "$@"
  fi
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SOURCE_DIR="${ROOT_DIR}/tools/codex/prompts"
DEST_DIR="${HOME}/.codex/prompts"

if [[ ! -d "${SOURCE_DIR}" ]]; then
  echo "Source prompts directory not found: ${SOURCE_DIR}" >&2
  echo "Create it and add your .md prompts, then re-run." >&2
  exit 1
fi

if find "${SOURCE_DIR}" -mindepth 1 -type d -print -quit | grep -q .; then
  echo "Warning: subdirectories under ${SOURCE_DIR} are ignored." >&2
fi

run mkdir -p "${DEST_DIR}"

collect_sorted_files() {
  local dir="$1"
  local label="$2"
  local raw="${TMP_DIR}/${label}.raw"
  local sorted="${TMP_DIR}/${label}.sorted"

  if ! find "${dir}" -maxdepth 1 -type f -name "*.md" -print > "${raw}"; then
    echo "Failed to scan prompts in ${dir}" >&2
    exit 1
  fi

  if ! LC_ALL=C sort "${raw}" > "${sorted}"; then
    echo "Failed to sort prompts in ${dir}" >&2
    exit 1
  fi

  printf '%s' "${sorted}"
}

SOURCE_FILES=()
SOURCE_SORTED_FILE="$(collect_sorted_files "${SOURCE_DIR}" "source")"
while IFS= read -r line; do
  SOURCE_FILES+=("${line}")
done < "${SOURCE_SORTED_FILE}"

if [[ ${#SOURCE_FILES[@]} -eq 0 ]]; then
  echo "No prompts found in ${SOURCE_DIR}" >&2
fi

backup_suffix() {
  date +%Y%m%d%H%M%S
}

SOURCE_BASENAMES_FILE="${TMP_DIR}/source_basenames"
touch "${SOURCE_BASENAMES_FILE}"
for src in "${SOURCE_FILES[@]}"; do
  base="$(basename "${src}")"
  printf '%s\n' "${base}" >> "${SOURCE_BASENAMES_FILE}"
  dest="${DEST_DIR}/${base}"

  if [[ ! -f "${dest}" ]]; then
    echo "Copy ${base}"
    run cp -p "${src}" "${dest}"
    continue
  fi

  if cmp -s "${src}" "${dest}"; then
    echo "Up-to-date ${base}"
    continue
  fi

  backup="${DEST_DIR}/${base}.local-$(backup_suffix).bak"
  echo "Conflict ${base} -> backing up local to $(basename "${backup}")"
  run cp -p "${dest}" "${backup}"
  run cp -p "${src}" "${dest}"
  echo "Updated ${base}"
done

if [[ "${PRUNE}" -eq 1 ]]; then
  DEST_FILES=()
  DEST_SORTED_FILE="$(collect_sorted_files "${DEST_DIR}" "dest")"
  while IFS= read -r line; do
    DEST_FILES+=("${line}")
  done < "${DEST_SORTED_FILE}"
  ORPHAN_DIR=""

  for dest in "${DEST_FILES[@]}"; do
    base="$(basename "${dest}")"
    if ! grep -Fqx -- "${base}" "${SOURCE_BASENAMES_FILE}"; then
      if [[ -z "${ORPHAN_DIR}" ]]; then
        ORPHAN_DIR="${DEST_DIR}/_orphaned/$(backup_suffix)"
        run mkdir -p "${ORPHAN_DIR}"
      fi
      echo "Orphan ${base} -> _orphaned"
      run mv "${dest}" "${ORPHAN_DIR}/${base}"
    fi
  done
fi
