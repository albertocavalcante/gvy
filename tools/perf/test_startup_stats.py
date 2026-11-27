# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

import contextlib
import io
import json
import os
import platform
import re
import signal
import sys
import tempfile
import time
import unittest
from collections.abc import Mapping
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Self
from unittest import mock

from tools.perf import startup_stats
from tools.perf.startup_stats import JsonObject, JsonValue, RunConfig, RunResult

REPO_ROOT = Path(__file__).resolve().parents[2]
FAKE_SERVER = Path(__file__).resolve().with_name("fake_lsp_server.py")
LAUNCH_ARGS_TS = REPO_ROOT / "editors/code/client/src/server/launchArgs.ts"
SERVER_STATS_DOC = REPO_ROOT / "docs/lsp/server-stats.md"
PHASES = (
    "lsp.initialize",
    "workspace.setup",
    "dependencies.resolve",
    "workspace.model.publish",
    "workspace.index",
    "workspace.compile",
)
NULL_IDENTITY: JsonObject = {
    "javaFeature": None,
    "javaVersion": None,
    "maxHeapBytes": None,
}


def as_list(value: JsonValue) -> list[JsonValue]:
    assert isinstance(value, list)
    return value


def as_object(value: JsonValue) -> JsonObject:
    assert isinstance(value, dict)
    return value


def at(value: JsonValue, *path: str | int) -> JsonValue:
    for step in path:
        value = (
            as_list(value)[step] if isinstance(step, int) else as_object(value)[step]
        )
    return value


def stats_with(statuses: list[str]) -> JsonObject:
    phases: list[JsonValue] = [
        {"name": f"phase.{i}", "status": status} for i, status in enumerate(statuses)
    ]
    return {
        "schemaVersion": 1,
        "build": {"versionState": "unknown"},
        "startup": {"phases": phases},
    }


def documented_stats() -> JsonValue:
    text = SERVER_STATS_DOC.read_text(encoding="utf-8")
    match = re.search(r"```json\n(.*?)\n```", text, re.DOTALL)
    assert match is not None
    return startup_stats.parse_json(match.group(1).encode("utf-8"))


def run_with(
    index: int, spawn_to_settled_ms: int | None, compile_phase: tuple[str, int | None]
) -> RunResult:
    phases: list[JsonValue] = [
        {"name": name, "status": "completed", "durationMs": 1} for name in PHASES[:-1]
    ]
    compile_entry: JsonObject = {"name": PHASES[-1], "status": compile_phase[0]}
    if compile_phase[1] is not None:
        compile_entry["durationMs"] = compile_phase[1]
    phases.append(compile_entry)
    return RunResult(
        index=index,
        outcome="settled" if spawn_to_settled_ms is not None else "timeout",
        spawn_to_initialize_response_ms=10,
        spawn_to_settled_ms=spawn_to_settled_ms,
        quiescent_observed=True,
        status_files_total=1,
        server_stats={"schemaVersion": 1, "build": {}, "startup": {"phases": phases}},
        runtime=None,
        shutdown="clean",
    )


class IsSettledTest(unittest.TestCase):
    def test_all_terminal_statuses_are_settled(self) -> None:
        self.assertTrue(
            startup_stats.is_settled(
                stats_with(["completed", "failed", "cancelled", "skipped"])
            )
        )

    def test_non_terminal_or_unknown_statuses_are_not_settled(self) -> None:
        for statuses in (
            ["pending"],
            ["completed", "running"],
            ["completed", "paused"],
            [],
        ):
            with self.subTest(statuses=statuses):
                self.assertFalse(startup_stats.is_settled(stats_with(statuses)))

    def test_missing_startup_is_not_settled(self) -> None:
        self.assertFalse(startup_stats.is_settled({"schemaVersion": 1}))


class ValidateServerStatsTest(unittest.TestCase):
    def test_accepts_documented_example_unmodified(self) -> None:
        example = documented_stats()
        self.assertEqual(startup_stats.validate_server_stats(example), example)

    def test_rejects_other_schema_version(self) -> None:
        example = as_object(documented_stats())
        example["schemaVersion"] = 2
        with self.assertRaises(startup_stats.ProtocolError):
            _ = startup_stats.validate_server_stats(example)

    def test_rejects_missing_phases(self) -> None:
        with self.assertRaises(startup_stats.ProtocolError):
            _ = startup_stats.validate_server_stats(
                {"schemaVersion": 1, "build": {}, "startup": {}}
            )

    def test_rejects_strings_that_look_like_paths_or_uris(self) -> None:
        for text in ("/home/x", "file:///x", "a b", "", "x" * 65):
            with self.subTest(text=text):
                stats = stats_with(["completed"])
                stats["build"] = {"version": text}
                with self.assertRaises(startup_stats.ProtocolError):
                    _ = startup_stats.validate_server_stats(stats)

    def test_rejects_non_object(self) -> None:
        with self.assertRaises(startup_stats.ProtocolError):
            _ = startup_stats.validate_server_stats([1])

    def test_rejects_deeply_nested_value_without_recursion_error(self) -> None:
        nested: JsonValue = "x"
        for _ in range(5000):
            nested = [nested]
        stats = stats_with(["completed"])
        stats["build"] = {"deep": nested}
        with self.assertRaises(startup_stats.ProtocolError):
            _ = startup_stats.validate_server_stats(stats)


class RuntimeIdentityTest(unittest.TestCase):
    def test_keeps_only_java_and_heap_identity(self) -> None:
        facts: JsonValue = {
            "schemaVersion": 1,
            "runtime": {
                "processState": "known",
                "processId": 4242,
                "javaState": "known",
                "javaFeature": 17,
                "javaVersion": "17.0.13",
                "heapState": "known",
                "maxHeapBytes": 2147483648,
            },
            "build": {"versionState": "unknown"},
            "hoverEngine": {"configured": "openrewrite", "state": "unknown"},
        }
        identity = startup_stats.runtime_identity(facts)
        self.assertEqual(
            identity,
            {"javaFeature": 17, "javaVersion": "17.0.13", "maxHeapBytes": 2147483648},
        )
        self.assertNotIn("4242", json.dumps(identity))

    def test_unknown_or_malformed_values_become_null(self) -> None:
        facts: JsonValue = {
            "schemaVersion": 1,
            "runtime": {
                "javaState": "unknown",
                "heapState": "known",
                "maxHeapBytes": True,
            },
        }
        self.assertEqual(startup_stats.runtime_identity(facts), NULL_IDENTITY)

    def test_non_object_yields_all_null(self) -> None:
        self.assertEqual(startup_stats.runtime_identity(None), NULL_IDENTITY)


class SummarizeTest(unittest.TestCase):
    def test_excludes_first_run_and_uses_odd_median(self) -> None:
        runs = [
            run_with(0, 9000, ("completed", 90)),
            run_with(1, 200, ("completed", 20)),
        ]
        runs += [
            run_with(2, 400, ("completed", 40)),
            run_with(3, 300, ("completed", 30)),
        ]
        summary = startup_stats.summarize(runs)
        self.assertEqual(summary["pooledPosition"], "repeat")
        self.assertEqual(
            summary["spawnToSettledMs"],
            {"n": 3, "medianMs": 300, "minMs": 200, "maxMs": 400},
        )

    def test_even_median_rounds_down(self) -> None:
        runs = [run_with(0, 1, ("completed", 1))]
        runs += [
            run_with(i, ms, ("completed", ms))
            for i, ms in enumerate((200, 400, 300, 501), start=1)
        ]
        summary = startup_stats.summarize(runs)
        self.assertEqual(
            summary["spawnToSettledMs"],
            {"n": 4, "medianMs": 350, "minMs": 200, "maxMs": 501},
        )

    def test_counts_phases_by_completed_and_other_statuses(self) -> None:
        runs = [
            run_with(0, 1, ("completed", 1)),
            run_with(1, 10, ("completed", 50)),
            run_with(2, 20, ("failed", 5)),
        ]
        runs.append(run_with(3, None, ("running", None)))
        phases = as_list(startup_stats.summarize(runs)["phases"])
        self.assertEqual([at(phase, "name") for phase in phases], list(PHASES))
        self.assertEqual(
            phases[-1],
            {
                "name": "workspace.compile",
                "completedRuns": 1,
                "otherRuns": 2,
                "medianMs": 50,
                "minMs": 50,
                "maxMs": 50,
            },
        )

    def test_no_repeat_runs_gives_nulls(self) -> None:
        summary = startup_stats.summarize([run_with(0, 5, ("completed", 5))])
        self.assertEqual(
            summary["spawnToSettledMs"],
            {"n": 0, "medianMs": None, "minMs": None, "maxMs": None},
        )
        self.assertEqual(
            at(summary["phases"], 0),
            {
                "name": "lsp.initialize",
                "completedRuns": 0,
                "otherRuns": 0,
                "medianMs": None,
                "minMs": None,
                "maxMs": None,
            },
        )


class RunIdentityTest(unittest.TestCase):
    def test_runtime_comes_from_the_first_run_that_has_it(self) -> None:
        without = run_with(0, 5, ("completed", 1))
        with_runtime = replace(
            run_with(1, 5, ("completed", 1)), runtime={"javaFeature": 17}
        )
        build, runtime = startup_stats.run_identity([without, with_runtime])
        self.assertEqual(build, {})
        self.assertEqual(runtime, {"javaFeature": 17})

    def test_no_runtime_facts_gives_all_null(self) -> None:
        self.assertEqual(startup_stats.run_identity([])[1], NULL_IDENTITY)


class IdentityAgreementTest(unittest.TestCase):
    def test_missing_runtime_facts_do_not_count_as_disagreement(self) -> None:
        with_runtime = replace(
            run_with(1, 5, ("completed", 1)), runtime={"javaFeature": 17}
        )
        without_runtime = run_with(2, 6, ("completed", 1))
        self.assertTrue(
            startup_stats.runs_agree_on_identity([with_runtime, without_runtime])
        )

    def test_different_runtime_or_build_is_a_disagreement(self) -> None:
        first = replace(run_with(1, 5, ("completed", 1)), runtime={"javaFeature": 17})
        other_runtime = replace(first, runtime={"javaFeature": 21})
        self.assertFalse(startup_stats.runs_agree_on_identity([first, other_runtime]))
        other_build = replace(
            first,
            server_stats={"schemaVersion": 1, "build": {"versionState": "unknown"}},
        )
        self.assertFalse(startup_stats.runs_agree_on_identity([first, other_build]))


class HostIdentityTest(unittest.TestCase):
    def test_unexpected_names_become_a_fixed_token(self) -> None:
        with mock.patch.object(platform, "system", return_value="Odd OS/1"):
            self.assertEqual(startup_stats.host_os(), "unknown")
        with mock.patch.object(platform, "machine", return_value="/odd arch"):
            self.assertEqual(startup_stats.host_arch(), "unknown")

    def test_known_names_are_normalised(self) -> None:
        with mock.patch.object(platform, "system", return_value="Darwin"):
            self.assertEqual(startup_stats.host_os(), "macos")
        with mock.patch.object(platform, "machine", return_value="AMD64"):
            self.assertEqual(startup_stats.host_arch(), "x64")


class WriteNewFileTest(unittest.TestCase):
    def test_a_failed_write_removes_the_file_it_created(self) -> None:
        path = Path(self.enterContext(tempfile.TemporaryDirectory())) / "out.json"

        class FailingHandle:
            def __enter__(self) -> Self:
                return self

            def __exit__(self, *exc_info: object) -> None:
                return None

            def write(self, _text: str) -> int:
                raise OSError("disk full")

        def create_then_fail(target: Path, _mode: str, encoding: str) -> FailingHandle:
            self.assertEqual(encoding, "utf-8")
            os.close(os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY))
            return FailingHandle()

        with (
            mock.patch.object(Path, "open", new=create_then_fail),
            self.assertRaises(OSError),
        ):
            startup_stats.write_new_file(path, "text")
        self.assertFalse(path.exists())

    def test_never_overwrites_an_existing_file(self) -> None:
        path = Path(self.enterContext(tempfile.TemporaryDirectory())) / "out.json"
        _ = path.write_text("keep", encoding="utf-8")
        with self.assertRaises(FileExistsError):
            startup_stats.write_new_file(path, "new")
        self.assertEqual(path.read_text(encoding="utf-8"), "keep")


class LaunchCommandTest(unittest.TestCase):
    def test_command_equals_the_editors_for_java_16_and_later(self) -> None:
        source = LAUNCH_ARGS_TS.read_text(encoding="utf-8")
        defaults = re.search(
            r"DEFAULT_SERVER_VM_ARGS: readonly string\[\] = \[(.*?)\];",
            source,
            re.DOTALL,
        )
        build = re.search(r"export function buildJavaArgs\(.*?\n}\n", source, re.DOTALL)
        assert defaults is not None and build is not None
        body = build.group(0)
        # Defaults first, then exactly these pushes in this order; the first only on Java 16 and later.
        self.assertRegex(body, r"const args = resolveVmArgs\(")
        pushes = [
            m.group(1) for m in re.finditer(r"args\.push\((.*?)\);", body, re.DOTALL)
        ]
        # Tolerate formatter line breaks and trailing commas inside a push(...).
        normalized = [" ".join(push.split()).rstrip(",").strip() for push in pushes]
        self.assertEqual(
            normalized,
            ['"--enable-native-access=ALL-UNNAMED"', '"-jar", input.jarPath'],
        )
        self.assertRegex(body, r"javaVersion\s*>=\s*16")
        editor = re.findall(r'"([^"]+)"', defaults.group(1))
        editor += ["--enable-native-access=ALL-UNNAMED", "-jar", "x.jar"]
        self.assertEqual(
            list(startup_stats.java_command(Path("x.jar"))), ["java", *editor]
        )


class FramingTest(unittest.TestCase):
    def test_round_trip(self) -> None:
        message: JsonObject = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "m",
            "params": {"t": "é"},
        }
        self.assertEqual(
            startup_stats.FrameDecoder().feed(startup_stats.encode_frame(message)),
            [message],
        )

    def test_header_split_across_reads_and_two_frames_in_one_read(self) -> None:
        first: JsonObject = {"id": 1}
        second: JsonObject = {"id": 2}
        data = startup_stats.encode_frame(first) + startup_stats.encode_frame(second)
        decoder = startup_stats.FrameDecoder()
        self.assertEqual(decoder.feed(data[:7]), [])
        self.assertEqual(decoder.feed(data[7:20]), [])
        self.assertEqual(decoder.feed(data[20:]), [first, second])

    def test_rejects_frame_over_the_cap(self) -> None:
        header = f"Content-Length: {startup_stats.MAX_FRAME_BYTES + 1}\r\n\r\n".encode(
            "ascii"
        )
        with self.assertRaises(startup_stats.FrameError):
            _ = startup_stats.FrameDecoder().feed(header)

    def test_rejects_duplicate_length_and_oversized_header(self) -> None:
        with self.assertRaises(startup_stats.FrameError):
            _ = startup_stats.FrameDecoder().feed(
                b"Content-Length: 1\r\nContent-Length: 2\r\n\r\n{}"
            )
        long_header = b"X: " + b"a" * 2000 + b"\r\nContent-Length: 2\r\n\r\n{}"
        with self.assertRaises(startup_stats.FrameError):
            _ = startup_stats.FrameDecoder().feed(long_header)

    def test_a_line_break_does_not_separate_name_and_value(self) -> None:
        with self.assertRaises(startup_stats.FrameError):
            _ = startup_stats.FrameDecoder().feed(b"Content-Length:\r\n 2\r\n\r\n{}")

    def test_rejects_numbers_that_overflow_to_infinity(self) -> None:
        for text in (b'{"a": 1e999}', b'{"a": -1e999}'):
            with (
                self.subTest(text=text),
                self.assertRaises(startup_stats.ProtocolError),
            ):
                _ = startup_stats.parse_json(text)

    def test_rejects_nan_and_infinity(self) -> None:
        for text in (b'{"a": NaN}', b'{"a": Infinity}', b'{"a": -Infinity}'):
            with (
                self.subTest(text=text),
                self.assertRaises(startup_stats.ProtocolError),
            ):
                _ = startup_stats.parse_json(text)

    def test_rejects_missing_length_and_non_object_body(self) -> None:
        with self.assertRaises(startup_stats.FrameError):
            _ = startup_stats.FrameDecoder().feed(b"X: 1\r\n\r\n")
        with self.assertRaises(startup_stats.FrameError):
            _ = startup_stats.FrameDecoder().feed(b"Content-Length: 1\r\n\r\n1")


def fake_config(
    scenario: str,
    workspace: Path,
    timeout_s: float = 30.0,
    poll_interval_s: float = 0.05,
    extra_env: Mapping[str, str] | None = None,
) -> RunConfig:
    return RunConfig(
        command=(sys.executable, "-I", str(FAKE_SERVER), scenario),
        env={"PATH": os.environ.get("PATH", ""), **(extra_env or {})},
        workspace=workspace,
        index=0,
        timeout_s=timeout_s,
        poll_interval_s=poll_interval_s,
    )


class RunOnceTest(unittest.TestCase):
    def run_scenario(
        self, scenario: str, timeout_s: float = 30.0, poll_interval_s: float = 0.05
    ) -> RunResult:
        workspace = Path(self.enterContext(tempfile.TemporaryDirectory()))
        return startup_stats.run_once(
            fake_config(scenario, workspace, timeout_s, poll_interval_s)
        )

    def test_quiescent_status_triggers_the_poll_not_the_interval(self) -> None:
        result = self.run_scenario("settle", timeout_s=20.0, poll_interval_s=60.0)
        self.assertEqual(result.outcome, "settled")

    def test_deeply_nested_frame_is_a_protocol_error_not_a_timeout(self) -> None:
        self.assertEqual(
            self.run_scenario("deep-frame", timeout_s=20.0).outcome, "protocol-error"
        )

    def test_deeply_nested_stats_are_a_protocol_error(self) -> None:
        result = self.run_scenario("deep-stats", timeout_s=20.0)
        self.assertEqual(result.outcome, "protocol-error")
        self.assertIsNone(result.server_stats)

    def test_server_exit_is_noticed_while_a_grandchild_holds_stdout_open(self) -> None:
        directory = Path(self.enterContext(tempfile.TemporaryDirectory()))
        pid_file = directory / "grandchild.pid"

        def reap_grandchild() -> None:
            if pid_file.exists():
                with contextlib.suppress(ProcessLookupError):
                    os.kill(int(pid_file.read_text(encoding="utf-8")), signal.SIGKILL)

        self.addCleanup(reap_grandchild)
        config = fake_config(
            "grandchild",
            directory,
            60.0,
            extra_env={"FAKE_GRANDCHILD_PIDFILE": str(pid_file)},
        )
        started = time.monotonic()
        result = startup_stats.run_once(config)
        self.assertEqual(result.outcome, "server-exited")
        # The grandchild lives for 30 s; waiting for stdout to close would take that long.
        self.assertLess(time.monotonic() - started, 10.0)

    def test_settles_after_quiescent_status(self) -> None:
        result = self.run_scenario("settle")
        self.assertEqual(result.outcome, "settled")
        self.assertTrue(result.quiescent_observed)
        self.assertEqual(result.status_files_total, 1)
        self.assertEqual(result.shutdown, "clean")
        assert result.server_stats is not None
        self.assertTrue(startup_stats.is_settled(result.server_stats))
        self.assertIsNotNone(result.spawn_to_settled_ms)
        self.assertIsNotNone(result.spawn_to_initialize_response_ms)
        self.assertEqual(
            result.runtime,
            {"javaFeature": 17, "javaVersion": "17.0.13", "maxHeapBytes": 2147483648},
        )

    def test_never_settling_server_times_out_and_keeps_last_snapshot(self) -> None:
        result = self.run_scenario("never", timeout_s=1.0)
        self.assertEqual(result.outcome, "timeout")
        self.assertIsNone(result.spawn_to_settled_ms)
        assert result.server_stats is not None
        self.assertFalse(startup_stats.is_settled(result.server_stats))
        self.assertEqual(result.shutdown, "clean")

    def test_server_exiting_early(self) -> None:
        result = self.run_scenario("exit-early")
        self.assertEqual(result.outcome, "server-exited")
        self.assertEqual(result.shutdown, "exited")
        self.assertIsNone(result.server_stats)

    def test_method_not_found_means_stats_unavailable(self) -> None:
        result = self.run_scenario("method-not-found")
        self.assertEqual(result.outcome, "stats-unavailable")
        self.assertIsNone(result.server_stats)
        self.assertEqual(result.shutdown, "clean")

    def test_invalid_stats_are_dropped_as_protocol_error(self) -> None:
        result = self.run_scenario("invalid-stats")
        self.assertEqual(result.outcome, "protocol-error")
        self.assertIsNone(result.server_stats)
        self.assertEqual(result.shutdown, "clean")

    def test_every_server_request_is_answered(self) -> None:
        self.assertEqual(self.run_scenario("requests").outcome, "settled")


@dataclass(frozen=True)
class MainFixture:
    root: Path
    workspace: Path
    jar: Path
    output: Path


def launch_fake(scenario: str) -> startup_stats.LaunchCommand:
    def command(_jar: Path) -> tuple[str, ...]:
        return (sys.executable, "-I", str(FAKE_SERVER), scenario)

    return command


class MainTest(unittest.TestCase):
    def fixture(self) -> MainFixture:
        root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        workspace = root / "secret-project"
        workspace.mkdir()
        jar = root / "server-all.jar"
        _ = jar.write_bytes(b"not a real jar")
        return MainFixture(root, workspace, jar, root / "out.json")

    @staticmethod
    def argv(
        fixture: MainFixture, *extra: str, label: str = "fixture-one"
    ) -> list[str]:
        return [
            "--jar",
            str(fixture.jar),
            "--runs",
            "3",
            "--label",
            label,
            "--output",
            str(fixture.output),
            "--poll-interval-ms",
            "50",
            *extra,
            str(fixture.workspace),
        ]

    @staticmethod
    def run_main(argv: list[str], scenario: str) -> int:
        with contextlib.redirect_stderr(io.StringIO()):
            return startup_stats.main(argv, launch_fake(scenario))

    @staticmethod
    def run_main_captured(
        argv: list[str], launch: startup_stats.LaunchCommand
    ) -> tuple[int, str]:
        captured = io.StringIO()
        with contextlib.redirect_stderr(captured), contextlib.redirect_stdout(captured):
            return startup_stats.main(argv, launch), captured.getvalue()

    def assert_no_paths(self, fixture: MainFixture, text: str) -> None:
        for forbidden in (
            str(fixture.root),
            fixture.root.name,
            "secret-project",
            "server-all",
            "Traceback",
        ):
            self.assertNotIn(forbidden, text)

    def test_unreadable_jar_exits_one_without_traceback_or_path(self) -> None:
        fixture = self.fixture()
        denied = PermissionError(13, "Permission denied", str(fixture.jar))
        with mock.patch.object(startup_stats, "_sha256", side_effect=denied):
            code, output = self.run_main_captured(
                self.argv(fixture), launch_fake("settle")
            )
        self.assertEqual(code, 1)
        self.assertIn("PermissionError", output)
        self.assert_no_paths(fixture, output)
        self.assertFalse(fixture.output.exists())

    def test_jar_deleted_during_the_runs_exits_one_without_output_or_path(self) -> None:
        fixture = self.fixture()
        real_run_once = startup_stats.run_once

        def run_then_delete(config: RunConfig) -> RunResult:
            result = real_run_once(config)
            fixture.jar.unlink(missing_ok=True)
            return result

        with mock.patch.object(startup_stats, "run_once", side_effect=run_then_delete):
            code, output = self.run_main_captured(
                self.argv(fixture), launch_fake("settle")
            )
        self.assertEqual(code, 1)
        self.assert_no_paths(fixture, output)
        self.assertFalse(fixture.output.exists())

    def test_usage_errors_do_not_echo_paths(self) -> None:
        fixture = self.fixture()
        missing = fixture.root / "missing-dir"
        cases = {
            "jar": ["--jar", str(missing / "x.jar")],
            "output": ["--output", str(missing / "out.json")],
            "workspace": [str(missing)],
        }
        for name, override in cases.items():
            with self.subTest(name=name):
                argv = self.argv(fixture)
                if name == "workspace":
                    argv[-1:] = override
                else:
                    argv[argv.index(override[0]) + 1] = override[1]
                code, output = self.run_main_captured(argv, launch_fake("settle"))
                self.assertEqual(code, 2)
                self.assert_no_paths(fixture, output)

    def test_output_has_no_workspace_path_or_name_and_matches_schema(self) -> None:
        fixture = self.fixture()
        self.assertEqual(self.run_main(self.argv(fixture), "settle"), 0)
        data = fixture.output.read_bytes()
        for forbidden in (
            str(fixture.workspace),
            "secret-project",
            str(fixture.jar),
            fixture.root.name,
            "server-all",
        ):
            self.assertNotIn(forbidden.encode("utf-8"), data)
        report = startup_stats.parse_json(data)
        self.assertEqual(at(report, "schema"), "gvy-startup-stats")
        self.assertEqual(at(report, "workspaceLabel"), "fixture-one")
        self.assertEqual(
            [at(run, "position") for run in as_list(at(report, "runs"))],
            ["first", "repeat", "repeat"],
        )
        self.assertEqual(at(report, "summary", "spawnToSettledMs", "n"), 2)
        self.assertEqual(at(report, "identity", "javaFeature"), 17)
        self.assertEqual(
            at(report, "identity", "jvmArgs"), list(startup_stats.JVM_FLAGS)
        )
        removed = as_object(at(report, "identity", "removedEnvironment"))
        self.assertEqual(set(removed), set(startup_stats.REMOVED_ENVIRONMENT))

    def test_refuses_to_overwrite_existing_output(self) -> None:
        fixture = self.fixture()
        _ = fixture.output.write_text("keep", encoding="utf-8")
        self.assertEqual(self.run_main(self.argv(fixture), "settle"), 2)
        self.assertEqual(fixture.output.read_text(encoding="utf-8"), "keep")

    def test_rejects_unsafe_label(self) -> None:
        fixture = self.fixture()
        self.assertEqual(self.run_main(self.argv(fixture, label="../x"), "settle"), 2)
        self.assertFalse(fixture.output.exists())

    def test_unsettled_run_writes_output_and_exits_one(self) -> None:
        fixture = self.fixture()
        argv = self.argv(fixture, "--timeout-seconds", "1", "--runs", "2")
        self.assertEqual(self.run_main(argv, "never"), 1)
        report = startup_stats.parse_json(fixture.output.read_bytes())
        outcomes = [at(run, "outcome") for run in as_list(at(report, "runs"))]
        self.assertEqual(outcomes, ["timeout", "timeout"])

    def test_refuses_to_write_when_the_jar_changes_during_the_runs(self) -> None:
        fixture = self.fixture()

        def launch(jar: Path) -> tuple[str, ...]:
            _ = jar.write_bytes(b"replaced while measuring")
            return launch_fake("settle")(jar)

        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(startup_stats.main(self.argv(fixture), launch), 1)
        self.assertFalse(fixture.output.exists())

    def test_report_building_failure_leaves_no_output_file(self) -> None:
        fixture = self.fixture()
        with (
            mock.patch.object(
                startup_stats, "_build_report", side_effect=RuntimeError("boom")
            ),
            self.assertRaises(RuntimeError),
        ):
            _ = self.run_main(self.argv(fixture), "settle")
        self.assertFalse(fixture.output.exists())

    def test_refuses_to_write_when_runs_disagree_on_identity(self) -> None:
        fixture = self.fixture()
        self.assertEqual(self.run_main(self.argv(fixture), "drifting-identity"), 1)
        self.assertFalse(fixture.output.exists())


@unittest.skipUnless(
    os.environ.get("GVY_STARTUP_STATS_JAR")
    or os.environ.get("GVY_REQUIRE_REAL_SERVER") == "1",
    "set GVY_STARTUP_STATS_JAR to run against a real server jar",
)
class RealServerTest(unittest.TestCase):
    def test_hover_basic_fixture_settles_with_all_phases_completed(self) -> None:
        jar = os.environ.get("GVY_STARTUP_STATS_JAR")
        if not jar:
            self.fail("GVY_REQUIRE_REAL_SERVER=1 needs GVY_STARTUP_STATS_JAR")
        fixture = REPO_ROOT / "tests/e2e/resources/workspaces/hover-basic"
        directory = Path(self.enterContext(tempfile.TemporaryDirectory()))
        workspace = directory / "secret-project"
        workspace.mkdir()
        _ = (workspace / "sample.groovy").write_bytes(
            (fixture / "sample.groovy").read_bytes()
        )
        output = Path(
            os.environ.get("GVY_STARTUP_STATS_OUTPUT") or directory / "out.json"
        )
        argv = [
            "--jar",
            jar,
            "--runs",
            "2",
            "--timeout-seconds",
            "120",
            "--label",
            "hover-basic",
            "--output",
            str(output),
            str(workspace),
        ]
        self.assertEqual(startup_stats.main(argv), 0)
        data = output.read_bytes()
        self.assertNotIn(b"secret-project", data)
        report = startup_stats.parse_json(data)
        runs = as_list(at(report, "runs"))
        self.assertEqual([at(run, "outcome") for run in runs], ["settled", "settled"])
        for run in runs:
            phases = as_list(at(run, "serverStats", "startup", "phases"))
            self.assertEqual([at(phase, "name") for phase in phases], list(PHASES))
            statuses = [at(phase, "status") for phase in phases]
            self.assertEqual(statuses, ["completed"] * len(PHASES))
        self.assertEqual(at(report, "identity", "build", "versionState"), "known")
