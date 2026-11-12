# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0
import hashlib
import importlib.util
import io
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from typing import BinaryIO
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("pin_export", Path(__file__).parents[1] / "pin_export.py")
assert SPEC and SPEC.loader
pin_export = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pin_export)

REPOSITORY = Path(__file__).resolve().parents[2]
REVISION = "1234567890abcdef1234567890abcdef12345678"
WORKFLOW = "push-to-vscode-groovy"
CONFIG = b"""core.workflow(
    name = "push-to-vscode-groovy",
    origin = git.origin(
        url = "https://github.com/albertocavalcante/gvy.git",
        ref = "main",
    ),
    destination = git.github_destination(url = "unchanged", push = "main"),
)
"""


class PinOriginTests(unittest.TestCase):
    def test_actual_configs_change_only_forward_origin_literal(self) -> None:
        for name in ("vscode-groovy", "rewrite-codenarc"):
            with self.subTest(name=name):
                original = (REPOSITORY / f"infra/copybara/{name}.bara.sky").read_bytes()
                result = pin_export.pin_origin_ref(original, f"push-to-{name}", REVISION)
                self.assertEqual(original.replace(b'ref = "main"', f'ref = "{REVISION}"'.encode(), 1), result)
                self.assertEqual(original, result.replace(REVISION.encode(), b"main", 1))
                if name == "rewrite-codenarc":
                    marker = b'name = "pull-from-rewrite-codenarc"'
                    self.assertEqual(original.split(marker)[1], result.split(marker)[1])

    def test_crlf_utf8_comments_and_fake_text_are_preserved(self) -> None:
        prefix = '# caf\u00e9: core.workflow(name = "push-to-vscode-groovy")\n'.encode()
        prefix += b'example = "origin = git.origin(ref = \\"main\\")"\n'
        original = (prefix + CONFIG).replace(b"\n", b"\r\n")
        expected = original.replace(b'ref = "main"', f'ref = "{REVISION}"'.encode(), 1)
        self.assertEqual(expected, pin_export.pin_origin_ref(original, WORKFLOW, REVISION))

    def test_single_quoted_ref_is_supported(self) -> None:
        original = CONFIG.replace(b'ref = "main"', b"ref = 'main'")
        expected = original.replace(b"'main'", f"'{REVISION}'".encode(), 1)
        self.assertEqual(expected, pin_export.pin_origin_ref(original, WORKFLOW, REVISION))

    def test_invalid_revisions_and_unknown_workflow_fail(self) -> None:
        for revision in ("", "main", "a" * 39, "a" * 41, "A" * 40, "z" * 40, REVISION + "\n"):
            with self.subTest(revision=revision), self.assertRaises(ValueError):
                pin_export.pin_origin_ref(CONFIG, WORKFLOW, revision)
        with self.assertRaises(ValueError):
            pin_export.pin_origin_ref(CONFIG, "pull-from-rewrite-codenarc", REVISION)

    def test_invalid_encoding_nul_syntax_and_size_fail(self) -> None:
        for config in (CONFIG + b"\xff", CONFIG + b"\x00", CONFIG + b"(", b"#" * (256 * 1024 + 1)):
            with self.subTest(config=config[:30]), self.assertRaises(ValueError):
                pin_export.pin_origin_ref(config, WORKFLOW, REVISION)

    def test_missing_duplicate_dynamic_nested_and_unknown_workflows_fail(self) -> None:
        cases = (
            CONFIG.replace(WORKFLOW.encode(), b"different-workflow"),
            CONFIG + CONFIG,
            CONFIG.replace(b'name = "push-to-vscode-groovy",', b"name = workflow_name,"),
            CONFIG.replace(b'name = "push-to-vscode-groovy",', b'name = "push-to-vscode-groovy", name = "other",'),
            b"def later():\n" + b"".join(b"    " + line for line in CONFIG.splitlines(keepends=True)),
            CONFIG + b'core.workflow(name = "unrecognized")\n',
        )
        for config in cases:
            with self.subTest(config=config[:80]), self.assertRaises(ValueError):
                pin_export.pin_origin_ref(config, WORKFLOW, REVISION)

    def test_unsupported_origin_and_ref_shapes_fail(self) -> None:
        cases = (
            CONFIG.replace(b"origin = git.origin(", b"other = git.origin("),
            CONFIG.replace(b"origin = git.origin(", b"origin = custom_origin("),
            CONFIG.replace(b"origin = git.origin(", b'origin = git.origin("positional",'),
            CONFIG.replace(b'        ref = "main",', b"        **options,"),
            CONFIG.replace(b'        ref = "main",', b""),
            CONFIG.replace(b'        ref = "main",', b'        ref = "main", ref = "main",'),
            CONFIG.replace(b'        ref = "main",', b"        ref = branch,"),
            CONFIG.replace(b'        ref = "main",', b'        ref = "ma" "in",'),
            CONFIG.replace(b'        ref = "main",', b'        ref = """main""",'),
            CONFIG.replace(b'        ref = "main",', b'        ref = "release",'),
            CONFIG.replace(b'        ref = "main",', b'        ref = "main", extra = True,'),
            CONFIG.replace(b"    origin = git.origin(", b"    **options, origin = git.origin("),
            CONFIG.replace(b"    destination =", b"    origin = git.origin(), destination ="),
            CONFIG.replace(b'url = "https://github.com/albertocavalcante/gvy.git"', b'url = "https://other.test/repo"'),
            CONFIG.replace(b'url = "https://github.com/albertocavalcante/gvy.git"', b"url = source_url()"),
        )
        for config in cases:
            with self.subTest(config=config), self.assertRaises(ValueError):
                pin_export.pin_origin_ref(config, WORKFLOW, REVISION)

    def test_source_url_binding_must_be_unambiguous(self) -> None:
        config = CONFIG.replace(b'"https://github.com/albertocavalcante/gvy.git"', b"sourceUrl")
        prefix = b'sourceUrl = "https://github.com/albertocavalcante/gvy.git"\n'
        self.assertEqual(
            (prefix + config).replace(b'ref = "main"', f'ref = "{REVISION}"'.encode()),
            pin_export.pin_origin_ref(prefix + config, WORKFLOW, REVISION),
        )
        for bad_prefix in (b"", prefix + prefix, prefix + b'sourceUrl = "other"\n', b"sourceUrl = compute()\n"):
            with self.subTest(prefix=bad_prefix), self.assertRaises(ValueError):
                pin_export.pin_origin_ref(bad_prefix + config, WORKFLOW, REVISION)


class GitDiagnosticsTests(unittest.TestCase):
    def setUp(self) -> None:
        self.status = 0
        self.diagnostics = b"warning: check this Git condition\n"

    def git_process(
        self,
        command: list[str],
        *,
        stdout: BinaryIO,
        stderr: BinaryIO,
        env: dict[str, str],
        timeout: int,
        check: bool,
    ) -> subprocess.CompletedProcess[bytes]:
        stdout.write(b"expected output\n")
        stderr.write(self.diagnostics)
        return subprocess.CompletedProcess(command, self.status)

    def test_successful_git_diagnostics_are_forwarded_only_to_stderr(self) -> None:
        output = io.StringIO()
        errors = io.StringIO()
        with (
            redirect_stdout(output),
            redirect_stderr(errors),
            patch.object(pin_export.subprocess, "run", side_effect=self.git_process),
        ):
            self.assertEqual(b"expected output\n", pin_export._git(Path("."), "status"))
        self.assertEqual("", output.getvalue())
        self.assertEqual(self.diagnostics.decode(), errors.getvalue())

    def test_failed_git_preserves_diagnostics_and_reports_actual_exit_status(self) -> None:
        self.status = 42
        self.diagnostics = b"fatal: exact failure details\n"
        output = io.StringIO()
        errors = io.StringIO()
        with (
            redirect_stdout(output),
            redirect_stderr(errors),
            patch.object(pin_export.subprocess, "run", side_effect=self.git_process),
            self.assertRaisesRegex(ValueError, "exit status 42"),
        ):
            pin_export._git(Path("."), "status")
        self.assertEqual("", output.getvalue())
        self.assertEqual(self.diagnostics.decode(), errors.getvalue())

    def test_excess_diagnostics_explicitly_fail_on_success_or_failure(self) -> None:
        self.diagnostics = b"x" * (64 * 1024 + 1)
        for status in (0, 42):
            self.status = status
            output = io.StringIO()
            errors = io.StringIO()
            with (
                self.subTest(status=status),
                redirect_stdout(output),
                redirect_stderr(errors),
                patch.object(pin_export.subprocess, "run", side_effect=self.git_process),
                self.assertRaisesRegex(ValueError, f"diagnostics exceed.*exit status {status}"),
            ):
                pin_export._git(Path("."), "status")
            self.assertEqual("", output.getvalue())
            self.assertEqual("x" * (64 * 1024), errors.getvalue())


class BindExportTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / "checkout with spaces"
        self.root.mkdir()
        self.config = self.root / "infra/copybara/vscode-groovy.bara.sky"
        self.config.parent.mkdir(parents=True)
        self.config.write_bytes(CONFIG)
        (self.root / ".gitignore").write_text("ignored-output\n", encoding="utf-8")
        self.git("init", "-q")
        self.git("add", "infra/copybara/vscode-groovy.bara.sky", ".gitignore")
        self.commit()
        self.revision = self.git("rev-parse", "HEAD").decode().strip()
        self.output = self.base / "private config with spaces.sky"

    def git(self, *arguments: str, input: bytes | None = None) -> bytes:
        environment = {name: value for name, value in os.environ.items() if not name.upper().startswith("GIT_")}
        environment.update(
            GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_SYSTEM=os.devnull, GIT_CONFIG_GLOBAL=os.devnull, GIT_ATTR_NOSYSTEM="1"
        )
        with tempfile.TemporaryDirectory() as template:
            command = ["git", "-c", f"core.attributesFile={os.devnull}", "-C", str(self.root), *arguments]
            if arguments[0] == "init":
                command.append(f"--template={template}")
            return subprocess.run(
                command, env=environment, input=input, stdout=subprocess.PIPE, check=True, timeout=30
            ).stdout

    def commit(self) -> None:
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "test: fixture")

    def bind(self, revision: str | None = None, output: Path | None = None) -> str:
        return pin_export.bind_export(self.root, WORKFLOW, revision or self.revision, output or self.output)

    def assert_rejected(self, revision: str | None = None, output: Path | None = None) -> None:
        with self.assertRaises(ValueError):
            self.bind(revision, output)
        self.assertFalse((output or self.output).exists())

    def test_clean_checkout_writes_private_external_config_and_returns_relative_path(self) -> None:
        result = self.bind()
        self.assertEqual("../private config with spaces.sky", result)
        self.assertFalse(Path(result).is_absolute())
        self.assertEqual(self.output.resolve(), (self.root / result).resolve())
        self.assertEqual(CONFIG.replace(b'"main"', f'"{self.revision}"'.encode(), 1), self.output.read_bytes())
        if os.name != "nt":
            self.assertEqual(0o600, stat.S_IMODE(self.output.stat().st_mode))
        self.assertEqual(CONFIG, self.config.read_bytes())
        self.assertEqual(b"", self.git("status", "--porcelain=v1", "--untracked-files=all"))

    def test_head_mismatch_and_malformed_revision_fail_before_output(self) -> None:
        self.assert_rejected("0" * 40)
        self.assert_rejected("main")

    def test_tracked_worktree_and_index_changes_fail_before_output(self) -> None:
        self.config.write_bytes(CONFIG + b"# changed\n")
        self.assert_rejected()
        self.git("add", "infra/copybara/vscode-groovy.bara.sky")
        self.assert_rejected()

    def test_untracked_files_fail_but_ignored_files_are_allowed(self) -> None:
        noise = self.root / "untracked"
        noise.write_text("noise", encoding="utf-8")
        self.assert_rejected()
        noise.unlink()
        (self.root / "ignored-output").write_text("ignored", encoding="utf-8")
        self.bind()

    def test_assume_unchanged_and_skip_worktree_flags_are_rejected(self) -> None:
        for option in ("--assume-unchanged", "--skip-worktree"):
            with self.subTest(option=option):
                self.git("update-index", option, "infra/copybara/vscode-groovy.bara.sky")
                self.assert_rejected()
                self.git(
                    "update-index",
                    "--no-assume-unchanged",
                    "--no-skip-worktree",
                    self.config.relative_to(self.root).as_posix(),
                )

    def test_inside_checkout_or_line_break_output_is_rejected(self) -> None:
        self.assert_rejected(output=self.root / "ignored-output")
        self.assert_rejected(output=self.base / "unsafe\npath")

    def test_output_is_never_overwritten(self) -> None:
        self.output.write_bytes(b"existing")
        with self.assertRaises(ValueError):
            self.bind()
        self.assertEqual(b"existing", self.output.read_bytes())

    def test_output_alias_and_normalized_inside_path_are_rejected(self) -> None:
        # Use a hard link for portable collision coverage, including Windows.
        self.output.hardlink_to(self.config)
        with self.assertRaises(ValueError):
            self.bind()
        self.assertEqual(CONFIG, self.config.read_bytes())
        self.output.unlink()
        self.assert_rejected(output=self.root / ".." / self.root.name / "ignored-output")

    def test_blob_reads_are_bound_to_revision_instead_of_working_tree(self) -> None:
        self.config.write_bytes(b"unvalidated working-tree replacement\n")
        self.assertEqual(
            CONFIG, pin_export._read_config_blob(self.root, self.revision, self.config.relative_to(self.root))
        )
        self.assert_rejected()

    def test_git_replace_refs_cannot_substitute_committed_configuration(self) -> None:
        config_path = self.config.relative_to(self.root).as_posix()
        original_id = self.git("rev-parse", f"{self.revision}:{config_path}").decode().strip()
        replacement = b"unvalidated replacement configuration\n"
        replacement_id = self.git("hash-object", "-w", "--stdin", input=replacement).decode().strip()
        self.git("replace", original_id, replacement_id)
        self.assertEqual(replacement, self.git("cat-file", "blob", original_id))
        self.assertEqual(CONFIG, pin_export._read_config_blob(self.root, self.revision, Path(config_path)))
        self.bind()
        self.assertEqual(CONFIG.replace(b'"main"', f'"{self.revision}"'.encode(), 1), self.output.read_bytes())

    def test_parent_symlink_in_git_tree_cannot_escape_configuration_path(self) -> None:
        object_id = self.git("hash-object", "-w", "--stdin", input=b"../../external-directory").decode().strip()
        self.git("rm", "-qr", "--cached", "infra")
        self.git("update-index", "--add", "--cacheinfo", f"120000,{object_id},infra")
        self.commit()
        self.revision = self.git("rev-parse", "HEAD").decode().strip()
        with self.assertRaisesRegex(ValueError, "missing or.*unsupported tree entry"):
            pin_export._read_config_blob(self.root, self.revision, self.config.relative_to(self.root))
        self.assert_rejected()

    def test_untracked_ignored_config_cannot_replace_committed_config(self) -> None:
        self.git("rm", "-q", self.config.relative_to(self.root).as_posix())
        (self.root / ".gitignore").write_text("infra/copybara/vscode-groovy.bara.sky\n", encoding="utf-8")
        self.git("add", ".gitignore")
        self.commit()
        self.revision = self.git("rev-parse", "HEAD").decode().strip()
        self.config.parent.mkdir(parents=True, exist_ok=True)
        self.config.write_bytes(CONFIG)
        self.assert_rejected()

    def test_config_symlink_fails_before_output(self) -> None:
        object_id = self.git("hash-object", "-w", "--stdin", input=b"../../../../external-config").decode().strip()
        self.git("update-index", "--cacheinfo", f"120000,{object_id},{self.config.relative_to(self.root).as_posix()}")
        self.commit()
        self.revision = self.git("rev-parse", "HEAD").decode().strip()
        with self.assertRaisesRegex(ValueError, "regular Git blob"):
            pin_export._read_config_blob(self.root, self.revision, self.config.relative_to(self.root))
        self.assert_rejected()

    def test_oversized_config_blob_fails_before_output(self) -> None:
        self.config.write_bytes(b"#" * (256 * 1024 + 1))
        self.git("add", self.config.relative_to(self.root).as_posix())
        self.commit()
        self.revision = self.git("rev-parse", "HEAD").decode().strip()
        with self.assertRaisesRegex(ValueError, "exceeds 256 KiB"):
            self.bind()
        self.assertFalse(self.output.exists())

    def test_root_must_be_repository_top_level(self) -> None:
        with self.assertRaises(ValueError):
            pin_export.bind_export(self.root / "infra", WORKFLOW, self.revision, self.output)
        self.assertFalse(self.output.exists())

    def test_cli_prints_only_relative_config_path(self) -> None:
        result = subprocess.run(
            [
                sys.executable,
                str(REPOSITORY / ".reuse/pin_export.py"),
                "--root",
                str(self.root),
                "--workflow",
                WORKFLOW,
                "--revision",
                self.revision,
                "--output",
                str(self.output),
            ],
            check=True,
            capture_output=True,
        )
        self.assertEqual(b"../private config with spaces.sky" + os.linesep.encode(), result.stdout)
        self.assertEqual(b"", result.stderr)

    def test_cli_failure_has_no_stdout_or_output(self) -> None:
        result = subprocess.run(
            [
                sys.executable,
                str(REPOSITORY / ".reuse/pin_export.py"),
                "--root",
                str(self.root),
                "--workflow",
                WORKFLOW,
                "--revision",
                "main",
                "--output",
                str(self.output),
            ],
            check=False,
            capture_output=True,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b"", result.stdout)
        self.assertIn(b"revision", result.stderr)
        self.assertFalse(self.output.exists())


class FixtureEnvironmentTests(unittest.TestCase):
    def test_new_fixtures_preserve_external_git_state_with_hostile_environment(self) -> None:
        caller = BindExportTests("test_cli_prints_only_relative_config_path")
        self.addCleanup(caller.doCleanups)
        caller.setUp()
        caller.git("config", "core.filemode", "false")
        (caller.root / "sentinel.txt").write_bytes(b"caller-owned sentinel\n")
        caller.git("add", "sentinel.txt")
        template = caller.base / "template"
        (template / "hooks").mkdir(parents=True)
        (template / "hooks/sentinel").write_bytes(b"caller-owned template sentinel\n")
        hook = template / "hooks/post-index-change"
        hook.write_bytes(b'#!/bin/sh\nprintf "hook ran\\n" >> "$GVY_OWNED_HOOK_LOG"\n')
        hook.chmod(hook.stat().st_mode | 0o111)
        config = caller.base / "caller.gitconfig"
        config.write_text(f'[init]\n\ttemplateDir = "{template.as_posix()}"\n', encoding="utf-8")

        def snapshot() -> dict[str, tuple[int, str]]:
            return {
                path.relative_to(caller.base).as_posix(): (
                    stat.S_IMODE(path.stat().st_mode),
                    hashlib.sha256(path.read_bytes()).hexdigest(),
                )
                for path in caller.base.rglob("*")
                if path.is_file()
            }

        before = snapshot()
        hostile = {
            "GIT_DIR": str(caller.root / ".git"),
            "GIT_COMMON_DIR": str(caller.root / ".git"),
            "GIT_WORK_TREE": str(caller.root),
            "GIT_INDEX_FILE": str(caller.root / ".git/index"),
            "GIT_OBJECT_DIRECTORY": str(caller.root / ".git/objects"),
            "GIT_ALTERNATE_OBJECT_DIRECTORIES": str(caller.root / ".git/objects"),
            "GIT_CONFIG_GLOBAL": str(config),
            "GIT_CONFIG_SYSTEM": str(config),
            "GIT_CONFIG_COUNT": "1",
            "GIT_CONFIG_KEY_0": "core.filemode",
            "GIT_CONFIG_VALUE_0": "false",
            "GIT_CONFIG_PARAMETERS": "'core.ignorecase=true'",
            "GIT_TEMPLATE_DIR": str(template),
            "GIT_TRACE": str(caller.base / "unexpected-trace"),
            "GVY_OWNED_HOOK_LOG": str(caller.base / "unexpected-hook"),
        }
        for name in (
            "test_cli_prints_only_relative_config_path",
            "test_git_replace_refs_cannot_substitute_committed_configuration",
            "test_parent_symlink_in_git_tree_cannot_escape_configuration_path",
            "test_config_symlink_fails_before_output",
        ):
            with self.subTest(test=name), patch.dict(os.environ, hostile):
                fixture = BindExportTests(name)
                self.addCleanup(fixture.doCleanups)
                try:
                    fixture.setUp()
                    getattr(fixture, name)()
                finally:
                    self.assertEqual(before, snapshot())
                self.assertFalse((fixture.root / ".git/hooks/sentinel").exists())
                self.assertFalse((fixture.root / ".git/hooks/post-index-change").exists())


if __name__ == "__main__":
    unittest.main()
