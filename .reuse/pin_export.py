# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0
"""Bind a supported Copybara export to the clean checkout that passed validation."""

import argparse
import ast
import os
import re
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

MAX_CONFIG_BYTES = 256 * 1024
MAX_GIT_DIAGNOSTICS_BYTES = 64 * 1024
SOURCE_URL = "https://github.com/albertocavalcante/gvy.git"
CONFIG_PATHS = {
    "push-to-vscode-groovy": Path("infra/copybara/vscode-groovy.bara.sky"),
    "push-to-rewrite-codenarc": Path("infra/copybara/rewrite-codenarc.bara.sky"),
}


def _validate_request(workflow: str, revision: str) -> Path:
    if workflow not in CONFIG_PATHS:
        raise ValueError("unsupported forward export workflow")
    if re.fullmatch(r"[0-9a-f]{40}", revision) is None:
        raise ValueError("revision must be a full 40-character lowercase hexadecimal commit ID")
    return CONFIG_PATHS[workflow]


def _is_call(node: ast.AST, namespace: str, name: str) -> bool:
    return (
        isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and isinstance(node.func.value, ast.Name)
        and node.func.value.id == namespace
        and node.func.attr == name
    )


def _keywords(call: ast.Call) -> dict[str, ast.expr]:
    if call.args:
        raise ValueError("positional workflow/origin arguments are unsupported")
    keywords: dict[str, ast.expr] = {}
    for keyword in call.keywords:
        if keyword.arg is None or keyword.arg in keywords:
            raise ValueError("expanded or duplicate workflow/origin arguments are unsupported")
        keywords[keyword.arg] = keyword.value
    return keywords


def _validate_source_url(module: ast.Module, expression: ast.expr) -> None:
    if isinstance(expression, ast.Constant) and expression.value == SOURCE_URL:
        return
    if not isinstance(expression, ast.Name) or expression.id != "sourceUrl":
        raise ValueError("unsupported source URL; expected the gvy repository")
    assignments = [
        node
        for node in ast.walk(module)
        if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Store) and node.id == "sourceUrl"
    ]
    declarations = [
        statement
        for statement in module.body
        if isinstance(statement, ast.Assign)
        and len(statement.targets) == 1
        and isinstance(statement.targets[0], ast.Name)
        and statement.targets[0].id == "sourceUrl"
        and isinstance(statement.value, ast.Constant)
        and statement.value.value == SOURCE_URL
    ]
    if len(assignments) != 1 or len(declarations) != 1:
        raise ValueError("sourceUrl must have exactly one literal gvy repository declaration")


def pin_origin_ref(config: bytes, workflow: str, revision: str) -> bytes:
    """Replace only the named forward workflow's literal origin ref, preserving other bytes.

    Python's AST is used only to recognize the current Python-compatible Starlark
    shapes. Nothing is evaluated, and unsupported export shapes fail closed.
    """
    _validate_request(workflow, revision)
    if len(config) > MAX_CONFIG_BYTES or b"\x00" in config:
        raise ValueError("configuration exceeds 256 KiB or contains NUL")
    try:
        module = ast.parse(config.decode("utf-8"))
    except (UnicodeDecodeError, SyntaxError, RecursionError) as error:
        raise ValueError("configuration must be valid UTF-8 and supported Starlark syntax") from error
    top_level = {statement.value for statement in module.body if isinstance(statement, ast.Expr)}
    allowed_names = {workflow}
    if workflow == "push-to-rewrite-codenarc":
        allowed_names.add("pull-from-rewrite-codenarc")
    workflows: dict[str, dict[str, ast.expr]] = {}
    for node in ast.walk(module):
        if not isinstance(node, ast.Call) or not _is_call(node, "core", "workflow"):
            continue
        if node not in top_level:
            raise ValueError("workflow declarations must be top-level calls")
        keywords = _keywords(node)
        name = keywords.get("name")
        if not isinstance(name, ast.Constant) or not isinstance(name.value, str) or name.value not in allowed_names:
            raise ValueError("workflow names must be supported string literals")
        if name.value in workflows:
            raise ValueError("duplicate workflow declaration")
        workflows[name.value] = keywords
    if workflow not in workflows:
        raise ValueError("forward workflow declaration is missing")
    origin = workflows[workflow].get("origin")
    if not isinstance(origin, ast.Call) or not _is_call(origin, "git", "origin"):
        raise ValueError("forward workflow must have one direct git.origin call")
    origin_keywords = _keywords(origin)
    if set(origin_keywords) != {"url", "ref"}:
        raise ValueError("git.origin must have exactly one url and one ref argument")
    _validate_source_url(module, origin_keywords["url"])
    ref = origin_keywords["ref"]
    if not isinstance(ref, ast.Constant) or ref.value != "main" or ref.end_lineno is None or ref.end_col_offset is None:
        raise ValueError("origin ref must be the expected literal main branch")
    line_starts = [0, *(match.end() for match in re.finditer(rb"\r\n|\r|\n", config))]
    start = line_starts[ref.lineno - 1] + ref.col_offset
    end = line_starts[ref.end_lineno - 1] + ref.end_col_offset
    literal = config[start:end]
    if literal not in (b'"main"', b"'main'"):
        raise ValueError("origin ref must use a plain, single string literal")
    return config[:start] + literal[:1] + revision.encode("ascii") + literal[-1:] + config[end:]


def _git(root: Path, *arguments: str, limit: int = 8 * 1024 * 1024) -> bytes:
    # Do not let inherited Git context point these checks at a different checkout.
    environment = {name: value for name, value in os.environ.items() if not name.startswith("GIT_")}
    command = [
        "git",
        "--no-optional-locks",
        "--no-replace-objects",
        "-c",
        "core.fsmonitor=false",
        "-c",
        "core.untrackedCache=false",
        "-C",
        str(root),
        *arguments,
    ]
    try:
        with tempfile.TemporaryFile() as output, tempfile.TemporaryFile() as errors:
            result = subprocess.run(command, stdout=output, stderr=errors, env=environment, timeout=30, check=False)
            errors.seek(0)
            diagnostics = errors.read(MAX_GIT_DIAGNOSTICS_BYTES + 1)
            if diagnostics:
                sys.stderr.write(diagnostics[:MAX_GIT_DIAGNOSTICS_BYTES].decode("utf-8", errors="replace"))
            if len(diagnostics) > MAX_GIT_DIAGNOSTICS_BYTES:
                raise ValueError(
                    f"Git diagnostics exceed {MAX_GIT_DIAGNOSTICS_BYTES} bytes (exit status {result.returncode}); "
                    "only the bounded prefix was forwarded"
                )
            if result.returncode != 0:
                raise ValueError(f"Git check failed with exit status {result.returncode}: {arguments[0]}")
            output.seek(0)
            data = output.read(limit + 1)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ValueError("cannot inspect the checkout with Git") from error
    if len(data) > limit:
        raise ValueError("Git output exceeds the inspection limit")
    return data


def _verify_checkout(root: Path, revision: str, config_path: Path) -> None:
    reported_root = Path(os.fsdecode(_git(root, "rev-parse", "--show-toplevel").rstrip(b"\n"))).resolve()
    if reported_root != root:
        raise ValueError("root must be the repository top-level directory")
    if _git(root, "rev-parse", "--verify", "HEAD").strip() != revision.encode("ascii"):
        raise ValueError("checkout HEAD does not match the validated revision")
    entries = _git(root, "ls-files", "-v", "-z").split(b"\x00")
    if any(entry[:1] == b"S" or entry[:1].islower() for entry in entries if entry):
        raise ValueError("checkout contains assume-unchanged or skip-worktree entries")
    if b"H " + config_path.as_posix().encode("ascii") not in entries:
        raise ValueError("the export configuration must be tracked")
    if _git(root, "status", "--porcelain=v1", "-z", "--untracked-files=all", "--ignore-submodules=none"):
        raise ValueError("checkout must have no tracked changes or nonignored untracked files")


def _read_config_blob(root: Path, revision: str, path: Path) -> bytes:
    # Read the immutable tree object, never a possibly symlinked working-tree path.
    entry = _git(root, "ls-tree", "-z", revision, "--", path.as_posix(), limit=1024)
    fields = entry.removesuffix(b"\x00").split(b"\t")
    if len(fields) != 2 or fields[1] != path.as_posix().encode("ascii"):
        raise ValueError("configuration is missing or has an unsupported tree entry")
    metadata = fields[0].split(b" ")
    if (
        len(metadata) != 3
        or metadata[0] not in (b"100644", b"100755")
        or metadata[1] != b"blob"
        or re.fullmatch(rb"[0-9a-f]{40}", metadata[2]) is None
    ):
        raise ValueError("configuration must be a regular Git blob, never a symlink")
    object_id = metadata[2].decode("ascii")
    size_text = _git(root, "cat-file", "-s", object_id, limit=32).strip()
    if not size_text.isdigit() or int(size_text) > MAX_CONFIG_BYTES:
        raise ValueError("configuration exceeds 256 KiB")
    config = _git(root, "cat-file", "blob", object_id, limit=MAX_CONFIG_BYTES)
    if len(config) != int(size_text):
        raise ValueError("configuration blob size changed unexpectedly")
    return config


def bind_export(root: Path, workflow: str, revision: str, output: Path) -> str:
    """Write a private external config exclusively; return its repository-relative path."""
    config_path = _validate_request(workflow, revision)
    try:
        root = root.resolve(strict=True)
        _verify_checkout(root, revision, config_path)
        config = pin_origin_ref(_read_config_blob(root, revision, config_path), workflow, revision)
        output = output.parent.resolve(strict=True) / output.name
        if output.is_relative_to(root):
            raise ValueError("output configuration must be outside the repository")
        relative = Path(os.path.relpath(output, root)).as_posix()
        if "\r" in relative or "\n" in relative:
            raise ValueError("output path must not contain line breaks")
        _verify_checkout(root, revision, config_path)
        descriptor = os.open(output, os.O_CREAT | os.O_EXCL | os.O_WRONLY, stat.S_IRUSR | stat.S_IWUSR)
    except OSError as error:
        raise ValueError("cannot create an exclusive external configuration") from error
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(config)
    except OSError as error:
        output.unlink(missing_ok=True)
        raise ValueError("cannot write the private export configuration") from error
    return relative


class Arguments(argparse.Namespace):
    workflow: str
    revision: str
    output: Path
    root: Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workflow", required=True, choices=CONFIG_PATHS)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--root", type=Path, default=Path("."))
    arguments = parser.parse_args(namespace=Arguments())
    try:
        relative = bind_export(arguments.root, arguments.workflow, arguments.revision, arguments.output)
    except ValueError as error:
        print(f"export binding failed: {error}", file=sys.stderr)
        return 1
    print(relative)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
