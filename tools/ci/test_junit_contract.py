# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

import unittest

from tools.ci.junit_contract import CaseExpectation, inspect_junit, inspect_task_log


class JunitContractTest(unittest.TestCase):
    def test_exact_cases_and_private_result(self) -> None:
        xml = b'<testsuite name="C" tests="2" failures="0" errors="0" skipped="1"><testcase classname="C" name="secret"/><testcase classname="C" name="skipped"><skipped/></testcase></testsuite>'
        expected = [
            CaseExpectation("C", "secret", "passed", 1),
            CaseExpectation("C", "skipped", "skipped", 1),
        ]
        result = inspect_junit(xml, expected, report_id="private/report.xml")
        self.assertEqual(result.reason, "ok")
        self.assertEqual(result.counts, (2, 0, 0, 1))
        self.assertNotIn("secret", repr(result))
        self.assertNotIn('skipped"', repr(result))

    def test_rejects_duplicate_identity_and_mismatch(self) -> None:
        xml = b'<testsuite name="C" tests="2" failures="0" errors="0" skipped="0"><testcase classname="C" name="same"/><testcase classname="C" name="same"/></testsuite>'
        self.assertEqual(
            inspect_junit(
                xml, [CaseExpectation("C", "same", "passed", 1)], report_id="report"
            ).reason,
            "case_mismatch",
        )
        self.assertEqual(
            inspect_junit(
                xml, [CaseExpectation("C", "same", "passed", 2)], report_id="report"
            ).reason,
            "ok",
        )

    def test_multiplicity_cannot_prove_distinct_source_variants(self) -> None:
        xml = b'<testsuite tests="2" failures="0" errors="0" skipped="0"><testcase classname="C" name="same"/><testcase classname="C" name="same"/></testsuite>'
        result = inspect_junit(
            xml, [CaseExpectation("C", "same", "passed", 2)], report_id="private"
        )
        self.assertEqual(result.reason, "ok")
        self.assertEqual(result.duplicate_case_rows, 1)
        self.assertNotIn("private", repr(result))

    def test_text_budget_and_failure_outcome(self) -> None:
        expected = [CaseExpectation("C", "x", "failed", 1)]
        xml = b'<testsuite tests="1" failures="1" errors="0" skipped="0"><testcase classname="C" name="x"><failure>no</failure></testcase></testsuite>'
        self.assertEqual(inspect_junit(xml, expected, report_id="r").reason, "ok")
        self.assertEqual(
            inspect_junit(
                xml.replace(
                    b"<failure>no</failure>",
                    b"<failure>" + b"x" * 524289 + b"</failure>",
                ),
                expected,
                report_id="r",
            ).reason,
            "limit_exceeded",
        )
        self.assertEqual(
            inspect_junit(
                xml.replace(b"<failure>no</failure>", b"<skipped/>"),
                expected,
                report_id="r",
            ).reason,
            "invalid_report",
        )

    def test_rejects_hidden_cases_and_result_markers(self) -> None:
        expected = [CaseExpectation("C", "x", "passed", 1)]
        good = b'<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="C" name="x"/></testsuite>'
        hidden_case = good.replace(
            b"</testsuite>",
            b'<extension><testcase classname="C" name="hidden"/></extension></testsuite>',
        )
        hidden_failure = good.replace(
            b'name="x"/>', b'name="x"><extension><failure/></extension></testcase>'
        )
        for data in (hidden_case, hidden_failure):
            self.assertEqual(
                inspect_junit(data, expected, report_id="r").reason, "invalid_report"
            )

    def test_rejects_invalid_multiplicity_and_total(self) -> None:
        xml = b'<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="C" name="x"/></testsuite>'
        self.assertEqual(
            inspect_junit(
                xml, [CaseExpectation("C", "x", "passed", True)], report_id="r"
            ).reason,
            "invalid_expectation",
        )
        self.assertEqual(
            inspect_junit(
                xml,
                [
                    CaseExpectation("C", "x", "passed", 10000),
                    CaseExpectation("C", "y", "passed", 1),
                ],
                report_id="r",
            ).reason,
            "limit_exceeded",
        )

    def test_case_and_element_budgets(self) -> None:
        expected = [CaseExpectation("C", "x", "passed", 1)]
        prefix = b'<testsuite tests="1" failures="0" errors="0" skipped="0">'
        one_case = b'<testcase classname="C" name="x"/>'
        many_cases = prefix + one_case * 10001 + b"</testsuite>"
        many_elements = prefix + one_case + b"<extension/>" * 20000 + b"</testsuite>"
        self.assertEqual(
            inspect_junit(many_cases, expected, report_id="r").reason, "limit_exceeded"
        )
        self.assertEqual(
            inspect_junit(many_elements, expected, report_id="r").reason,
            "limit_exceeded",
        )

    def test_malformed_expectation_fields_fail_closed(self) -> None:
        case = CaseExpectation("C", "x", "passed", 1)
        object.__setattr__(case, "name", ["unexpected"])
        xml = b'<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="C" name="x"/></testsuite>'
        self.assertEqual(
            inspect_junit(xml, [case], report_id="r").reason, "invalid_expectation"
        )
        missing = object.__new__(CaseExpectation)
        self.assertEqual(
            inspect_junit(xml, [missing], report_id="r").reason, "invalid_expectation"
        )

    def test_rejects_unsafe_or_malformed_xml(self) -> None:
        bad = [
            b'<!DOCTYPE x [<!ENTITY x "y">]><testsuite/>',
            b'<?xml version="1.0" encoding="ISO-8859-1"?><testsuite/>',
            b"<testsuite>\xff</testsuite>",
            b"<testsuite>",
        ]
        for xml, reason in zip(
            bad,
            ("unsafe_xml", "invalid_xml", "invalid_xml", "invalid_xml"),
            strict=True,
        ):
            with self.subTest(reason=reason):
                self.assertEqual(
                    inspect_junit(
                        xml,
                        [CaseExpectation("C", "x", "passed", 1)],
                        report_id="report",
                    ).reason,
                    reason,
                )

    def test_enforces_size_depth_and_declared_counts(self) -> None:
        self.assertEqual(
            inspect_junit(
                b"x" * 1_048_577,
                [CaseExpectation("C", "x", "passed", 1)],
                report_id="report",
            ).reason,
            "limit_exceeded",
        )
        self.assertEqual(
            inspect_junit(
                b'<testsuite tests="1" failures="0" errors="0" skipped="0"/>',
                [CaseExpectation("C", "x", "passed", 1)],
                report_id="report",
            ).reason,
            "invalid_report",
        )
        self.assertEqual(
            inspect_junit(
                b"<testsuite>" + b"<a>" * 65 + b"</a>" * 65 + b"</testsuite>",
                [CaseExpectation("C", "x", "passed", 1)],
                report_id="report",
            ).reason,
            "limit_exceeded",
        )


class TaskObservationTest(unittest.TestCase):
    def test_task_header_is_observation_not_execution_proof(self) -> None:
        self.assertEqual(
            inspect_task_log(b"> Task :x:test\n> Task :x:test\n", ":x:test"), "bare"
        )
        self.assertEqual(
            inspect_task_log(b"> Task :x:testClasses\n", ":x:test"), "missing"
        )
        self.assertEqual(
            inspect_task_log(b"> Task :x:test FROM-CACHE\n", ":x:test"), "from_cache"
        )
        self.assertEqual(
            inspect_task_log(b"> Task :x:test UP-TO-DATE\n", ":x:test"), "up_to_date"
        )
        self.assertEqual(
            inspect_task_log(b"> Task :x:test\n> Task :x:test FROM-CACHE\n", ":x:test"),
            "ambiguous",
        )

    def test_task_log_work_limit_and_no_source(self) -> None:
        self.assertEqual(inspect_task_log(b"\n" * 100001, ":x:test"), "invalid")
        self.assertEqual(
            inspect_task_log(b"> Task :x:test NO-SOURCE\n", ":x:test"), "no_source"
        )
