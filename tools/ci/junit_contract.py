# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

"""Bounded JUnit report comparison. This parser does not attest test execution."""

from __future__ import annotations

import hashlib
import io
import json
import re
import xml.etree.ElementTree as ET
from collections import Counter
from dataclasses import dataclass
from typing import Literal

Outcome = Literal["passed", "failed", "error", "skipped"]
Reason = Literal[
    "ok",
    "invalid_xml",
    "unsafe_xml",
    "limit_exceeded",
    "invalid_report",
    "invalid_expectation",
    "case_mismatch",
]
MAX_BYTES = 1_048_576
MAX_ELEMENTS = 20_000
MAX_DEPTH = 64
MAX_TEXT = 524_288
MAX_CASES = 10_000
_DECL = re.compile(r"^\s*<\?xml\s+([^?]*)\?>", re.IGNORECASE)
_ENCODING = re.compile(r'\bencoding\s*=\s*([\'"])([^\'"]+)\1', re.IGNORECASE)


@dataclass(frozen=True)
class CaseExpectation:
    classname: str
    name: str
    outcome: Outcome
    multiplicity: int


@dataclass(frozen=True)
class ReportResult:
    reason: Reason
    artifact_sha256: str | None
    expected_contract_sha256: str
    counts: tuple[int, int, int, int]
    duplicate_case_rows: int


def _valid_container(data: object, expected: object, report_id: object) -> bool:
    return (
        isinstance(data, bytes)
        and isinstance(expected, list)
        and isinstance(report_id, str)
    )


def _valid_text(value: object) -> bool:
    return isinstance(value, str) and 0 < len(value) <= 4096


def _valid_count(value: object) -> bool:
    return type(value) is int and 1 <= value <= MAX_CASES


def _valid_case(value: object) -> bool:
    if not isinstance(value, CaseExpectation):
        return False
    try:
        return (
            _valid_text(value.classname)
            and _valid_text(value.name)
            and value.outcome in ("passed", "failed", "error", "skipped")
            and _valid_count(value.multiplicity)
        )
    except AttributeError:
        return False


def inspect_junit(
    data: bytes, expected: list[CaseExpectation], *, report_id: str
) -> ReportResult:
    """Compare a report with a source-owned mapping; its digest is not source provenance."""
    digest: str | None = None
    expected_digest = hashlib.sha256(b"").hexdigest()

    def result(
        reason: Reason,
        counts: tuple[int, int, int, int] = (0, 0, 0, 0),
        duplicate_case_rows: int = 0,
    ) -> ReportResult:
        return ReportResult(
            reason, digest, expected_digest, counts, duplicate_case_rows
        )

    if not _valid_container(data, expected, report_id):
        return result("invalid_expectation")
    if len(data) > MAX_BYTES or len(expected) > MAX_CASES:
        return result("limit_exceeded")
    digest = hashlib.sha256(data).hexdigest()
    if (
        not expected
        or not _valid_text(report_id)
        or any(not _valid_case(e) for e in expected)
    ):
        return result("invalid_expectation")
    if sum(e.multiplicity for e in expected) > MAX_CASES:
        return result("limit_exceeded")
    if sum(len(e.classname) + len(e.name) for e in expected) > MAX_TEXT:
        return result("limit_exceeded")
    encoded = json.dumps(
        [
            report_id,
            [[e.classname, e.name, e.outcome, e.multiplicity] for e in expected],
        ],
        ensure_ascii=True,
        separators=(",", ":"),
    ).encode()
    expected_digest = hashlib.sha256(encoded).hexdigest()
    try:
        text = data.decode("utf-8-sig", errors="strict")
    except UnicodeDecodeError:
        return result("invalid_xml")
    if re.search(r"<!\s*(?:DOCTYPE|ENTITY)\b", text, re.IGNORECASE):
        return result("unsafe_xml")
    declaration = _DECL.match(text)
    if declaration:
        encoding = _ENCODING.search(declaration.group(1))
        if encoding and encoding.group(2).lower() != "utf-8":
            return result("invalid_xml")
    depth = elements = chars = 0
    root: ET.Element | None = None
    try:
        for event, element in ET.iterparse(io.StringIO(text), events=("start", "end")):
            if event == "start":
                depth += 1
                elements += 1
                if root is None:
                    root = element
                if depth > MAX_DEPTH or elements > MAX_ELEMENTS:
                    return result("limit_exceeded")
            else:
                chars += len(element.text or "") + len(element.tail or "")
                if chars > MAX_TEXT:
                    return result("limit_exceeded")
                depth -= 1
    except ET.ParseError:
        return result("invalid_xml")
    if root is None or root.tag != "testsuite":
        return result("invalid_report")
    cases = root.findall("testcase")
    if len(cases) > MAX_CASES:
        return result("limit_exceeded")
    if len(list(root.iter("testsuite"))) != 1 or len(
        list(root.iter("testcase"))
    ) != len(cases):
        return result("invalid_report")
    for marker in ("failure", "error", "skipped"):
        if sum(1 for _ in root.iter(marker)) != sum(
            len(case.findall(marker)) for case in cases
        ):
            return result("invalid_report")
    actual: Counter[tuple[str, str, str]] = Counter()
    totals = [len(cases), 0, 0, 0]
    for case in cases:
        classname, name = case.get("classname"), case.get("name")
        if not classname or not name:
            return result("invalid_report")
        markers = [
            tag for tag in ("failure", "error", "skipped") if case.find(tag) is not None
        ]
        if len(markers) > 1 or any(len(case.findall(tag)) > 1 for tag in markers):
            return result("invalid_report")
        marker = markers[0] if markers else "passed"
        if marker != "passed":
            totals[{"failure": 1, "error": 2, "skipped": 3}[marker]] += 1
        outcome = "failed" if marker == "failure" else marker
        actual[(classname, name, outcome)] += 1
    counts = (totals[0], totals[1], totals[2], totals[3])
    try:
        declared = tuple(
            int(root.attrib[key]) for key in ("tests", "failures", "errors", "skipped")
        )
    except (KeyError, ValueError):
        return result("invalid_report")
    if declared != counts:
        return result("invalid_report")
    wanted: Counter[tuple[str, str, str]] = Counter()
    for e in expected:
        wanted[(e.classname, e.name, e.outcome)] += e.multiplicity
    distinct = {(classname, name) for classname, name, _ in actual}
    duplicates = len(cases) - len(distinct)
    return result("ok" if actual == wanted else "case_mismatch", counts, duplicates)


TaskObservation = Literal[
    "bare",
    "from_cache",
    "up_to_date",
    "skipped",
    "no_source",
    "failed",
    "missing",
    "ambiguous",
    "invalid",
]


def inspect_task_log(data: bytes, task: str) -> TaskObservation:
    """Classify exact task headers only; a bare header is not proof of fresh execution."""
    if len(data) > 16 * 1024 * 1024 or not re.fullmatch(r":[A-Za-z0-9_.:-]+", task):
        return "invalid"
    try:
        text = data.decode("utf-8", errors="strict")
    except UnicodeDecodeError:
        return "invalid"
    if "\x00" in text:
        return "invalid"
    marker = "> Task " + task
    statuses: set[str] = set()
    for line_number, raw in enumerate(io.StringIO(text), start=1):
        if line_number > 100_000 or len(raw) > 65_536:
            return "invalid"
        line = raw.rstrip("\r\n")
        if line == marker:
            statuses.add("bare")
        elif line.startswith(marker + " "):
            statuses.add(line[len(marker) + 1 :])
    if not statuses:
        return "missing"
    if len(statuses) != 1:
        return "ambiguous"
    values: dict[str, TaskObservation] = {
        "bare": "bare",
        "FROM-CACHE": "from_cache",
        "UP-TO-DATE": "up_to_date",
        "SKIPPED": "skipped",
        "NO-SOURCE": "no_source",
        "FAILED": "failed",
    }
    return values.get(next(iter(statuses)), "invalid")
