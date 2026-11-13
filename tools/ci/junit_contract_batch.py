# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

"""Bounded stdin/stdout adapter; report matching does not attest execution."""

from __future__ import annotations

import base64
import binascii
import json
from pathlib import Path
import sys
from collections.abc import Callable, Mapping, Sequence
from typing import TYPE_CHECKING, Literal, TypeGuard, TypedDict

if TYPE_CHECKING:
    from tools.ci.junit_contract import CaseExpectation, Outcome

MAX_INPUT_BYTES = 32 * 1024 * 1024
MAX_XML_BYTES = 16 * 1024 * 1024
MAX_EXPECTED_BYTES = 4 * 1024 * 1024
MAX_REPORTS = 1024
MAX_CASES = 10_000
MAX_JSON_DEPTH = 16
MAX_OUTPUT_BYTES = 512 * 1024


class ReportOutput(TypedDict):
    index: int
    reason: str
    artifactSha256: str | None
    expectedContractSha256: str
    counts: list[int]
    duplicateCaseRows: int


class BatchOutput(TypedDict):
    schemaVersion: Literal[1]
    status: Literal["compared", "invalid_input", "limit_exceeded"]
    ok: bool
    reports: list[ReportOutput]


class InvalidBatch(ValueError):
    pass


class BatchLimit(ValueError):
    pass


def _pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise InvalidBatch
        result[key] = value
    return result


def _constant(_value: str) -> object:
    raise InvalidBatch


def _is_object(value: object) -> TypeGuard[Mapping[object, object]]:
    return isinstance(value, dict)


def _is_array(value: object) -> TypeGuard[Sequence[object]]:
    return isinstance(value, list)


def _object(value: object, keys: set[str]) -> dict[str, object]:
    if not _is_object(value):
        raise InvalidBatch
    result: dict[str, object] = {}
    for key, item in value.items():
        if not isinstance(key, str):
            raise InvalidBatch
        result[key] = item
    if set(result) != keys:
        raise InvalidBatch
    return result


def _array(value: object) -> list[object]:
    if not _is_array(value):
        raise InvalidBatch
    return list(value)


def _text(value: object) -> str:
    if not isinstance(value, str) or not 0 < len(value) <= 4096:
        raise InvalidBatch
    return value


def _outcome(value: object) -> Outcome:
    if value == "passed":
        return "passed"
    if value == "failed":
        return "failed"
    if value == "error":
        return "error"
    if value == "skipped":
        return "skipped"
    raise InvalidBatch


def _expected(value: object) -> list[CaseExpectation]:
    from tools.ci.junit_contract import CaseExpectation

    records = _array(value)
    if not records:
        raise InvalidBatch
    if len(records) > MAX_CASES:
        raise BatchLimit
    result: list[CaseExpectation] = []
    for record in records:
        row = _array(record)
        if len(row) != 4:
            raise InvalidBatch
        count = row[3]
        if type(count) is not int or not 1 <= count <= MAX_CASES:
            raise InvalidBatch
        result.append(
            CaseExpectation(_text(row[0]), _text(row[1]), _outcome(row[2]), count)
        )
    return result


def _decoded_value(decode: Callable[[str], object], text: str) -> object:
    # The stdlib decoder's untyped result enters only as object; callers below
    # validate every field before constructing the comparator's typed values.
    return decode(text)


def _decode(data: bytes) -> object:
    if len(data) > MAX_INPUT_BYTES:
        raise BatchLimit
    text = data.decode("utf-8", errors="strict")
    depth = 0
    quoted = escaped = False
    for char in text:
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in "[{":
            depth += 1
            if depth > MAX_JSON_DEPTH:
                raise BatchLimit
        elif char in "]}":
            depth -= 1
            if depth < 0:
                raise InvalidBatch
    return _decoded_value(
        json.JSONDecoder(object_pairs_hook=_pairs, parse_constant=_constant).decode,
        text,
    )


def _compare(records: list[object]) -> list[ReportOutput]:
    from tools.ci.junit_contract import MAX_BYTES, inspect_junit

    if not records:
        raise InvalidBatch
    if len(records) > MAX_REPORTS:
        raise BatchLimit
    result: list[ReportOutput] = []
    total_xml = total_cases = total_text = actual_cases = 0
    for index, record in enumerate(records):
        item = _object(record, {"reportId", "xmlBase64", "expected"})
        report_id = _text(item["reportId"])
        expected = _expected(item["expected"])
        total_cases += sum(row.multiplicity for row in expected)
        total_text += len(report_id.encode("utf-8")) + sum(
            len(row.classname.encode("utf-8")) + len(row.name.encode("utf-8"))
            for row in expected
        )
        if total_cases > MAX_CASES or total_text > MAX_EXPECTED_BYTES:
            raise BatchLimit
        encoded = item["xmlBase64"]
        if not isinstance(encoded, str):
            raise InvalidBatch
        if len(encoded) > ((MAX_BYTES + 2) // 3) * 4:
            raise BatchLimit
        xml = base64.b64decode(encoded, validate=True)
        if base64.b64encode(xml).decode("ascii") != encoded:
            raise InvalidBatch
        total_xml += len(xml)
        if len(xml) > MAX_BYTES or total_xml > MAX_XML_BYTES:
            raise BatchLimit
        observation = inspect_junit(xml, expected, report_id=report_id)
        actual_cases += observation.counts[0]
        if actual_cases > MAX_CASES:
            raise BatchLimit
        result.append(
            {
                "index": index,
                "reason": observation.reason,
                "artifactSha256": observation.artifact_sha256,
                "expectedContractSha256": observation.expected_contract_sha256,
                "counts": list(observation.counts),
                "duplicateCaseRows": observation.duplicate_case_rows,
            }
        )
    return result


def compare_batch(data: bytes) -> BatchOutput:
    """Compare bounded supplied bytes; no file reads or execution provenance."""
    try:
        document = _object(_decode(data), {"schemaVersion", "reports"})
        if type(document["schemaVersion"]) is not int or document["schemaVersion"] != 1:
            raise InvalidBatch
        reports = _compare(_array(document["reports"]))
        result: BatchOutput = {
            "schemaVersion": 1,
            "status": "compared",
            "ok": all(row["reason"] == "ok" for row in reports),
            "reports": reports,
        }
        if len(encode_result(result)) > MAX_OUTPUT_BYTES:
            raise BatchLimit
        return result
    except BatchLimit:
        return {
            "schemaVersion": 1,
            "status": "limit_exceeded",
            "ok": False,
            "reports": [],
        }
    except (InvalidBatch, ValueError, UnicodeError, binascii.Error, RecursionError):
        return {
            "schemaVersion": 1,
            "status": "invalid_input",
            "ok": False,
            "reports": [],
        }


def encode_result(result: BatchOutput) -> bytes:
    return (
        json.dumps(result, separators=(",", ":"), ensure_ascii=True) + "\n"
    ).encode()


def main() -> int:
    # -I -S omits the caller's cwd, site hooks and PYTHONPATH. Only the selected
    # script's canonical repository supplies the reviewed sibling comparator.
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    result: BatchOutput
    if len(sys.argv) != 1:
        result = {
            "schemaVersion": 1,
            "status": "invalid_input",
            "ok": False,
            "reports": [],
        }
    else:
        result = compare_batch(sys.stdin.buffer.read(MAX_INPUT_BYTES + 1))
    _ = sys.stdout.buffer.write(encode_result(result))
    if result["status"] != "compared":
        return 2
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
