# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0
import importlib.util
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("license_check", Path(__file__).parents[1] / "check.py")
assert SPEC and SPEC.loader
check = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(check)


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
        (self.root / ".gitignore").write_text("ignored.bin\n")
        (self.root / "ignored.bin").write_bytes(b"\x00\x01")
        subprocess.run(["git", "-C", str(self.root), "add", "-f", "ignored.bin"], check=True)
        self.assertIn("REUSE lint failed; run reuse --root . lint for details", check.check(self.root))

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
