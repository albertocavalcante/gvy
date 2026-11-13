# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

from __future__ import annotations

import base64
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import unittest

from tools.ci.junit_contract_batch import compare_batch


def report(xml: bytes, name: str = "one") -> dict[str, object]:
    return {
        "reportId": "private/report.xml",
        "xmlBase64": base64.b64encode(xml).decode("ascii"),
        "expected": [["C", name, "passed", 1]],
    }


def encode(reports: list[dict[str, object]]) -> bytes:
    return json.dumps({"schemaVersion": 1, "reports": reports}).encode()


XML = b'<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="C" name="one"/></testsuite>'


class BatchContractTest(unittest.TestCase):
    def test_compares_original_bytes_without_echoing_private_selectors(self) -> None:
        result = compare_batch(encode([report(XML), report(XML, "missing")]))
        self.assertEqual(result["status"], "compared")
        self.assertFalse(result["ok"])
        rows = result["reports"]
        self.assertIsInstance(rows, list)
        self.assertEqual([row["reason"] for row in rows], ["ok", "case_mismatch"])
        self.assertEqual(rows[0]["artifactSha256"], hashlib.sha256(XML).hexdigest())
        self.assertEqual(rows[0]["counts"], [1, 0, 0, 0])
        self.assertEqual(rows[1]["index"], 1)
        text = json.dumps(result)
        self.assertNotIn("private", text)
        self.assertNotIn("missing", text)

    def test_preserves_xml_failure_instead_of_turning_it_into_batch_success(
        self,
    ) -> None:
        result = compare_batch(encode([report(b"<broken>")]))
        self.assertEqual(result["status"], "compared")
        self.assertFalse(result["ok"])
        self.assertEqual(result["reports"][0]["reason"], "invalid_xml")

    def test_rejects_duplicate_keys_unknown_fields_and_nested_json(self) -> None:
        valid = encode([report(XML)])
        malformed = [
            valid.replace(
                b'"schemaVersion": 1', b'"schemaVersion":1,"schemaVersion":1'
            ),
            valid[:-1] + b',"secret":"canary"}',
            b'{"schemaVersion":1,"reports":NaN}',
            b"\xff",
        ]
        for data in malformed:
            with self.subTest(data=data[:80]):
                result = compare_batch(data)
                self.assertEqual(result["status"], "invalid_input")
                self.assertFalse(result["ok"])
                self.assertEqual(result["reports"], [])
                self.assertNotIn("canary", json.dumps(result))
        self.assertEqual(
            compare_batch(b"[" * 65 + b"0" + b"]" * 65)["status"],
            "limit_exceeded",
        )

    def test_rejects_invalid_shape_and_boolean_multiplicity(self) -> None:
        variants: list[dict[str, object]] = [
            {**report(XML), "extra": "secret"},
            {**report(XML), "expected": [["C", "one", "passed", True]]},
            {**report(XML), "expected": [["C", "one", "unknown", 1]]},
            {**report(XML), "expected": []},
            {**report(XML), "xmlBase64": "not base64"},
        ]
        for item in variants:
            with self.subTest(item=item):
                self.assertEqual(
                    compare_batch(encode([item]))["status"], "invalid_input"
                )

    def test_aggregate_case_budget_is_not_a_per_report_budget(self) -> None:
        item = {**report(XML), "expected": [["C", "one", "passed", 6000]]}
        result = compare_batch(encode([item, item]))
        self.assertEqual(result["status"], "limit_exceeded")
        self.assertEqual(result["reports"], [])

    def test_changed_mapping_has_a_different_identity(self) -> None:
        first = compare_batch(encode([report(XML)]))["reports"][0]
        second = compare_batch(encode([report(XML, "other")]))["reports"][0]
        self.assertEqual(first["artifactSha256"], second["artifactSha256"])
        self.assertNotEqual(
            first["expectedContractSha256"], second["expectedContractSha256"]
        )

    def test_report_count_and_per_xml_bound_reject_before_comparison(self) -> None:
        from tools.ci.junit_contract_batch import MAX_REPORTS

        self.assertEqual(
            compare_batch(encode([report(XML)] * (MAX_REPORTS + 1)))["status"],
            "limit_exceeded",
        )
        self.assertEqual(
            compare_batch(encode([report(b"x" * 1_048_577)]))["status"],
            "limit_exceeded",
        )

    def test_quoted_brackets_do_not_consume_structural_depth(self) -> None:
        name = "[" * 70
        xml = XML.replace(b'name="one"', ('name="' + name + '"').encode())
        self.assertTrue(compare_batch(encode([report(xml, name)]))["ok"])

    def test_source_owned_unicode_mapping_digest_matches_the_pure_api(self) -> None:
        from tools.ci.junit_contract import CaseExpectation, inspect_junit

        xml = XML.replace(b"one", "雪".encode())
        result = compare_batch(encode([report(xml, "雪")]))["reports"][0]
        pure = inspect_junit(
            xml,
            [CaseExpectation("C", "雪", "passed", 1)],
            report_id="private/report.xml",
        )
        self.assertEqual(result["reason"], "ok")
        self.assertEqual(
            result["expectedContractSha256"], pure.expected_contract_sha256
        )

    def test_isolated_cli_has_one_json_result_and_truthful_status(self) -> None:
        script = Path(__file__).with_name("junit_contract_batch.py")
        for data, code in [
            (encode([report(XML)]), 0),
            (encode([report(XML, "missing")]), 1),
            (b"invalid", 2),
        ]:
            result = subprocess.run(
                [sys.executable, "-I", "-S", str(script)],
                input=data,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
                timeout=5,
            )
            self.assertEqual(result.returncode, code)
            self.assertEqual(result.stderr, b"")
            self.assertEqual(len(result.stdout.splitlines()), 1)
            self.assertEqual(json.loads(result.stdout), compare_batch(data))
