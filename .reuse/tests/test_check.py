# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0
import hashlib
import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import tomllib
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("license_check", Path(__file__).parents[1] / "check.py")
assert SPEC and SPEC.loader
check = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(check)

BENCHMARK_FIXTURE_PATHS: tuple[str, ...] = (
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
)

EDITOR_INPUT_FIXTURES: tuple[str, ...] = (
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
)


class HeaderTests(unittest.TestCase):
    def test_leading_header_required(self) -> None:
        valid = b"// SPDX-FileCopyrightText: Alice\n// SPDX-License-Identifier: Apache-2.0\npackage x\n"
        self.assertEqual([], check.header_errors(Path("src/A.kt"), valid))
        self.assertTrue(check.header_errors(Path("src/A.kt"), b'val x = "SPDX-License-Identifier: Apache-2.0"\n'))

    def test_shebang_and_encoding_allowed(self) -> None:
        script = b"#!/usr/bin/env python3\n# coding: utf-8\n# SPDX-FileCopyrightText: Alice\n# SPDX-License-Identifier: Apache-2.0\nprint(1)\n"
        self.assertEqual([], check.header_errors(Path("tools/a.py"), script))

    def test_unapproved_and_empty_holder(self) -> None:
        self.assertTrue(
            check.header_errors(Path("src/A.kt"), b"// SPDX-FileCopyrightText: \n// SPDX-License-Identifier: MIT\n")
        )

    def test_comment_block_and_annotation(self) -> None:
        data = b'/*\n * SPDX-FileCopyrightText: Alice\n * SPDX-License-Identifier: Apache-2.0\n */\n@file:Suppress("x")\npackage x\n'
        self.assertEqual([], check.header_errors(Path("build.gradle.kts"), data))

    def test_tags_after_code_and_fake_string_do_not_count(self) -> None:
        data = b'val x = "// SPDX-FileCopyrightText: Alice"\n// SPDX-License-Identifier: Apache-2.0\n'
        self.assertTrue(check.header_errors(Path("a.kt"), data))

    def test_pep723_and_unapproved_license(self) -> None:
        data = b"#!/usr/bin/env python3\n# /// script\n# requires-python = '>=3.11'\n# ///\n# SPDX-FileCopyrightText: Alice\n# SPDX-License-Identifier: MIT\n"
        self.assertTrue(check.header_errors(Path("a.py"), data))

    def test_classification(self) -> None:
        self.assertTrue(check.maintained(Path("third_party/BUCK"), b""))
        self.assertTrue(check.maintained(Path("tools/run"), b"#!/bin/sh\n"))
        self.assertFalse(check.maintained(Path("src/test/resources/fixture.kt"), b""))

    def test_native_helper_sources_require_c_comments(self) -> None:
        valid = (
            b"// SPDX-FileCopyrightText: Alice\n// SPDX-License-Identifier: Apache-2.0\nint main(void) { return 0; }\n"
        )
        wrong = b"# SPDX-FileCopyrightText: Alice\n# SPDX-License-Identifier: Apache-2.0\n"
        for suffix in (".c", ".h"):
            path = Path(f"tools/ci/helper{suffix}")
            with self.subTest(suffix=suffix):
                self.assertTrue(check.maintained(path, b""))
                self.assertEqual([], check.header_errors(path, valid))
                self.assertTrue(check.header_errors(path, wrong))

    def test_runtime_sources_and_explicit_imports_have_distinct_policies(self) -> None:
        self.assertTrue(check.maintained(Path("build-tool/src/main/kotlin/gvy/build/Build.kt"), b""))
        self.assertTrue(check.maintained(Path("build-tool/src/main/resources/runtime.gradle"), b""))
        self.assertTrue(check.maintained(Path(".devcontainer/Dockerfile"), b""))
        self.assertFalse(check.maintained(Path("gradlew"), b"#!/bin/sh\n"))
        self.assertFalse(check.maintained(Path("third_party/jvm/BUCK"), b"# DO NOT EDIT\n"))

    def test_wrong_comment_syntax_does_not_count(self) -> None:
        data = b"# SPDX-FileCopyrightText: Alice\n# SPDX-License-Identifier: Apache-2.0\n"
        self.assertTrue(check.header_errors(Path("a.kt"), data))

    def test_exact_benchmark_fixture_paths_use_metadata_instead_of_source_headers(self) -> None:
        self.assertEqual(13, len(BENCHMARK_FIXTURE_PATHS))
        for name in BENCHMARK_FIXTURE_PATHS:
            with self.subTest(path=name):
                self.assertFalse(check.maintained(Path(name), b"println 'fixture data'\n"))

    def test_editor_input_fixtures_keep_payload_bytes_and_exact_metadata(self) -> None:
        self.assertEqual(14, len(EDITOR_INPUT_FIXTURES))
        self.assertEqual(frozenset(EDITOR_INPUT_FIXTURES), check.EDITOR_FIXTURE_PATHS)
        for name in EDITOR_INPUT_FIXTURES:
            with self.subTest(path=name):
                path = Path(name)
                self.assertFalse(check.maintained(path, b"import java.util.List\n"))
                neighbor = path.with_name("new-maintained-source.groovy")
                self.assertTrue(check.maintained(neighbor, b"println 'source'\n"))
                self.assertTrue(check.header_errors(neighbor, b"println 'source'\n"))

    def test_metadata_fixture_inventory_is_exact(self) -> None:
        self.assertEqual(frozenset(BENCHMARK_FIXTURE_PATHS), check.BYTE_SENSITIVE_FIXTURE_PATHS)

    def test_benchmark_neighbors_still_require_leading_source_headers(self) -> None:
        data = b"println 'maintained source'\n"
        for name in BENCHMARK_FIXTURE_PATHS:
            path = Path(name)
            neighbors = [path.with_name("neighbor.groovy"), path.with_name(path.name + ".groovy")]
            if path.suffix in {".groovy", ".gradle"}:
                neighbors.extend([Path("unlisted") / path, Path("Tools") / Path(*path.parts[1:])])
            for neighbor in neighbors:
                with self.subTest(path=neighbor.as_posix()):
                    self.assertTrue(check.maintained(neighbor, data))
                    self.assertTrue(check.header_errors(neighbor, data))

    def test_unrecognized_jenkins_neighbors_keep_generic_shebang_classification(self) -> None:
        path = Path("tools/perf/bench/corpus/fixtures/upstream/jenkins-docker-pipeline/JenkinsfileNeighbor")
        self.assertFalse(check.maintained(path, b"pipeline {}\n"))
        self.assertTrue(check.maintained(path, b"#!/usr/bin/env groovy\npipeline {}\n"))
        self.assertFalse(check.maintained(path.with_suffix(".neighbor"), b"#!/usr/bin/env groovy\npipeline {}\n"))

    def test_unlisted_jenkinsfile_is_maintained_source(self) -> None:
        for path in (
            Path("Jenkinsfile"),
            Path("tools/pipeline/Jenkinsfile"),
            Path("tools/perf/bench/corpus/fixtures/upstream/new/Jenkinsfile"),
        ):
            with self.subTest(path=path.as_posix()):
                self.assertTrue(check.maintained(path, b"pipeline {}\n"))

    def test_jenkinsfile_uses_groovy_comment_headers_even_after_a_shebang(self) -> None:
        path = Path("upstream/new/Jenkinsfile")
        valid_headers = (
            b"// SPDX-FileCopyrightText: Alice\n// SPDX-License-Identifier: Apache-2.0\n",
            b"/*\n * SPDX-FileCopyrightText: Alice\n * SPDX-License-Identifier: Apache-2.0\n */\n",
        )
        for shebang in (b"", b"#!/usr/bin/env groovy\n"):
            for header in valid_headers:
                with self.subTest(shebang=shebang, header=header):
                    self.assertEqual([], check.header_errors(path, shebang + header + b"pipeline {}\n"))
            hash_header = b"# SPDX-FileCopyrightText: Alice\n# SPDX-License-Identifier: Apache-2.0\n"
            self.assertTrue(check.header_errors(path, shebang + hash_header + b"pipeline {}\n"))


class DiagnosticTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

    def report(
        self,
        *,
        compliant: bool = False,
        categories: dict[str, list[str]] | None = None,
        files: list[dict[str, object]] | None = None,
    ) -> dict[str, object]:
        return {
            "lint_version": "1.0",
            "reuse_spec_version": "3.3",
            "reuse_tool_version": "6.3.0",
            "summary": {"compliant": compliant},
            "non_compliant": {name: (categories or {}).get(name, []) for name in check.NON_COMPLIANT_CATEGORIES},
            "files": files or [],
        }

    def invoke(
        self, code: int, report: object, *, stdout: bytes | None = None, stderr: bytes = b""
    ) -> tuple[dict[Path, set[str]] | None, list[str], list[str]]:
        result = subprocess.CompletedProcess(
            ["reuse"], code, json.dumps(report).encode() if stdout is None else stdout, stderr
        )
        with patch.object(check.subprocess, "run", return_value=result) as run:
            outcome = check.run_reuse(self.root)
        self.assertEqual(1, run.call_count)
        self.assertNotIn("text", run.call_args.kwargs)
        self.assertEqual(120, run.call_args.kwargs["timeout"])
        return outcome

    def test_every_category_and_invalid_expression_count_is_actionable(self) -> None:
        categories = {
            name: [str(self.root / "nested/a.txt") if name in check.PATH_CATEGORIES else "MIT"]
            for name in check.NON_COMPLIANT_CATEGORIES
        }
        report = self.report(
            categories=categories,
            files=[
                {
                    "path": "invalid.txt",
                    "copyrights": ["DO NOT LOG HOLDER"],
                    "source": "DO NOT LOG SOURCE",
                    "spdx_expressions": [{"value": "DO NOT LOG EXPRESSION", "is_valid": False}],
                }
            ],
        )
        _, errors, warnings = self.invoke(1, report)
        self.assertEqual([], warnings)
        text = "\n".join(errors)
        for name in check.NON_COMPLIANT_CATEGORIES:
            self.assertIn(f"{name} count=1", text)
            self.assertIn(f'{name} item="', text)
        self.assertIn('invalid_expressions count=1 file="invalid.txt"', text)
        self.assertIn('read_errors item="nested/a.txt"', text)
        self.assertNotIn(str(self.root), text)
        self.assertNotIn("DO NOT LOG", text)

    def test_diagnostic_paths_are_lexical_and_redact_outside_or_traversal(self) -> None:
        with patch.object(Path, "resolve", side_effect=AssertionError("must not resolve diagnostic paths")):
            self.assertEqual("a/file.txt", check.diagnostic_path(str(self.root / "a/./file.txt"), self.root))
            self.assertEqual("[outside-root]", check.diagnostic_path(str(self.root.parent / "other.txt"), self.root))
            self.assertEqual("[outside-root]", check.diagnostic_path("C:\\outside\\private.txt", self.root))
            self.assertEqual("[outside-root]", check.diagnostic_path("/outside/private.txt", Path("C:/project")))
            self.assertEqual("[unsafe-path]", check.diagnostic_path("a/../private.txt", self.root))
            self.assertEqual("[unsafe-path]", check.diagnostic_path("a\\..\\private.txt", self.root))

    def test_details_are_sorted_escaped_and_redact_known_credentials(self) -> None:
        names = ["z\n::error title=forged::payload\x1b[31m\u202e", "a credential-value"]
        with patch.dict(os.environ, {"GVY_TEST_API_TOKEN": "credential-value"}):
            a = check.reuse_diagnostics(self.report(categories={"bad_licenses": names}), self.root, None)
            b = check.reuse_diagnostics(
                self.report(categories={"bad_licenses": list(reversed(names))}), self.root, None
            )
        self.assertEqual(a, b)
        text = "\n".join(a)
        self.assertIn("[redacted]", text)
        self.assertNotIn("credential-value", text)
        self.assertIn("\\n::error", text)
        self.assertIn("\\u001b", text)
        self.assertIn("\\u202e", text)
        self.assertNotIn("\x1b", text)
        self.assertNotIn("\u202e", text)
        self.assertTrue(all(line.startswith("REUSE diagnostic: ") and "\n" not in line for line in a))

    def test_item_field_and_total_byte_limits_have_explicit_omission_counts(self) -> None:
        small = check.reuse_diagnostics(
            self.report(categories={"unused_licenses": [f"id-{i:03}" for i in range(80)]}), self.root, None
        )
        self.assertEqual(50, sum(" item=" in line for line in small))
        self.assertIn("omitted items=30", small[-1])
        huge = check.reuse_diagnostics(
            self.report(categories={"bad_licenses": [f"{i:03}" + "\u202e" * 1000 for i in range(80)]}), self.root, None
        )
        self.assertLessEqual(len("\n".join(huge).encode()), 16 * 1024)
        self.assertIn("bad_licenses count=80", huge[0])
        self.assertIn("truncated characters=", huge[-1])
        self.assertNotIn("omitted items=0", huge[-1])
        for line in huge:
            if " item=" in line:
                self.assertLessEqual(len(json.loads(line.split(" item=", 1)[1])), 256)

    def test_stderr_keeps_summaries_without_traceback_source_or_environment_dump(self) -> None:
        stderr = (
            b'Traceback (most recent call last):\n  File "/private/runner.py", line 3\n'
            b'    raise ValueError("PRIVATE SOURCE")\nTOKEN=PRIVATE ENVIRONMENT\n'
            b"ValueError: secret/value failed /private/input.toml\nwarning: continuing\n"
        )
        with patch.dict(os.environ, {"GVY_TEST_PASSWORD": "secret/value"}):
            _, errors, _ = self.invoke(2, None, stderr=stderr)
        text = "\n".join(errors)
        self.assertIn("unexpected exit 2", text)
        self.assertIn("ValueError: [redacted] failed [outside-root detail redacted]", text)
        self.assertIn("warning: continuing", text)
        self.assertIn("omitted unrecognized lines=4", text)
        for forbidden in ("Traceback", "PRIVATE SOURCE", "PRIVATE ENVIRONMENT", "/private/", "secret/value"):
            self.assertNotIn(forbidden, text)

    def test_stderr_rejects_traceback_source_and_level_named_environment_assignments(self) -> None:
        stderr = (
            'Traceback (most recent call last):\n  File "example.py", line 1\n'
            '    error = "PRIVATE_SOURCE_SENTINEL"\nRuntimeError: safe terminal summary\n'
            'WARNING=PRIVATE_ENV_SENTINEL\nerror = "PRIVATE_ASSIGNMENT"\n'
            "reuse.report - ERROR - useful logger summary\n"
        )
        text = "\n".join(check.reuse_diagnostics(None, self.root, stderr))
        for forbidden in ("PRIVATE_SOURCE_SENTINEL", "PRIVATE_ENV_SENTINEL", "PRIVATE_ASSIGNMENT"):
            self.assertNotIn(forbidden, text)
        self.assertIn("RuntimeError: safe terminal summary", text)
        self.assertIn("reuse.report - ERROR - useful logger summary", text)

    def test_stderr_preserves_nested_project_paths_and_redacts_whole_outside_path_tails(self) -> None:
        stderr = (
            f"ERROR: cannot parse {self.root / 'editors/code/REUSE.toml'}: invalid syntax\n"
            "Error: '/Users/Other Person/private-file.txt' is inaccessible\n"
            "Error: C:\\Users\\Other Person\\private-file.txt is inaccessible\n"
            "Error: \\\\server\\Other Person\\private-file.txt is inaccessible\n"
        )
        text = "\n".join(check.reuse_diagnostics(None, self.root, stderr))
        self.assertIn("editors/code/REUSE.toml: invalid syntax", text.replace("\\\\", "/"))
        for forbidden in ("Other", "Person", "private-file", "server", str(self.root)):
            self.assertNotIn(forbidden, text)

    def test_json_decoder_recursion_failure_keeps_same_invocation_stderr(self) -> None:
        _, errors, _ = self.invoke(1, None, stdout=b"[" * 10000, stderr=b"error: retained context\n")
        self.assertIn("malformed JSON nesting", errors[0])
        self.assertIn("exit 1", errors[0])
        self.assertIn("retained context", "\n".join(errors))

    def test_all_category_counts_survive_exhausted_detail_budget(self) -> None:
        categories = {name: ["\0" * 1000] * 80 for name in check.NON_COMPLIANT_CATEGORIES}
        lines = check.reuse_diagnostics(self.report(categories=categories), self.root, None)
        for name in check.NON_COMPLIANT_CATEGORIES:
            self.assertIn(f"REUSE diagnostic: {name} count=80", lines)

    def test_exit_and_report_disagreement_fails_in_both_directions(self) -> None:
        for code, compliant in ((1, True), (0, False)):
            with self.subTest(code=code):
                _, errors, _ = self.invoke(code, self.report(compliant=compliant))
                self.assertIn("exit/report mismatch", errors[0])
                self.assertIn(f"exit {code}", errors[0])

    def test_malformed_json_decode_schema_and_unexpected_exit_remain_distinct(self) -> None:
        cases = [
            (1, None, b'{\n"private payload":', "malformed JSON at line 2 column 19 char 20"),
            (1, None, b"\xff", "UTF-8 decode failure at byte 0"),
            (1, {"lint_version": "future"}, None, "invalid report schema"),
            (7, None, b"PRIVATE STDOUT", "unexpected exit 7"),
        ]
        for code, report, stdout, expected in cases:
            with self.subTest(expected=expected):
                _, errors, _ = self.invoke(code, report, stdout=stdout, stderr=b"error: useful context\n")
                text = "\n".join(errors)
                self.assertIn(expected, text)
                self.assertIn("useful context", text)
                self.assertNotIn("private payload", text)
                self.assertNotIn("PRIVATE STDOUT", text)

    def test_schema_rejects_incomplete_categories_or_non_string_items(self) -> None:
        for categories in ({}, {name: [3] for name in check.NON_COMPLIANT_CATEGORIES}):
            report = self.report()
            report["non_compliant"] = categories
            _, errors, _ = self.invoke(1, report)
            self.assertIn("invalid report schema", errors[0])

    def test_launch_and_timeout_keep_bounded_partial_stderr_without_command_dump(self) -> None:
        for failure, expected in (
            (OSError(13, "private executable"), "launch error errno=13"),
            (subprocess.TimeoutExpired(["PRIVATE COMMAND"], 120, stderr=b"error: partial context\n"), "timeout"),
        ):
            with self.subTest(expected=expected):
                with patch.object(check.subprocess, "run", side_effect=failure) as run:
                    _, errors, _ = check.run_reuse(self.root)
                self.assertEqual(1, run.call_count)
                self.assertIn(expected, errors[0])
                text = "\n".join(errors)
                self.assertNotIn("PRIVATE COMMAND", text)
                self.assertNotIn("private executable", text)
                if expected == "timeout":
                    self.assertIn("partial context", text)

    def test_compliant_warning_is_visible_in_cli_and_fails_diagnostic_policy(self) -> None:
        result = subprocess.CompletedProcess(
            ["reuse"], 0, json.dumps(self.report(compliant=True)).encode(), b"warning: note\n"
        )
        with (
            patch.object(check.subprocess, "run", return_value=result),
            patch.object(check, "REQUIRED_LICENSE_PATHS", set()),
            patch.object(check.tomllib, "loads", return_value={"license": []}),
            patch.object(Path, "read_text", return_value=""),
            patch.object(sys, "argv", ["check.py", "--root", str(self.root)]),
            patch.object(sys, "stderr", new_callable=io.StringIO) as stderr,
            patch.object(sys, "stdout", new_callable=io.StringIO) as stdout,
        ):
            self.assertEqual(1, check.main())
        self.assertIn("warning: note", stderr.getvalue())
        self.assertIn("gvy diagnostic policy", stderr.getvalue())
        self.assertNotIn("checks passed", stdout.getvalue())

    def test_only_exact_empty_successful_stderr_passes_shared_api_and_cli(self) -> None:
        captures = (
            b"",
            b"warning: note\n",
            b"unknown text",
            b" \r\n\t",
            b"\x1b[31mwarning: note",
            b"\x1b[0m",
            b"\0",
            b"\xff",
        )
        policy = "gvy diagnostic policy failed: unclassified stderr from successful REUSE (exit 0)"
        for capture in captures:
            with self.subTest(capture=capture):
                result = subprocess.CompletedProcess(
                    ["reuse"], 0, json.dumps(self.report(compliant=True)).encode(), capture
                )
                with (
                    patch.object(check.subprocess, "run", return_value=result) as run,
                    patch.object(check, "REQUIRED_LICENSE_PATHS", set()),
                    patch.object(check.tomllib, "loads", return_value={"license": []}),
                    patch.object(Path, "read_text", return_value=""),
                ):
                    with self.subTest(channel="check without collector"):
                        errors = check.check(self.root)
                        self.assertEqual(1, run.call_count)
                        self.assertEqual(bool(capture), bool(errors))
                        if capture:
                            self.assertIn(policy, errors)
                            self.assertNotIn("REUSE lint failed", "\n".join(errors))
                    run.reset_mock()
                    with self.subTest(channel="CLI"):
                        with (
                            patch.object(sys, "argv", ["check.py", "--root", str(self.root)]),
                            patch.object(sys, "stderr", new_callable=io.StringIO) as stderr,
                            patch.object(sys, "stdout", new_callable=io.StringIO) as stdout,
                        ):
                            self.assertEqual(int(bool(capture)), check.main())
                        self.assertEqual(1, run.call_count)
                        if capture:
                            self.assertIn(policy, stderr.getvalue())
                            self.assertNotIn("checks passed", stdout.getvalue())
                        else:
                            self.assertEqual("", stderr.getvalue())
                            self.assertIn("checks passed", stdout.getvalue())


class IntegrationTests(unittest.TestCase):
    def git(self, *arguments: str, directory: Path | None = None) -> subprocess.CompletedProcess[bytes]:
        environment = {name: value for name, value in os.environ.items() if not name.upper().startswith("GIT_")}
        environment.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull, GIT_ATTR_NOSYSTEM="1")
        with tempfile.TemporaryDirectory() as template:
            command = ["git", "-c", f"core.attributesFile={os.devnull}", "-C", str(directory or self.root), *arguments]
            if arguments[0] == "init":
                command.append(f"--template={template}")
            return subprocess.run(command, env=environment, stdout=subprocess.PIPE, check=True, timeout=30)

    def write_inventory(self, *, include_mit: bool = False) -> None:
        """Give each synthetic project only the license texts it actually uses."""
        approved_paths = {"LICENSE", "LICENSES/Apache-2.0.txt", "editors/code/LICENSE", "editors/zed/LICENSE"}
        if include_mit:
            approved_paths.add("LICENSES/MIT.txt")
        inventory = tomllib.loads((Path(__file__).parents[1] / "license-hashes.toml").read_text(encoding="utf-8"))
        entries = inventory.get("license")
        if not isinstance(entries, list):
            raise TypeError("missing approved test inventory")
        blocks = ["# SPDX-FileCopyrightText: Alice\n# SPDX-License-Identifier: Apache-2.0\n"]
        seen: set[str] = set()
        for raw_entry in entries:
            entry = check.as_object(raw_entry)
            name = entry.get("path")
            if not isinstance(name, str) or name not in approved_paths:
                continue
            lines = ["[[license]]"]
            for key in ("path", "sha256", "source"):
                value = entry.get(key)
                if not isinstance(value, str):
                    raise TypeError("invalid approved test inventory entry")
                lines.append(f"{key} = {json.dumps(value)}")
            blocks.append("\n".join(lines) + "\n")
            seen.add(name)
        if seen != approved_paths:
            raise ValueError("missing approved test license")
        (self.root / ".reuse/license-hashes.toml").write_text("\n".join(blocks), encoding="utf-8")

    def add_mit_data(self, expression: str = "MIT") -> None:
        shutil.copy(Path(__file__).parents[2] / "LICENSES/MIT.txt", self.root / "LICENSES/MIT.txt")
        (self.root / "fixture.txt").write_text("Synthetic metadata-only fixture\n", encoding="utf-8")
        (self.root / "REUSE.toml").write_text(
            "version = 1\n[[annotations]]\n"
            'path = "fixture.txt"\n'
            'SPDX-FileCopyrightText = "Synthetic fixture author"\n'
            f"SPDX-License-Identifier = {json.dumps(expression)}\n",
            encoding="utf-8",
        )

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "LICENSES").mkdir()
        shutil.copy(Path(__file__).parents[2] / "LICENSES/Apache-2.0.txt", self.root / "LICENSES/Apache-2.0.txt")
        shutil.copy(Path(__file__).parents[2] / "LICENSE", self.root / "LICENSE")
        (self.root / ".reuse").mkdir()
        self.write_inventory()
        for editor in ("code", "zed"):
            directory = self.root / "editors" / editor
            directory.mkdir(parents=True)
            shutil.copy(self.root / "LICENSE", directory / "LICENSE")
        (self.root / "src").mkdir()
        self.source = self.root / "src/a.py"
        self.source.write_text("# SPDX-FileCopyrightText: Alice\n# SPDX-License-Identifier: Apache-2.0\nprint(1)\n")

    def test_compliant_and_tampered_license(self) -> None:
        self.assertEqual([], check.check(self.root))
        (self.root / "LICENSES/Apache-2.0.txt").write_text("tampered")
        self.assertTrue(any("SHA-256" in error for error in check.check(self.root)))

    def test_stderr_policy_failure_preserves_parsed_paths_and_remaining_repository_gates(self) -> None:
        self.git("init", "-q")
        (self.root / "src/unreported.txt").write_text("unreported data\n", encoding="utf-8")
        self.git("add", "--", "src/a.py", "src/unreported.txt")
        tracked = self.git("ls-files", "-z")
        (self.root / "LICENSE").write_text("tampered\n", encoding="utf-8")
        self.source.write_text("print(1)\n", encoding="utf-8")
        report = {
            "lint_version": "1.0",
            "reuse_spec_version": "3.3",
            "reuse_tool_version": "6.3.0",
            "summary": {"compliant": True},
            "non_compliant": {name: [] for name in check.NON_COMPLIANT_CATEGORIES},
            "files": [{"path": "src/a.py", "spdx_expressions": [{"value": "MIT", "is_valid": True}]}],
        }
        result = subprocess.CompletedProcess(["reuse"], 0, json.dumps(report).encode(), b"unknown diagnostic\n")
        with patch.object(check.subprocess, "run", side_effect=[result, tracked]) as run:
            errors = check.check(self.root)
        self.assertEqual(2, run.call_count)  # One REUSE invocation, then the existing tracked-file scan.
        for expected in (
            "gvy diagnostic policy failed: unclassified stderr from successful REUSE (exit 0)",
            "LICENSE: license text does not match approved SHA-256",
            "LICENSES/MIT.txt: used MIT license must have an approved inventory entry",
            "src/unreported.txt: tracked file omitted by REUSE",
            "src/a.py: missing nonempty leading SPDX-FileCopyrightText",
            "src/a.py: leading license must be Apache-2.0",
            "src/a.py: effective REUSE license must be Apache-2.0",
        ):
            self.assertIn(expected, errors)

    def test_missing_metadata_diagnostic_names_both_categories_and_relative_file(self) -> None:
        (self.root / "unannotated.txt").write_text("payload must not be logged\n", encoding="utf-8")
        with patch.object(check.subprocess, "run", wraps=subprocess.run) as run:
            errors = "\n".join(check.check(self.root))
        self.assertEqual(1, run.call_count)
        self.assertIn('missing_copyright_info item="unannotated.txt"', errors)
        self.assertIn('missing_licensing_info item="unannotated.txt"', errors)
        self.assertNotIn(str(self.root), errors)
        self.assertNotIn("payload must not be logged", errors)

    def test_unused_license_diagnostic_names_identifier(self) -> None:
        shutil.copy(Path(__file__).parents[2] / "LICENSES/MIT.txt", self.root / "LICENSES/MIT.txt")
        self.write_inventory(include_mit=True)
        errors = "\n".join(check.check(self.root))
        self.assertIn('unused_licenses item="MIT"', errors)

    def test_invalid_expression_diagnostic_names_file_without_expression(self) -> None:
        self.source.write_text(
            "# SPDX-FileCopyrightText: Private Holder\n# SPDX-License-Identifier: Apache-2.0 AND\nprint(1)\n",
            encoding="utf-8",
        )
        errors = "\n".join(check.check(self.root))
        self.assertIn('invalid_expressions count=1 file="src/a.py"', errors)
        self.assertNotIn("Private Holder", errors)
        self.assertNotIn("Apache-2.0 AND", errors)

    def test_invalid_toml_diagnostic_preserves_exit_and_same_invocation_stderr(self) -> None:
        (self.root / "REUSE.toml").write_text("version = [\n", encoding="utf-8")
        with patch.object(check.subprocess, "run", wraps=subprocess.run) as run:
            errors = "\n".join(check.check(self.root))
        self.assertEqual(1, run.call_count)
        self.assertIn("unexpected exit 2", errors)
        self.assertIn("stderr", errors)
        self.assertIn("REUSE.toml", errors)

    def test_used_mit_requires_approved_inventory(self) -> None:
        self.add_mit_data()
        self.assertEqual(
            ["LICENSES/MIT.txt: used MIT license must have an approved inventory entry"], check.check(self.root)
        )

    def test_used_mit_text_and_inventory_integrity(self) -> None:
        self.add_mit_data()
        self.write_inventory(include_mit=True)
        canonical = (self.root / "LICENSES/MIT.txt").read_bytes()
        self.assertEqual(
            "b05785f9f18e6716bab63424b11454513b9943a222595b70411009202fc592b5",
            hashlib.sha256(canonical).hexdigest(),
        )
        self.assertEqual([], check.check(self.root))
        (self.root / "LICENSES/MIT.txt").write_text("tampered\n", encoding="utf-8")
        self.assertIn("LICENSES/MIT.txt: license text does not match approved SHA-256", check.check(self.root))

        (self.root / "LICENSES/MIT.txt").write_bytes(canonical)
        self.write_inventory()
        self.assertEqual(
            ["LICENSES/MIT.txt: used MIT license must have an approved inventory entry"], check.check(self.root)
        )

    def test_compound_mit_use_requires_inventory(self) -> None:
        for expression in ("MIT OR Apache-2.0", "(MIT AND Apache-2.0)"):
            with self.subTest(expression=expression):
                self.add_mit_data(expression)
                self.assertEqual(
                    ["LICENSES/MIT.txt: used MIT license must have an approved inventory entry"], check.check(self.root)
                )
                self.write_inventory(include_mit=True)
                self.assertEqual([], check.check(self.root))
                self.write_inventory()

    def test_git_archive_preserves_licensed_corpus_and_excludes_other_tools(self) -> None:
        project = Path(__file__).parents[2]
        shutil.copy(project / ".gitattributes", self.root / ".gitattributes")
        shutil.copy(project / "LICENSES/MIT.txt", self.root / "LICENSES/MIT.txt")
        self.write_inventory(include_mit=True)
        metadata = tomllib.loads((project / "REUSE.toml").read_text(encoding="utf-8"))
        blocks = ["version = 1\n"]
        corpus_paths: list[str] = []
        for raw_annotation in metadata["annotations"]:
            annotation = check.as_object(raw_annotation)
            if annotation.get("SPDX-License-Identifier") != "MIT":
                continue
            blocks.append(
                "[[annotations]]\n" + "\n".join(f"{key} = {json.dumps(value)}" for key, value in annotation.items())
            )
            paths = annotation.get("path")
            if not isinstance(paths, list):
                raise TypeError("missing corpus annotation paths")
            for name in paths:
                if not isinstance(name, str):
                    raise TypeError("invalid corpus annotation path")
                source = project / name
                destination = self.root / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, destination)
                corpus_paths.append(name)
        self.assertEqual(4, len(corpus_paths))
        (self.root / "REUSE.toml").write_text("\n\n".join(blocks), encoding="utf-8")
        excluded = ("tools/other.py", "tools/perf/other.py", "tools/perf/bench/driver.py")
        for name in excluded:
            (self.root / name).write_text("not part of the distributed corpus\n", encoding="utf-8")
        tracked = [
            ".gitattributes",
            "REUSE.toml",
            ".reuse/license-hashes.toml",
            "LICENSE",
            "LICENSES/Apache-2.0.txt",
            "LICENSES/MIT.txt",
            "editors/code/LICENSE",
            "editors/zed/LICENSE",
            "src/a.py",
            *corpus_paths,
            *excluded,
        ]
        self.git("init", "-q")
        self.git("add", "--", *tracked)
        executable = "tools/perf/bench/corpus/fixtures/upstream/jenkins-docker-pipeline/Jenkinsfile"
        self.git("update-index", "--chmod=+x", "--", executable)
        tree = self.git("write-tree").stdout.strip()
        archive = self.git("archive", "--format=tar", tree.decode("ascii")).stdout
        export_temp = tempfile.TemporaryDirectory()
        self.addCleanup(export_temp.cleanup)
        exported = Path(export_temp.name)
        with tarfile.open(fileobj=io.BytesIO(archive)) as contents:
            members = {member.name: member for member in contents if member.isfile()}
            self.assertEqual(set(tracked) - set(excluded), set(members))
            self.assertEqual(0o111, members[executable].mode & 0o111)
            self.assertEqual(0, members["src/a.py"].mode & 0o111)
            for name, member in members.items():
                stream = contents.extractfile(member)
                if stream is None:
                    raise AssertionError(f"missing archived contents: {name}")
                with stream:
                    data = stream.read()
                if name in corpus_paths:
                    self.assertEqual((project / name).read_bytes(), data)
                destination = exported / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
        self.assertFalse((exported / ".git").exists())
        self.assertEqual([], check.check(exported))
        mit = exported / "LICENSES/MIT.txt"
        canonical = mit.read_bytes()
        mit.unlink()
        self.assertTrue(any(error.startswith("LICENSES/MIT.txt: cannot read:") for error in check.check(exported)))
        mit.write_bytes(canonical)
        for name in corpus_paths:
            (exported / name).unlink()
        self.assertIn("REUSE lint failed (exit 1)", check.check(exported))
        result = subprocess.run(
            [sys.executable, "-m", "reuse", "--no-multiprocessing", "--root", str(exported), "lint", "--json"],
            capture_output=True,
            check=False,
        )
        report = check.as_object(json.loads(result.stdout))
        self.assertEqual(1, result.returncode)
        self.assertEqual(["MIT"], check.as_object(report["non_compliant"])["unused_licenses"])

    def test_git_archive_preserves_external_git_state_with_hostile_environment(self) -> None:
        with tempfile.TemporaryDirectory() as external_temp:
            external = Path(external_temp)
            self.git("init", "-q", directory=external)
            (external / "sentinel.txt").write_text("external work\n", encoding="utf-8")
            self.git("config", "core.filemode", "false", directory=external)
            self.git("add", "--", "sentinel.txt", directory=external)
            template = external / "template"
            (template / "hooks").mkdir(parents=True)
            (template / "hooks/sentinel").write_bytes(b"caller-owned template sentinel\n")
            config = external / "caller.gitconfig"
            config.write_text(f'[init]\n\ttemplateDir = "{template.as_posix()}"\n', encoding="utf-8")

            def snapshot() -> dict[str, str]:
                return {
                    path.relative_to(external).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                    for path in external.rglob("*")
                    if path.is_file()
                }

            before = snapshot()
            hostile = {
                "GIT_DIR": str(external / ".git"),
                "GIT_COMMON_DIR": str(external / ".git"),
                "GIT_WORK_TREE": str(self.root),
                "GIT_INDEX_FILE": str(external / ".git/index"),
                "GIT_OBJECT_DIRECTORY": str(external / ".git/objects"),
                "GIT_ALTERNATE_OBJECT_DIRECTORIES": str(external / ".git/objects"),
                "GIT_CONFIG_GLOBAL": str(config),
                "GIT_CONFIG_SYSTEM": str(config),
                "GIT_CONFIG_COUNT": "1",
                "GIT_CONFIG_KEY_0": "core.filemode",
                "GIT_CONFIG_VALUE_0": "false",
                "GIT_CONFIG_PARAMETERS": "'core.ignorecase=true'",
                "GIT_TEMPLATE_DIR": str(template),
                "GIT_TRACE": str(external / "unexpected-trace"),
            }
            with patch.dict(os.environ, hostile):
                try:
                    self.test_git_archive_preserves_licensed_corpus_and_excludes_other_tools()
                finally:
                    self.assertEqual(before, snapshot())
            self.assertFalse((self.root / ".git/hooks/sentinel").exists())

    def test_mit_like_custom_license_does_not_require_canonical_mit(self) -> None:
        (self.root / "LICENSES/LicenseRef-MIT-fixture.txt").write_text("Synthetic custom license\n", encoding="utf-8")
        (self.root / "fixture.txt").write_text("Synthetic fixture\n", encoding="utf-8")
        (self.root / "REUSE.toml").write_text(
            "version = 1\n[[annotations]]\n"
            'path = "fixture.txt"\n'
            'SPDX-FileCopyrightText = "Synthetic fixture author"\n'
            'SPDX-License-Identifier = "LicenseRef-MIT-fixture"\n',
            encoding="utf-8",
        )
        self.assertEqual([], check.check(self.root))

    def test_sidecar_cannot_mask_missing_header(self) -> None:
        self.source.write_text("print(1)\n")
        (self.root / "src/a.py.license").write_text(
            "SPDX-FileCopyrightText: Alice\nSPDX-License-Identifier: Apache-2.0\n"
        )
        self.assertTrue(any("leading" in error for error in check.check(self.root)))

    def test_native_helper_metadata_cannot_mask_missing_header(self) -> None:
        (self.root / "src/helper.c").write_text("int main(void) { return 0; }\n")
        (self.root / "src/helper.c.license").write_text(
            "SPDX-FileCopyrightText: Alice\nSPDX-License-Identifier: Apache-2.0\n"
        )
        self.assertTrue(any("src/helper.c: missing" in error for error in check.check(self.root)))

    def test_unknown_license_and_missing_text_fail(self) -> None:
        self.source.write_text("# SPDX-FileCopyrightText: Alice\n# SPDX-License-Identifier: NotARealLicense\n")
        self.assertTrue(any("REUSE lint failed" in error for error in check.check(self.root)))
        (self.root / "LICENSES/Apache-2.0.txt").unlink()
        self.assertTrue(any("cannot read" in error for error in check.check(self.root)))

    def test_metadata_cannot_mask_missing_header(self) -> None:
        self.source.write_text("print(1)\n")
        (self.root / "REUSE.toml").write_text(
            'version = 1\n[[annotations]]\npath = "src/a.py"\nSPDX-FileCopyrightText = "Alice"\nSPDX-License-Identifier = "Apache-2.0"\n'
        )
        self.assertTrue(any("leading" in error for error in check.check(self.root)))

    def test_exact_fixture_metadata_is_required_and_does_not_exempt_annotated_neighbors(self) -> None:
        relative = Path("tools/perf/bench/native-contract/fixtures/valid.groovy")
        fixture = self.root / relative
        fixture.parent.mkdir(parents=True)
        fixture.write_text("println 'synthetic fixture'\n", encoding="utf-8")
        metadata = self.root / "REUSE.toml"
        annotation = (
            "version = 1\n[[annotations]]\n"
            f'path = "{relative.as_posix()}"\n'
            'SPDX-FileCopyrightText = "Synthetic fixture author"\n'
            'SPDX-License-Identifier = "Apache-2.0"\n'
        )
        metadata.write_text(annotation, encoding="utf-8")
        self.assertEqual([], check.check(self.root))

        metadata.unlink()
        self.assertIn("REUSE lint failed (exit 1)", check.check(self.root))

        neighbor = relative.with_name("neighbor.groovy")
        (self.root / neighbor).write_text("println 'synthetic source'\n", encoding="utf-8")
        metadata.write_text(
            annotation
            + "[[annotations]]\n"
            + f'path = "{neighbor.as_posix()}"\n'
            + 'SPDX-FileCopyrightText = "Synthetic source author"\n'
            + 'SPDX-License-Identifier = "Apache-2.0"\n',
            encoding="utf-8",
        )
        errors = check.check(self.root)
        self.assertNotIn("REUSE lint failed (exit 1)", errors)
        self.assertTrue(any(error.startswith(f"{neighbor.as_posix()}:") and "leading" in error for error in errors))

    def test_editor_fixture_needs_metadata_and_cannot_exempt_annotated_neighbors(self) -> None:
        relative = Path("editors/code/client/src/test/fixtures/FoldingRanges.groovy")
        fixture = self.root / relative
        fixture.parent.mkdir(parents=True)
        payload = b"import java.util.List\nimport java.util.Map\n"
        fixture.write_bytes(payload)
        metadata = self.root / "REUSE.toml"
        annotation = (
            "version = 1\n[[annotations]]\n"
            f'path = "{relative.as_posix()}"\n'
            'SPDX-FileCopyrightText = "Synthetic fixture author"\n'
            'SPDX-License-Identifier = "Apache-2.0"\n'
        )
        metadata.write_text(annotation, encoding="utf-8")
        self.assertEqual([], check.check(self.root))
        self.assertEqual(payload, fixture.read_bytes())
        metadata.unlink()
        self.assertIn("REUSE lint failed (exit 1)", check.check(self.root))
        neighbor = relative.with_name("neighbor.groovy")
        (self.root / neighbor).write_bytes(b"println 'maintained source'\n")
        metadata.write_text(
            annotation
            + "[[annotations]]\n"
            + f'path = "{neighbor.as_posix()}"\n'
            + 'SPDX-FileCopyrightText = "Synthetic source author"\n'
            'SPDX-License-Identifier = "Apache-2.0"\n',
            encoding="utf-8",
        )
        errors = check.check(self.root)
        self.assertNotIn("REUSE lint failed (exit 1)", errors)
        self.assertTrue(any(error.startswith(f"{neighbor.as_posix()}:") and "leading" in error for error in errors))

    def test_metadata_cannot_mask_an_unlisted_jenkinsfile_header(self) -> None:
        relative = Path("tools/perf/bench/corpus/fixtures/upstream/new/Jenkinsfile")
        source = self.root / relative
        source.parent.mkdir(parents=True)
        source.write_text("pipeline {}\n", encoding="utf-8")
        (self.root / "REUSE.toml").write_text(
            "version = 1\n[[annotations]]\n"
            f'path = "{relative.as_posix()}"\n'
            'SPDX-FileCopyrightText = "Synthetic source author"\n'
            'SPDX-License-Identifier = "Apache-2.0"\n',
            encoding="utf-8",
        )
        errors = check.check(self.root)
        self.assertNotIn("REUSE lint failed (exit 1)", errors)
        self.assertTrue(any(error.startswith(f"{relative.as_posix()}:") and "leading" in error for error in errors))

        source.write_text(
            "// SPDX-FileCopyrightText: Synthetic source author\n// SPDX-License-Identifier: Apache-2.0\npipeline {}\n",
            encoding="utf-8",
        )
        self.assertEqual([], check.check(self.root))

    def test_reuse_ignored_tooling_is_checked(self) -> None:
        self.source.write_text("# SPDX-FileCopyrightText: Alice\n# SPDX-License-Identifier: Apache-2.0\n")
        (self.root / ".reuse/check.py").write_text("print(1)\n")
        self.assertTrue(any(".reuse/check.py: missing" in error for error in check.check(self.root)))

    def test_extra_effective_license_is_rejected(self) -> None:
        self.source.write_text(
            "# SPDX-FileCopyrightText: Alice\n# SPDX-License-Identifier: Apache-2.0\nprint(1)\n# SPDX-License-Identifier: MIT\n"
        )
        self.assertTrue(any("effective REUSE license" in error for error in check.check(self.root)))

    def test_tracked_ignored_binary_is_detected(self) -> None:
        self.git("init", "-q")
        (self.root / ".gitignore").write_text(
            "# SPDX-FileCopyrightText: Alice\n# SPDX-License-Identifier: Apache-2.0\nignored.bin\n"
        )
        self.assertEqual([], check.check(self.root))
        (self.root / "ignored.bin").write_bytes(b"\x00\x01")
        self.git("add", "-f", "ignored.bin")
        self.assertIn("REUSE lint failed (exit 1)", check.check(self.root))

    def test_tracked_report_omission_is_rejected(self) -> None:
        self.git("init", "-q")
        self.git("add", "src/a.py")
        # Simulate a future scanner omission while using a real tracked source.
        with patch.object(check, "parse_report", return_value=(True, {})):
            self.assertIn("src/a.py: tracked file omitted by REUSE", check.check(self.root))

    def test_standard_spdx_documents_match_reuse_exemptions(self) -> None:
        self.git("init", "-q")
        names = ["report.spdx" + suffix for suffix in ("", ".rdf", ".json", ".xml", ".yml", ".yaml")]
        for name in names:
            (self.root / name).write_text("{}\n")
        self.git("add", *names)
        self.assertEqual([], check.check(self.root))

    def test_jsx_metadata_does_not_replace_a_leading_header(self) -> None:
        source = self.root / "src/view.jsx"
        source.write_text("export default () => <div />;\n")
        source.with_suffix(".jsx.license").write_text(
            "SPDX-FileCopyrightText: Alice\nSPDX-License-Identifier: Apache-2.0\n"
        )
        self.assertTrue(any("src/view.jsx: missing" in error for error in check.check(self.root)))

    def test_report_shape_is_validated(self) -> None:
        with self.assertRaises((ValueError, TypeError)):
            check.parse_report({"lint_version": "1.0", "files": []})

    def test_root_license_integrity(self) -> None:
        (self.root / "LICENSE").write_text("tampered")
        self.assertTrue(any("LICENSE: license text does not match" in error for error in check.check(self.root)))
        (self.root / "LICENSE").unlink()
        self.assertTrue(any("LICENSE: cannot read" in error for error in check.check(self.root)))

    def test_distributed_license_copy_and_inventory_are_checked(self) -> None:
        (self.root / "editors/code/LICENSE").write_text("tampered")
        self.assertTrue(
            any("editors/code/LICENSE: license text does not match" in error for error in check.check(self.root))
        )
        (self.root / ".reuse/license-hashes.toml").write_text("license = []\n")
        self.assertTrue(any("missing required entries" in error for error in check.check(self.root)))
