# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0
"""Check REUSE compliance and gvy's first-party source header policy."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tomllib
from pathlib import Path

REQUIRED_LICENSE_PATHS = {"LICENSE", "LICENSES/Apache-2.0.txt", "editors/code/LICENSE", "editors/zed/LICENSE"}
# Resources include byte-sensitive fixtures and bundled data; their provenance is
# described by REUSE metadata. Imported code is audited separately there too.
EXCLUDED_PARTS = {"node_modules", ".venv"}
SUFFIXES = {
    ".kt",
    ".kts",
    ".java",
    ".groovy",
    ".gradle",
    ".py",
    ".sh",
    ".bash",
    ".zsh",
    ".js",
    ".mjs",
    ".cjs",
    ".ts",
    ".tsx",
    ".jsx",
    ".rs",
    ".go",
    ".css",
    ".bzl",
    ".bazel",
    ".yml",
    ".yaml",
    ".toml",
    ".properties",
    ".proto",
    ".tf",
    ".hcl",
    ".gdsl",
    ".sky",
    ".bxl",
    ".graphql",
    ".pro",
}
NAMES = {
    "Makefile",
    "BUCK",
    "BUILD",
    "WORKSPACE",
    "MODULE.bazel",
    ".bazelrc",
    ".buckconfig",
    ".editorconfig",
    ".envrc",
    "gradlew",
}
TAG = re.compile(r"^SPDX-(FileCopyrightText|License-Identifier):\s*(.*?)\s*$")


def maintained(path: Path, data: bytes) -> bool:
    """Classify original maintained source; REUSE still covers all other files."""
    if any(part in EXCLUDED_PARTS for part in path.parts):
        return False
    parts = path.parts
    if parts[:3] == ("tests", "e2e", "resources"):
        return False
    if any(
        parts[i] == "src" and (parts[i + 1] == "test" or parts[i + 1].endswith("Test")) and parts[i + 2] == "resources"
        for i in range(len(parts) - 2)
    ):
        return False
    return path.name in NAMES or path.suffix in SUFFIXES or (not path.suffix and data.startswith(b"#!"))


def header_errors(path: Path, data: bytes) -> list[str]:
    """Read only the leading comment, never strings or metadata overrides."""
    text = data.decode("utf-8-sig", errors="replace")
    lines = text.splitlines()
    tags: dict[str, list[str]] = {"FileCopyrightText": [], "License-Identifier": []}
    block = False
    hash_comments = (
        path.suffix
        in {
            ".py",
            ".sh",
            ".bash",
            ".zsh",
            ".yml",
            ".yaml",
            ".toml",
            ".properties",
            ".bzl",
            ".bazel",
            ".tf",
            ".hcl",
            ".sky",
            ".bxl",
            ".graphql",
            ".pro",
        }
        or path.name in NAMES
        or (not path.suffix and data.startswith(b"#!"))
    )
    slash_comments = path.suffix in {
        ".kt",
        ".kts",
        ".java",
        ".groovy",
        ".gradle",
        ".js",
        ".mjs",
        ".cjs",
        ".ts",
        ".tsx",
        ".jsx",
        ".rs",
        ".go",
        ".css",
        ".proto",
        ".tf",
        ".hcl",
        ".gdsl",
    }
    for index, line in enumerate(lines):
        stripped = line.strip()
        if index == 0 and stripped.startswith("#!"):
            continue
        if not stripped:
            continue
        if block:
            end = "*/" in stripped
            content = stripped.split("*/", 1)[0].lstrip("* ").strip()
            if end:
                block = False
                if stripped.split("*/", 1)[1].strip():
                    break
        elif slash_comments and stripped.startswith("/*"):
            content = stripped[2:].split("*/", 1)[0].lstrip("* ").strip()
            block = "*/" not in stripped
            if not block and stripped.split("*/", 1)[1].strip():
                break
        elif slash_comments and stripped.startswith("//"):
            content = stripped[2:].strip()
        elif hash_comments and stripped.startswith("#"):
            content = stripped[1:].strip()
        else:
            break
        match = TAG.fullmatch(content)
        if match:
            tags[match.group(1)].append(match.group(2))
    errors = []
    if not tags["FileCopyrightText"] or any(not holder for holder in tags["FileCopyrightText"]):
        errors.append(f"{path.as_posix()}: missing nonempty leading SPDX-FileCopyrightText")
    if tags["License-Identifier"] != ["Apache-2.0"]:
        errors.append(f"{path.as_posix()}: leading license must be Apache-2.0")
    return errors


def as_object(value: object) -> dict[str, object]:
    if not isinstance(value, dict):
        raise TypeError("expected JSON object")
    return {str(key): item for key, item in value.items()}


def parse_report(value: object) -> tuple[bool, dict[Path, set[str]]]:
    """Reject malformed or incompatible REUSE reports rather than guessing."""
    report = as_object(value)
    if (
        report.get("lint_version") != "1.0"
        or report.get("reuse_spec_version") != "3.3"
        or report.get("reuse_tool_version") != "6.3.0"
    ):
        raise ValueError("unexpected REUSE report version")
    summary = as_object(report.get("summary"))
    compliant = summary.get("compliant")
    files = report.get("files")
    if not isinstance(compliant, bool) or not isinstance(files, list):
        raise TypeError("invalid REUSE report summary or files")
    paths: dict[Path, set[str]] = {}
    for raw_item in files:
        item = as_object(raw_item)
        name = item.get("path")
        entries = item.get("spdx_expressions")
        if not isinstance(name, str) or not isinstance(entries, list):
            raise TypeError("invalid REUSE file entry")
        path = Path(name)
        if path.is_absolute() or ".." in path.parts or path in paths:
            raise ValueError("unsafe or duplicate REUSE path")
        expressions: set[str] = set()
        for raw_entry in entries:
            entry = as_object(raw_entry)
            expression = entry.get("value")
            if not isinstance(expression, str) or not isinstance(entry.get("is_valid"), bool):
                raise TypeError("invalid REUSE expression entry")
            expressions.add(expression)
        paths[path] = expressions
    return compliant, paths


def check(root: Path) -> list[str]:
    root = root.resolve()
    errors: list[str] = []
    try:
        inventory = tomllib.loads((root / ".reuse/license-hashes.toml").read_text(encoding="utf-8"))
        entries = inventory.get("license")
        if not isinstance(entries, list):
            raise TypeError("missing license inventory")
        seen: set[str] = set()
        for raw_entry in entries:
            entry = as_object(raw_entry)
            name, expected, source = entry.get("path"), entry.get("sha256"), entry.get("source")
            if not isinstance(name, str) or not isinstance(expected, str) or not isinstance(source, str) or not source:
                raise ValueError("invalid license inventory entry")
            path = Path(name)
            if path.is_absolute() or ".." in path.parts or name in seen or not re.fullmatch(r"[0-9a-f]{64}", expected):
                raise ValueError("unsafe or duplicate license inventory entry")
            seen.add(name)
            try:
                full_path = root / path
                if full_path.is_symlink() or not full_path.resolve().is_relative_to(root):
                    errors.append(f"{name}: unsafe symlink")
                    continue
                if hashlib.sha256(full_path.read_bytes()).hexdigest() != expected:
                    errors.append(f"{name}: license text does not match approved SHA-256")
            except OSError as exc:
                errors.append(f"{name}: cannot read: {exc.strerror}")
        if missing := REQUIRED_LICENSE_PATHS - seen:
            errors.append(f".reuse/license-hashes.toml: missing required entries: {', '.join(sorted(missing))}")
    except OSError as exc:
        errors.append(f".reuse/license-hashes.toml: cannot read: {exc.strerror}")
    except (ValueError, TypeError) as exc:
        errors.append(f".reuse/license-hashes.toml: invalid inventory: {exc}")
    env = os.environ.copy()
    env["REUSE_ENCODING_MODULE"] = "charset_normalizer"
    try:
        result = subprocess.run(
            [sys.executable, "-m", "reuse", "--no-multiprocessing", "--root", str(root), "lint", "--json"],
            capture_output=True,
            text=True,
            env=env,
            check=False,
            timeout=120,
        )
        if result.returncode not in (0, 1):
            raise ValueError("REUSE did not return a lint report")
        compliant, paths = parse_report(json.loads(result.stdout))
        if not compliant:
            errors.append("REUSE lint failed; run reuse --root . lint for details")
        # Git can track files ignored by REUSE's traversal. Treat omissions as
        # failures, not as an implicit exclusion from coverage.
        if (root / ".git").exists():
            git = subprocess.run(
                ["git", "-C", str(root), "ls-files", "-z"], capture_output=True, check=False, timeout=30
            )
            if git.returncode != 0:
                raise ValueError("git ls-files failed in checkout")
            tracked = {Path(os.fsdecode(name)) for name in git.stdout.split(b"\0") if name}
            for path in sorted(tracked - paths.keys()):
                full_path = root / path
                # REUSE deliberately ignores symlinks, empty files and license
                # texts; they do not represent missing file-level coverage.
                if (
                    path.parts[0] in {".reuse", "LICENSES"}
                    or path.name == "REUSE.toml"
                    or re.fullmatch(r"(?:LICEN[CS]E|COPYING)(?:[-.].*)?", path.name)
                    or path.name.endswith(
                        (".license", ".spdx", ".spdx.rdf", ".spdx.json", ".spdx.xml", ".spdx.yml", ".spdx.yaml")
                    )
                    or full_path.is_symlink()
                ):
                    continue
                if full_path.is_file() and full_path.stat().st_size == 0:
                    continue
                errors.append(f"{path.as_posix()}: tracked file omitted by REUSE")
        for path in sorted(paths):
            if path.is_absolute() or ".." in path.parts:
                errors.append(f"{path}: unsafe path in REUSE report")
                continue
            try:
                full_path = root / path
                if full_path.is_symlink() or not full_path.resolve().is_relative_to(root):
                    errors.append(f"{path.as_posix()}: unsafe symlink")
                    continue
                data = full_path.read_bytes()
                if maintained(path, data):
                    errors.extend(header_errors(path, data))
                    if paths[path] != {"Apache-2.0"}:
                        errors.append(f"{path.as_posix()}: effective REUSE license must be Apache-2.0")
            except OSError as exc:
                errors.append(f"{path.as_posix()}: cannot read: {exc.strerror}")
        for path in sorted((root / ".reuse").rglob("*")):
            if (
                path.is_file()
                and ".venv" not in path.parts
                and "__pycache__" not in path.parts
                and path.suffix in {".py", ".toml"}
            ):
                relative = path.relative_to(root)
                errors.extend(header_errors(relative, path.read_bytes()))
    except (OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired) as exc:
        errors.append(f"REUSE check failed: {exc}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()
    errors = check(args.root.resolve())
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("REUSE and first-party header checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
