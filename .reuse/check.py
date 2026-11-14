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
from pathlib import Path, PurePosixPath, PureWindowsPath

REQUIRED_LICENSE_PATHS = {"LICENSE", "LICENSES/Apache-2.0.txt", "editors/code/LICENSE", "editors/zed/LICENSE"}
# Resources include byte-sensitive fixtures and bundled data; their provenance is
# described by REUSE metadata. Imported code is audited separately there too.
EXCLUDED_PARTS = {"node_modules", ".venv"}
# These retain upstream/generated contents; adjacent or scoped metadata covers them.
METADATA_SOURCE_PATHS = {"gradlew", "third_party/jvm/BUCK"}
# Exact byte-sensitive source data, not a directory-wide header exemption.
# The fixture-owning PRs add exact REUSE.toml records with audited holders and
# licenses. REUSE coverage and tracked-file omission checks still apply.
BYTE_SENSITIVE_FIXTURE_PATHS: frozenset[str] = frozenset(
    {
        "tools/perf/bench/corpus/fixtures/upstream/groovy-cli-exception/CliBuilderException.groovy",
        "tools/perf/bench/corpus/fixtures/upstream/spock-blocking-variables/BlockingVariables.groovy",
        "tools/perf/bench/corpus/fixtures/upstream/groovy-grape-ivy/GrapeIvy.groovy",
        "tools/perf/bench/corpus/fixtures/upstream/groovy-benchmark-driver/bench.groovy",
        "tools/perf/bench/corpus/fixtures/upstream/ofbiz-filter-receipts/FilterOutReceipts.groovy",
        "tools/perf/bench/corpus/fixtures/upstream/spock-gradle-build/build.gradle",
        "tools/perf/bench/corpus/fixtures/upstream/geb-gradle-bootstrap/build.gradle",
        "tools/perf/bench/corpus/fixtures/upstream/pipeline-parallel-list/parallelFromList.groovy",
        "tools/perf/bench/corpus/fixtures/upstream/jenkins-docker-pipeline/Jenkinsfile",
        "tools/perf/bench/corpus/fixtures/upstream/spock-feature-methods/FeatureMethods.groovy",
        "tools/perf/bench/corpus/fixtures/upstream/geb-browser-spec/BrowserSpec.groovy",
        "tools/perf/bench/native-contract/fixtures/valid.groovy",
        "tools/perf/bench/native-contract/fixtures/malformed.groovy",
    }
)
# Editor test inputs retain original source positions; exact REUSE metadata is still required.
EDITOR_FIXTURE_PATHS: frozenset[str] = frozenset(
    {
        "editors/code/client/src/test/fixtures/CodeNarcRanges.groovy",
        "editors/code/client/src/test/fixtures/FoldingRanges.groovy",
        "editors/code/client/src/test/fixtures/Implementation.groovy",
        "editors/code/client/src/test/fixtures/ImportedAliases.groovy",
        "editors/code/client/src/test/fixtures/PrepareRename.groovy",
        "editors/code/client/src/test/fixtures/RenameDefaults.groovy",
        "editors/code/client/src/test/fixtures/RenameMemoized.groovy",
        "editors/code/client/src/test/fixtures/RenameParameter.groovy",
        "editors/code/client/src/test/fixtures/ScriptClassLiterals.groovy",
        "editors/code/client/src/test/fixtures/SelectionRanges.groovy",
        "editors/code/client/src/test/fixtures/SemanticTokens.groovy",
        "editors/code/client/src/test/fixtures/codenarc.groovy",
        "editors/zed/fixtures/basic/Greeter.groovy",
        "editors/zed/fixtures/basic/Main.groovy",
    }
)
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
    ".c",
    ".h",
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
    "Dockerfile",
    "Makefile",
    "Jenkinsfile",
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
NON_COMPLIANT_CATEGORIES = (
    "bad_licenses",
    "deprecated_licenses",
    "licenses_without_extension",
    "missing_licenses",
    "unused_licenses",
    "read_errors",
    "missing_copyright_info",
    "missing_licensing_info",
)
PATH_CATEGORIES = frozenset({"read_errors", "missing_copyright_info", "missing_licensing_info"})
DIAGNOSTIC_BYTES = 16 * 1024
DIAGNOSTIC_ITEMS = 50
DIAGNOSTIC_FIELD_CHARS = 256


def diagnostic_path(name: str, root: Path) -> str:
    """Normalize lexically; diagnostic paths never cause filesystem access."""
    path = Path(name)
    if ".." in name.replace("\\", "/").split("/"):
        return "[unsafe-path]"
    if path.is_absolute():
        try:
            path = path.relative_to(root)
        except ValueError:
            return "[outside-root]"
    elif PureWindowsPath(name).drive or PureWindowsPath(name).root or PurePosixPath(name).is_absolute():
        return "[outside-root]"
    return path.as_posix()


class Diagnostics:
    """Bound rendered details, reserving space for status and omission counts."""

    def __init__(self) -> None:
        self.lines: list[str] = []
        self.size = 0
        self.items = 0
        self.omitted = 0
        self.truncated_chars = 0
        # Known credential values only; this is not a general secret detector.
        self.secrets = sorted(
            {
                item
                for name, item in os.environ.items()
                if item and re.search(r"TOKEN|PASSWORD|SECRET|CREDENTIAL|API_KEY|PRIVATE_KEY|AUTHORIZATION", name, re.I)
            },
            key=lambda item: (-len(item), item),
        )

    def redact(self, value: str) -> str:
        for secret in self.secrets:
            value = value.replace(secret, "[redacted]")
        return value

    def add(self, label: str, value: str | None = None, *, detail: bool = False) -> None:
        suffix = ""
        if value is not None:
            value = self.redact(value)
            if len(value) > DIAGNOSTIC_FIELD_CHARS:
                self.truncated_chars += len(value) - (DIAGNOSTIC_FIELD_CHARS - 3)
                value = value[: DIAGNOSTIC_FIELD_CHARS - 3] + "..."
            # ASCII JSON escaping includes controls, ANSI escapes and bidi characters.
            suffix = json.dumps(value, ensure_ascii=True)
        line = f"REUSE diagnostic: {label}{suffix}"
        size = len(line.encode("utf-8")) + 1
        if (detail and self.items >= DIAGNOSTIC_ITEMS) or self.size + size > DIAGNOSTIC_BYTES - 512:
            self.omitted += 1
            return
        self.lines.append(line)
        self.size += size
        self.items += int(detail)

    def finish(self) -> list[str]:
        if self.omitted or self.truncated_chars:
            self.lines.append(
                f"REUSE diagnostic: omitted items={self.omitted}; truncated characters={self.truncated_chars}"
            )
        return self.lines


def non_compliant_categories(value: object) -> dict[str, list[str]]:
    categories = as_object(as_object(value).get("non_compliant"))
    if set(categories) != set(NON_COMPLIANT_CATEGORIES):
        raise ValueError("unexpected REUSE non_compliant categories")
    result: dict[str, list[str]] = {}
    for category in NON_COMPLIANT_CATEGORIES:
        items = categories[category]
        if not isinstance(items, list) or any(not isinstance(item, str) for item in items):
            raise TypeError("invalid REUSE non_compliant category")
        result[category] = [item for item in items if isinstance(item, str)]
    return result


def reuse_diagnostics(report: object, root: Path, stderr: str | bytes | None) -> list[str]:
    output = Diagnostics()
    if report is not None:
        categories = non_compliant_categories(report)
        files = as_object(report)["files"]
        if not isinstance(files, list):
            raise TypeError("invalid REUSE files")
        invalid: list[tuple[str, int]] = []
        for raw_file in files:
            file = as_object(raw_file)
            entries = file["spdx_expressions"]
            if not isinstance(entries, list):
                raise TypeError("invalid REUSE expressions")
            count = sum(as_object(entry)["is_valid"] is False for entry in entries)
            name = file["path"]
            if count and isinstance(name, str):
                invalid.append((name, count))
        # Totals precede details so a large early category cannot hide later causes.
        for category, items in categories.items():
            if items:
                output.add(f"{category} count={len(items)}")
        if invalid:
            output.add(f"invalid_expressions count={sum(count for _, count in invalid)}")
        for category, items in categories.items():
            for item in sorted(items):
                output.add(
                    f"{category} item=",
                    diagnostic_path(item, root) if category in PATH_CATEGORIES else item,
                    detail=True,
                )
        for name, count in sorted(invalid):
            output.add(f"invalid_expressions count={count} file=", diagnostic_path(name, root), detail=True)
    if stderr:
        text = stderr.decode("utf-8", errors="replace") if isinstance(stderr, bytes) else stderr
        output.add(f"stderr bytes={len(stderr) if isinstance(stderr, bytes) else len(text.encode('utf-8'))}")
        skipped = 0
        traceback = False
        exception = re.compile(r"^[\w.]*(?:Error|Exception):(?:\s|$)")
        summary = re.compile(
            r"^(?:reuse[\w.-]*:\s*(?:error|warning):|(?:error|warning|critical|fatal):|"
            r"reuse[\w.-]* - (?:ERROR|WARNING|CRITICAL|FATAL) -)(?:\s|$)",
            re.I,
        )
        # Keep diagnostic summaries, not traceback frames, source excerpts or env dumps.
        for line in text.splitlines():
            if line.startswith("Traceback (most recent call last):"):
                traceback = True
            terminal = exception.match(line)
            if not terminal and (traceback or not summary.match(line)):
                skipped += 1
                continue
            if terminal:
                traceback = False
            line = output.redact(line).replace(str(root) + os.sep, "")
            # With unquoted/spaced paths the end is ambiguous. Drop the tail at
            # an outside absolute path, rather than exposing leftover fragments.
            # A slash inside a project-relative path is not an absolute boundary.
            line = re.sub(r"(?<![\w.-])(?:[A-Za-z]:)?[\\/].*$", "[outside-root detail redacted]", line)
            output.add("stderr line=", line, detail=True)
        if skipped:
            output.add(f"stderr omitted unrecognized lines={skipped}")
    return output.finish()


def maintained(path: Path, data: bytes) -> bool:
    """Classify original maintained source; REUSE still covers all other files."""
    if path.as_posix() in METADATA_SOURCE_PATHS | BYTE_SENSITIVE_FIXTURE_PATHS | EDITOR_FIXTURE_PATHS:
        return False
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
    hash_comments = path.name != "Jenkinsfile" and (
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
    slash_comments = path.name == "Jenkinsfile" or path.suffix in {
        ".c",
        ".h",
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
    categories = non_compliant_categories(report)
    invalid_expression = False
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
            invalid_expression |= entry["is_valid"] is False
            expressions.add(expression)
        paths[path] = expressions
    if compliant and (any(categories.values()) or invalid_expression):
        raise ValueError("REUSE summary disagrees with reported violations")
    return compliant, paths


def run_reuse(root: Path) -> tuple[dict[Path, set[str]] | None, list[str], list[str]]:
    """Use one invocation and enforce licensing plus diagnostic accountability."""
    env = os.environ.copy()
    env["REUSE_ENCODING_MODULE"] = "charset_normalizer"
    stderr: str | bytes | None = None
    report: object = None
    status = ""
    try:
        result = subprocess.run(
            [sys.executable, "-m", "reuse", "--no-multiprocessing", "--root", str(root), "lint", "--json"],
            capture_output=True,
            env=env,
            check=False,
            timeout=120,
        )
        stderr = result.stderr
        status = f" (exit {result.returncode})"
        if result.returncode not in (0, 1):
            return (
                None,
                [f"REUSE check failed: unexpected exit {result.returncode}", *reuse_diagnostics(None, root, stderr)],
                [],
            )
        report = json.loads(result.stdout.decode("utf-8"))
        compliant, paths = parse_report(report)
        details = reuse_diagnostics(report, root, stderr)
        if (result.returncode == 0) != compliant:
            return paths, [f"REUSE check failed: exit/report mismatch{status}, compliant={compliant}", *details], []
        if not compliant:
            return paths, [f"REUSE lint failed{status}", *details], []
        # A compliant report does not classify its stderr under gvy's policy.
        if result.stderr != b"":
            return (
                paths,
                ["gvy diagnostic policy failed: unclassified stderr from successful REUSE (exit 0)", *details],
                [],
            )
        return paths, [], details
    except subprocess.TimeoutExpired as exc:
        stderr = exc.stderr
        reason = "timeout after 120 seconds"
    except OSError as exc:
        reason = f"launch error errno={exc.errno}"
    except UnicodeDecodeError as exc:
        reason = f"UTF-8 decode failure at byte {exc.start}{status}"
    except json.JSONDecodeError as exc:
        reason = f"malformed JSON at line {exc.lineno} column {exc.colno} char {exc.pos}{status}"
    except RecursionError:
        reason = f"malformed JSON nesting exceeds decoder limit{status}"
    except (ValueError, KeyError, TypeError) as exc:
        reason = f"invalid report schema{status}: {json.dumps(str(exc), ensure_ascii=True)}"
    return None, [f"REUSE check failed: {reason}", *reuse_diagnostics(None, root, stderr)], []


def check(root: Path, diagnostics: list[str] | None = None) -> list[str]:
    root = root.resolve()
    errors: list[str] = []
    seen: set[str] = set()
    try:
        inventory = tomllib.loads((root / ".reuse/license-hashes.toml").read_text(encoding="utf-8"))
        entries = inventory.get("license")
        if not isinstance(entries, list):
            raise TypeError("missing license inventory")
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
    paths, reuse_errors, warnings = run_reuse(root)
    errors.extend(reuse_errors)
    if diagnostics is not None:
        diagnostics.extend(warnings)
    if paths is None:
        return errors
    try:
        # REUSE validates the SPDX expressions; recognize only the exact MIT
        # identifier, including compound expressions, for the approved inventory.
        uses_mit = any(
            "MIT" in re.split(r"[\s()]+", expression) for expressions in paths.values() for expression in expressions
        )
        if uses_mit and "LICENSES/MIT.txt" not in seen:
            errors.append("LICENSES/MIT.txt: used MIT license must have an approved inventory entry")
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
    diagnostics: list[str] = []
    errors = check(args.root.resolve(), diagnostics)
    if diagnostics:
        print("\n".join(diagnostics), file=sys.stderr)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("REUSE and first-party header checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
