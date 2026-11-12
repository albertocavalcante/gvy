# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0
import importlib.util
import shutil
import subprocess
import tempfile
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

    def test_wrong_comment_syntax_does_not_count(self) -> None:
        data = b"# SPDX-FileCopyrightText: Alice\n# SPDX-License-Identifier: Apache-2.0\n"
        self.assertTrue(check.header_errors(Path("a.kt"), data))

    def test_exact_benchmark_fixture_paths_use_metadata_instead_of_source_headers(self) -> None:
        self.assertEqual(13, len(BENCHMARK_FIXTURE_PATHS))
        for name in BENCHMARK_FIXTURE_PATHS:
            with self.subTest(path=name):
                self.assertFalse(check.maintained(Path(name), b"println 'fixture data'\n"))

    def test_metadata_fixture_inventory_is_exact(self) -> None:
        self.assertEqual(frozenset(BENCHMARK_FIXTURE_PATHS), check.METADATA_SOURCE_PATHS)

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


class IntegrationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "LICENSES").mkdir()
        shutil.copy(Path(__file__).parents[2] / "LICENSES/Apache-2.0.txt", self.root / "LICENSES/Apache-2.0.txt")
        shutil.copy(Path(__file__).parents[2] / "LICENSE", self.root / "LICENSE")
        (self.root / ".reuse").mkdir()
        shutil.copy(Path(__file__).parents[1] / "license-hashes.toml", self.root / ".reuse/license-hashes.toml")
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

    def test_sidecar_cannot_mask_missing_header(self) -> None:
        self.source.write_text("print(1)\n")
        (self.root / "src/a.py.license").write_text(
            "SPDX-FileCopyrightText: Alice\nSPDX-License-Identifier: Apache-2.0\n"
        )
        self.assertTrue(any("leading" in error for error in check.check(self.root)))

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
        self.assertIn("REUSE lint failed; run reuse --root . lint for details", check.check(self.root))

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
        self.assertNotIn("REUSE lint failed; run reuse --root . lint for details", errors)
        self.assertTrue(any(error.startswith(f"{neighbor.as_posix()}:") and "leading" in error for error in errors))

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
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        (self.root / ".gitignore").write_text(
            "# SPDX-FileCopyrightText: Alice\n# SPDX-License-Identifier: Apache-2.0\nignored.bin\n"
        )
        self.assertEqual([], check.check(self.root))
        (self.root / "ignored.bin").write_bytes(b"\x00\x01")
        subprocess.run(["git", "-C", str(self.root), "add", "-f", "ignored.bin"], check=True)
        self.assertEqual(["REUSE lint failed; run reuse --root . lint for details"], check.check(self.root))

    def test_tracked_report_omission_is_rejected(self) -> None:
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "src/a.py"], check=True)
        # Simulate a future scanner omission while using a real tracked source.
        with patch.object(check, "parse_report", return_value=(True, {})):
            self.assertIn("src/a.py: tracked file omitted by REUSE", check.check(self.root))

    def test_standard_spdx_documents_match_reuse_exemptions(self) -> None:
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        names = ["report.spdx" + suffix for suffix in ("", ".rdf", ".json", ".xml", ".yml", ".yaml")]
        for name in names:
            (self.root / name).write_text("{}\n")
        subprocess.run(["git", "-C", str(self.root), "add", *names], check=True)
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
