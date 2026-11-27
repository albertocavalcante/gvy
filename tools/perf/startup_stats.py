# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0
"""Measure the server's startup phases without an editor.

Each run starts a fresh ``java -jar`` server over stdio, waits until every startup phase in the server's own
``groovy/serverStats`` has ended, and records that result. Procedure and output: tools/perf/startup-stats.md.

Privacy contract: the report holds only the label, numbers, fixed words and strings that passed ``STRING_PATTERN``.
The workspace path and name, the jar path, the host name, message texts and the server's stderr are never recorded.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import queue
import re
import subprocess
import sys
import threading
import time
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import IO, Literal, TypeAlias, TypeGuard, cast, final

JsonValue: TypeAlias = (
    "None | bool | int | float | str | list[JsonValue] | dict[str, JsonValue]"
)
JsonObject: TypeAlias = dict[str, JsonValue]
Outcome = Literal[
    "settled", "timeout", "server-exited", "protocol-error", "stats-unavailable"
]
Shutdown = Literal["clean", "terminated", "killed", "exited"]
LaunchCommand: TypeAlias = Callable[[Path], tuple[str, ...]]

SCHEMA = "gvy-startup-stats"
SCHEMA_VERSION = 1
STATS_SCHEMA_VERSION = 1
PHASE_NAMES = (
    "lsp.initialize",
    "workspace.setup",
    "dependencies.resolve",
    "workspace.model.publish",
    "workspace.index",
    "workspace.compile",
)
TERMINAL_STATUSES = frozenset({"completed", "failed", "cancelled", "skipped"})
# Copy of DEFAULT_SERVER_VM_ARGS in editors/code/client/src/server/launchArgs.ts; a unit test guards against drift.
JVM_FLAGS = (
    "-XX:+ExitOnOutOfMemoryError",
    "-Xms64m",
    "-Xmx2g",
    "-XX:+UseG1GC",
    "-XX:G1PeriodicGCInterval=60000",
    "-XX:MinHeapFreeRatio=20",
    "-XX:MaxHeapFreeRatio=40",
    "-XX:+UseStringDeduplication",
)
REMOVED_ENVIRONMENT = (
    "JAVA_TOOL_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "_JAVA_OPTIONS",
    "CLASSPATH",
)
STRING_PATTERN = re.compile(r"^[A-Za-z0-9._+-]{1,64}$")
LABEL_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
MAX_FRAME_BYTES = 8 * 1024 * 1024
MAX_HEADER_BYTES = 1024
METHOD_NOT_FOUND = -32601
EXIT_GRACE_S = 5.0
TERMINATE_GRACE_S = 5.0
RUNTIME_FACTS_WAIT_S = 5.0
DEFAULT_RUNS = 5
MAX_RUNS = 100
DEFAULT_TIMEOUT_S = 600.0
MAX_TIMEOUT_S = 86400.0
DEFAULT_POLL_INTERVAL_MS = 1000
_CONTENT_LENGTH = re.compile(
    rb"^content-length:\s*([0-9]{1,12})\s*$", re.IGNORECASE | re.MULTILINE
)


class ProtocolError(Exception):
    """The server sent something this tool does not accept."""


class FrameError(ProtocolError):
    """A message frame is malformed or over the size cap."""


# --- Pure core -------------------------------------------------------------------------------------------------------


def _is_list(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def _is_dict(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def _json_value(raw: object) -> JsonValue:
    if raw is None or isinstance(raw, (bool, int, float, str)):
        return raw
    if _is_list(raw):
        return [_json_value(item) for item in raw]
    if _is_dict(raw):
        result: JsonObject = {}
        for key, item in raw.items():
            if not isinstance(key, str):
                raise ProtocolError("JSON object key is not a string")
            result[key] = _json_value(item)
        return result
    raise ProtocolError("not a JSON value")


def parse_json(data: bytes) -> JsonValue:
    """Decode JSON from bytes into ``JsonValue``; raise ``ProtocolError`` when it is not valid JSON."""
    try:
        # The stdlib decoder is typed as Any; the structure is validated by _json_value.
        raw = cast(object, json.loads(data))
    except (ValueError, RecursionError) as error:
        raise ProtocolError("not valid JSON") from error
    return _json_value(raw)


def encode_frame(message: JsonObject) -> bytes:
    body = json.dumps(message, separators=(",", ":")).encode("utf-8")
    return b"Content-Length: " + str(len(body)).encode("ascii") + b"\r\n\r\n" + body


@final
class FrameDecoder:
    """Incremental decoder for ``Content-Length`` framed JSON objects."""

    def __init__(self) -> None:
        self._buffer: bytearray = bytearray()

    def feed(self, data: bytes) -> list[JsonObject]:
        """Return every complete message in the buffered bytes; raise ``FrameError`` on a bad or oversized frame."""
        self._buffer += data
        messages: list[JsonObject] = []
        while (message := self._next()) is not None:
            messages.append(message)
        return messages

    def _next(self) -> JsonObject | None:
        header_end = self._buffer.find(b"\r\n\r\n")
        if header_end < 0:
            if len(self._buffer) > MAX_HEADER_BYTES:
                raise FrameError("header too long")
            return None
        if header_end > MAX_HEADER_BYTES:
            raise FrameError("header too long")
        lengths = list(_CONTENT_LENGTH.finditer(bytes(self._buffer[:header_end])))
        if len(lengths) != 1:
            raise FrameError("expected exactly one Content-Length")
        length = int(lengths[0].group(1))
        if length > MAX_FRAME_BYTES:
            raise FrameError("frame over the size cap")
        body_start = header_end + 4
        if len(self._buffer) < body_start + length:
            return None
        body = bytes(self._buffer[body_start : body_start + length])
        del self._buffer[: body_start + length]
        try:
            parsed = parse_json(body)
        except ProtocolError as error:
            raise FrameError("body is not JSON") from error
        if not isinstance(parsed, dict):
            raise FrameError("body is not an object")
        return parsed


def _phases_of(stats: Mapping[str, JsonValue]) -> list[JsonValue]:
    startup = stats.get("startup")
    phases = startup.get("phases") if isinstance(startup, dict) else None
    return phases if isinstance(phases, list) else []


def is_settled(stats: Mapping[str, JsonValue]) -> bool:
    """True when the snapshot lists phases and every one has a terminal status."""
    phases = _phases_of(stats)
    return bool(phases) and all(
        isinstance(p, dict) and p.get("status") in TERMINAL_STATUSES for p in phases
    )


def _check_strings(value: JsonValue) -> None:
    if isinstance(value, str):
        if STRING_PATTERN.fullmatch(value) is None:
            raise ProtocolError(
                "serverStats holds a string outside the allowed pattern"
            )
    elif isinstance(value, list):
        for item in value:
            _check_strings(item)
    elif isinstance(value, dict):
        for key, item in value.items():
            _check_strings(key)
            _check_strings(item)


def validate_server_stats(value: JsonValue) -> JsonObject:
    """Return ``value`` unchanged when it is a schema version 1 result whose every string matches ``STRING_PATTERN``.

    Raises ``ProtocolError`` otherwise. The string check is a privacy tripwire: a path, URI or message text fails it.
    """
    if not isinstance(value, dict):
        raise ProtocolError("serverStats is not an object")
    if value.get("schemaVersion") != STATS_SCHEMA_VERSION or isinstance(
        value.get("schemaVersion"), bool
    ):
        raise ProtocolError("unsupported serverStats schemaVersion")
    if not _phases_of(value):
        raise ProtocolError("serverStats has no phases")
    _check_strings(value)
    return value


def _known_value(section: JsonValue, state_key: str, value_key: str) -> JsonValue:
    if not isinstance(section, dict) or section.get(state_key) != "known":
        return None
    return section.get(value_key)


def _positive_int(value: JsonValue) -> int | None:
    return (
        value
        if isinstance(value, int) and not isinstance(value, bool) and value > 0
        else None
    )


def runtime_identity(facts: JsonValue) -> JsonObject:
    """Reduce a ``groovy/runtimeFacts`` result to the Java and heap identity; anything else is dropped.

    A value that is not in the ``known`` state, or has the wrong shape, becomes ``None``.
    """
    runtime = (
        facts.get("runtime")
        if isinstance(facts, dict) and facts.get("schemaVersion") == 1
        else None
    )
    version = _known_value(runtime, "javaState", "javaVersion")
    return {
        "javaFeature": _positive_int(_known_value(runtime, "javaState", "javaFeature")),
        "javaVersion": version
        if isinstance(version, str) and STRING_PATTERN.fullmatch(version)
        else None,
        "maxHeapBytes": _positive_int(
            _known_value(runtime, "heapState", "maxHeapBytes")
        ),
    }


@dataclass(frozen=True)
class RunResult:
    index: int
    outcome: Outcome
    spawn_to_initialize_response_ms: int | None
    spawn_to_settled_ms: int | None
    quiescent_observed: bool
    status_files_total: int | None
    server_stats: JsonObject | None
    runtime: JsonObject | None
    shutdown: Shutdown

    @property
    def position(self) -> Literal["first", "repeat"]:
        return "first" if self.index == 0 else "repeat"

    def as_json(self) -> JsonObject:
        return {
            "index": self.index,
            "position": self.position,
            "outcome": self.outcome,
            "spawnToInitializeResponseMs": self.spawn_to_initialize_response_ms,
            "spawnToSettledMs": self.spawn_to_settled_ms,
            "quiescentObserved": self.quiescent_observed,
            "statusFilesTotal": self.status_files_total,
            "serverStats": self.server_stats,
            "shutdown": self.shutdown,
        }


def _distribution(values: list[int]) -> JsonObject:
    ordered = sorted(values)
    count = len(ordered)
    if count == 0:
        return {"n": 0, "medianMs": None, "minMs": None, "maxMs": None}
    middle = count // 2
    median = (
        ordered[middle] if count % 2 else (ordered[middle - 1] + ordered[middle]) // 2
    )
    return {"n": count, "medianMs": median, "minMs": ordered[0], "maxMs": ordered[-1]}


def _phase_entry(
    phases: Sequence[JsonValue], name: str
) -> Mapping[str, JsonValue] | None:
    for phase in phases:
        if isinstance(phase, dict) and phase.get("name") == name:
            return phase
    return None


def _phase_summary(name: str, repeats: Sequence[RunResult]) -> JsonObject:
    durations: list[int] = []
    other = 0
    for run in repeats:
        entry = (
            _phase_entry(_phases_of(run.server_stats), name)
            if run.server_stats is not None
            else None
        )
        duration = entry.get("durationMs") if entry is not None else None
        if (
            entry is not None
            and entry.get("status") == "completed"
            and _is_int(duration)
            and isinstance(duration, int)
        ):
            durations.append(duration)
        else:
            other += 1
    pooled = _distribution(durations)
    return {
        "name": name,
        "completedRuns": pooled["n"],
        "otherRuns": other,
        "medianMs": pooled["medianMs"],
        "minMs": pooled["minMs"],
        "maxMs": pooled["maxMs"],
    }


def summarize(runs: Sequence[RunResult]) -> JsonObject:
    """Pool the repeat runs only: run 0 starts from an unknown prior state and is reported on its own.

    Medians are rounded down. A phase counts as completed only with status ``completed``; any other status or a
    missing snapshot counts under ``otherRuns``.
    """
    repeats = [run for run in runs if run.position == "repeat"]
    settled = [
        run.spawn_to_settled_ms
        for run in repeats
        if run.spawn_to_settled_ms is not None
    ]
    phases: list[JsonValue] = [_phase_summary(name, repeats) for name in PHASE_NAMES]
    return {
        "pooledPosition": "repeat",
        "spawnToSettledMs": _distribution(settled),
        "phases": phases,
    }


def runs_agree_on_identity(runs: Sequence[RunResult]) -> bool:
    """True when the runs that reported a build stamp agree on it, and the runs that reported runtime facts agree.

    A run without stats or without runtime facts (for example after a timeout) takes no part in that comparison.
    """
    builds = {
        json.dumps(run.server_stats.get("build"), sort_keys=True)
        for run in runs
        if run.server_stats is not None
    }
    runtimes = {
        json.dumps(run.runtime, sort_keys=True)
        for run in runs
        if run.runtime is not None
    }
    return len(builds) <= 1 and len(runtimes) <= 1


# --- IO: child process and session -----------------------------------------------------------------------------------


@dataclass(frozen=True)
class RunConfig:
    command: tuple[str, ...]
    env: Mapping[str, str]
    workspace: Path
    index: int
    timeout_s: float
    poll_interval_s: float


@dataclass(frozen=True)
class _Received:
    message: JsonObject
    at: float


@dataclass(frozen=True)
class _StreamEnded:
    pass


@dataclass(frozen=True)
class _StreamFault:
    pass


_Inbound: TypeAlias = _Received | _StreamEnded | _StreamFault


class _RunEnded(Exception):
    def __init__(self, outcome: Outcome) -> None:
        super().__init__(outcome)
        self.outcome: Outcome = outcome


def _read_frames(stream: IO[bytes], inbound: queue.Queue[_Inbound]) -> None:
    decoder = FrameDecoder()
    ending: _Inbound = _StreamEnded()
    try:
        while chunk := stream.read(65536):
            for message in decoder.feed(chunk):
                inbound.put(_Received(message, time.monotonic()))
    except FrameError:
        ending = _StreamFault()
    except (OSError, ValueError):
        pass
    finally:
        stream.close()
    inbound.put(ending)


def _discard(stream: IO[bytes]) -> None:
    try:
        while stream.read(65536):
            pass
    except (OSError, ValueError):
        pass
    finally:
        stream.close()


def _elapsed_ms(start: float, end: float) -> int:
    return int((end - start) * 1000)


def _is_int(value: JsonValue) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


@final
class _Session:
    """One server process: sends requests, answers the server's requests, and tracks status notifications."""

    def __init__(self, stdin: IO[bytes], config: RunConfig, started: float) -> None:
        self._stdin = stdin
        self._config = config
        self._started = started
        self._inbound: queue.Queue[_Inbound] = queue.Queue()
        self._responses: dict[int, _Received] = {}
        self._next_id = 0
        self._quiescent_statuses = 0
        self.status_files_total: int | None = None
        self.last_stats: JsonObject | None = None

    def listen(self, stdout: IO[bytes], stderr: IO[bytes]) -> None:
        threading.Thread(
            target=_read_frames, args=(stdout, self._inbound), daemon=True
        ).start()
        threading.Thread(target=_discard, args=(stderr,), daemon=True).start()

    @property
    def quiescent_observed(self) -> bool:
        return self._quiescent_statuses > 0

    def send(self, message: JsonObject) -> None:
        try:
            _ = self._stdin.write(encode_frame({"jsonrpc": "2.0", **message}))
            self._stdin.flush()
        except (OSError, ValueError) as error:
            raise _RunEnded("server-exited") from error

    def request(self, method: str, params: JsonValue = None) -> int:
        self._next_id += 1
        message: JsonObject = {"id": self._next_id, "method": method}
        if params is not None:
            message["params"] = params
        self.send(message)
        return self._next_id

    def notify(self, method: str, params: JsonValue = None) -> None:
        message: JsonObject = {"method": method}
        if params is not None:
            message["params"] = params
        self.send(message)

    def next_event(self, until: float) -> bool:
        """Handle one inbound event; False when ``until`` (a monotonic time) passed first."""
        try:
            event = self._inbound.get(timeout=max(0.0, until - time.monotonic()))
        except queue.Empty:
            return False
        if isinstance(event, _StreamEnded):
            raise _RunEnded("server-exited")
        if isinstance(event, _StreamFault):
            raise _RunEnded("protocol-error")
        self._handle(event)
        return True

    def await_response(self, request_id: int, until: float) -> _Received:
        while request_id not in self._responses:
            if not self.next_event(until):
                raise _RunEnded("timeout")
        return self._responses.pop(request_id)

    def take_response(self, request_id: int) -> _Received | None:
        return self._responses.pop(request_id, None)

    def _handle(self, event: _Received) -> None:
        message = event.message
        message_id = message.get("id")
        method = message.get("method")
        if isinstance(method, str) and message_id is not None:
            if not (_is_int(message_id) or isinstance(message_id, str)):
                raise _RunEnded("protocol-error")
            self.send(
                {
                    "id": message_id,
                    "result": self._answer(method, message.get("params")),
                }
            )
        elif isinstance(method, str):
            if method == "groovy/status":
                self._record_status(message.get("params"))
        elif isinstance(message_id, int) and not isinstance(message_id, bool):
            self._responses[message_id] = event

    @staticmethod
    def _answer(method: str, params: JsonValue) -> JsonValue:
        if method != "workspace/configuration":
            return None
        items = params.get("items") if isinstance(params, dict) else None
        answers: list[JsonValue] = (
            [None] * len(items) if isinstance(items, list) else []
        )
        return answers

    def _record_status(self, params: JsonValue) -> None:
        if not isinstance(params, dict):
            return
        if params.get("quiescent") is True:
            self._quiescent_statuses += 1
        files_total = params.get("filesTotal")
        if isinstance(files_total, int) and not isinstance(files_total, bool):
            self.status_files_total = files_total

    def initialize(self, deadline: float) -> int:
        folder = self._config.workspace.resolve().as_uri()
        request_id = self.request(
            "initialize",
            {
                "processId": os.getpid(),
                "clientInfo": {"name": "gvy-startup-stats"},
                "capabilities": {"workspace": {"configuration": True}},
                "workspaceFolders": [{"uri": folder, "name": "workspace"}],
            },
        )
        response = self.await_response(request_id, deadline)
        if "error" in response.message or not isinstance(
            response.message.get("result"), dict
        ):
            raise _RunEnded("protocol-error")
        self.notify("initialized", {})
        return _elapsed_ms(self._started, response.at)

    def wait_settled(self, deadline: float) -> tuple[JsonObject, int]:
        """Poll ``groovy/serverStats`` until settled; return the snapshot and its arrival offset in ms."""
        poll_s = self._config.poll_interval_s
        next_poll = time.monotonic()
        in_flight: int | None = None
        wanted = True
        while True:
            now = time.monotonic()
            if now >= deadline:
                raise _RunEnded("timeout")
            if now >= next_poll:
                wanted = True
                next_poll = now + poll_s
            if wanted and in_flight is None:
                in_flight, wanted = self.request("groovy/serverStats"), False
            quiescent_before = self._quiescent_statuses
            _ = self.next_event(min(next_poll, deadline))
            wanted = wanted or self._quiescent_statuses > quiescent_before
            response = self.take_response(in_flight) if in_flight is not None else None
            if response is None:
                continue
            in_flight = None
            stats = self._stats_from(response)
            self.last_stats = stats
            if is_settled(stats):
                return stats, _elapsed_ms(self._started, response.at)

    @staticmethod
    def _stats_from(response: _Received) -> JsonObject:
        error = response.message.get("error")
        if isinstance(error, dict):
            raise _RunEnded(
                "stats-unavailable"
                if error.get("code") == METHOD_NOT_FOUND
                else "protocol-error"
            )
        try:
            return validate_server_stats(response.message.get("result"))
        except ProtocolError as protocol_error:
            raise _RunEnded("protocol-error") from protocol_error

    def runtime_facts(self) -> JsonObject | None:
        until = time.monotonic() + RUNTIME_FACTS_WAIT_S
        try:
            response = self.await_response(self.request("groovy/runtimeFacts"), until)
        except _RunEnded:
            return None
        return runtime_identity(response.message.get("result"))

    def request_shutdown(self) -> bool:
        """Send shutdown and exit; True when the server answered shutdown."""
        until = time.monotonic() + EXIT_GRACE_S
        try:
            _ = self.await_response(self.request("shutdown"), until)
            self.notify("exit")
        except _RunEnded:
            return False
        return True


def _stop(process: subprocess.Popen[bytes], asked_to_exit: bool) -> Shutdown:
    """Wait for our own child to exit; if it does not, escalate TERM then KILL on that child alone."""
    try:
        _ = process.wait(timeout=EXIT_GRACE_S if asked_to_exit else 1.0)
        return "clean" if asked_to_exit else "exited"
    except subprocess.TimeoutExpired:
        pass
    process.terminate()
    try:
        _ = process.wait(timeout=TERMINATE_GRACE_S)
        return "terminated"
    except subprocess.TimeoutExpired:
        process.kill()
        _ = process.wait()
        return "killed"


def run_once(config: RunConfig) -> RunResult:
    """Start the server once, measure until settled, timeout or failure, and always reap the child."""
    started = time.monotonic()
    process = subprocess.Popen(
        config.command,
        cwd=config.workspace,
        env=dict(config.env),
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=0,
    )
    assert (
        process.stdin is not None
        and process.stdout is not None
        and process.stderr is not None
    )
    session = _Session(process.stdin, config, started)
    session.listen(process.stdout, process.stderr)
    outcome: Outcome = "settled"
    initialize_ms: int | None = None
    settled_ms: int | None = None
    stats: JsonObject | None = None
    runtime: JsonObject | None = None
    asked_to_exit = False
    try:
        deadline = started + config.timeout_s
        try:
            initialize_ms = session.initialize(deadline)
            stats, settled_ms = session.wait_settled(deadline)
        except _RunEnded as ended:
            outcome = ended.outcome
            stats = session.last_stats if outcome == "timeout" else None
        if outcome in ("settled", "timeout"):
            runtime = session.runtime_facts()
            asked_to_exit = session.request_shutdown()
    finally:
        shutdown = _stop(process, asked_to_exit)
        process.stdin.close()
    return RunResult(
        index=config.index,
        outcome=outcome,
        spawn_to_initialize_response_ms=initialize_ms,
        spawn_to_settled_ms=settled_ms,
        quiescent_observed=session.quiescent_observed,
        status_files_total=session.status_files_total,
        server_stats=stats,
        runtime=runtime,
        shutdown=shutdown,
    )


# --- IO: command line ------------------------------------------------------------------------------------------------


def java_command(jar: Path) -> tuple[str, ...]:
    return ("java", *JVM_FLAGS, "-jar", str(jar))


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


def _host_os() -> str:
    name = platform.system().lower()
    return (
        "macos"
        if name == "darwin"
        else name
        if STRING_PATTERN.fullmatch(name)
        else "unknown"
    )


def _host_arch() -> str:
    machine = platform.machine().lower()
    mapped = {
        "x86_64": "x64",
        "amd64": "x64",
        "aarch64": "arm64",
        "arm64": "arm64",
    }.get(machine, machine)
    return mapped if STRING_PATTERN.fullmatch(mapped) else "unknown"


def _child_environment() -> tuple[dict[str, str], dict[str, JsonValue]]:
    removed: dict[str, JsonValue] = {
        key: key in os.environ for key in REMOVED_ENVIRONMENT
    }
    return {
        k: v for k, v in os.environ.items() if k not in REMOVED_ENVIRONMENT
    }, removed


@final
class Arguments(argparse.Namespace):
    """The parsed command line; ``argparse`` fills these typed attributes."""

    def __init__(self) -> None:
        super().__init__()
        self.jar = Path()
        self.runs = DEFAULT_RUNS
        self.label = ""
        self.output = Path()
        self.timeout_seconds = DEFAULT_TIMEOUT_S
        self.poll_interval_ms = DEFAULT_POLL_INTERVAL_MS
        self.workspace = Path()


def _build_report(
    args: Arguments, runs: Sequence[RunResult], removed: JsonObject
) -> JsonObject:
    first = next((run for run in runs if run.server_stats is not None), None)
    build = (
        first.server_stats.get("build")
        if first is not None and first.server_stats is not None
        else None
    )
    runtime = (
        first.runtime
        if first is not None and first.runtime is not None
        else runtime_identity(None)
    )
    run_entries: list[JsonValue] = [run.as_json() for run in runs]
    jvm_args: list[JsonValue] = list(JVM_FLAGS)
    return {
        "schema": SCHEMA,
        "schemaVersion": SCHEMA_VERSION,
        "recordedAt": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "toolSha256": _sha256(Path(__file__)),
        "workspaceLabel": args.label,
        "identity": {
            "artifactSha256": _sha256(args.jar),
            "build": build,
            **runtime,
            "os": _host_os(),
            "arch": _host_arch(),
            "cpuCount": os.cpu_count(),
            "jvmArgs": jvm_args,
            "removedEnvironment": removed,
        },
        "plannedRuns": args.runs,
        "timeoutMs": int(args.timeout_seconds * 1000),
        "pollIntervalMs": args.poll_interval_ms,
        "runs": run_entries,
        "summary": summarize(runs),
    }


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="startup_stats.py",
        description="Measure server startup phases over repeated fresh runs. Exit 1 if any run did not settle.",
    )
    _ = parser.add_argument(
        "--jar", type=Path, required=True, help="server jar (gls-*-all.jar)"
    )
    _ = parser.add_argument(
        "--runs",
        type=int,
        default=DEFAULT_RUNS,
        help="fresh server runs (default %(default)s)",
    )
    _ = parser.add_argument(
        "--label",
        required=True,
        help=f"workspace label matching {LABEL_PATTERN.pattern}",
    )
    _ = parser.add_argument(
        "--output", type=Path, required=True, help="JSON file to create; must not exist"
    )
    _ = parser.add_argument(
        "--timeout-seconds", type=float, default=DEFAULT_TIMEOUT_S, help="per-run limit"
    )
    _ = parser.add_argument(
        "--poll-interval-ms", type=int, default=DEFAULT_POLL_INTERVAL_MS
    )
    _ = parser.add_argument("workspace", type=Path, help="workspace directory to open")
    return parser


def _validated_arguments(
    parser: argparse.ArgumentParser, argv: Sequence[str]
) -> Arguments:
    args = parser.parse_args(argv, namespace=Arguments())
    if LABEL_PATTERN.fullmatch(args.label) is None:
        parser.error(f"--label must match {LABEL_PATTERN.pattern}")
    if not 1 <= args.runs <= MAX_RUNS:
        parser.error(f"--runs must be between 1 and {MAX_RUNS}")
    if not 0 < args.timeout_seconds <= MAX_TIMEOUT_S or args.poll_interval_ms <= 0:
        parser.error("--timeout-seconds or --poll-interval-ms is out of range")
    if not args.jar.is_file():
        parser.error("--jar is not a file")
    if not args.workspace.is_dir():
        parser.error("workspace is not a directory")
    if args.output.exists() or not args.output.parent.is_dir():
        parser.error("--output must be a new file in an existing directory")
    return args


def main(argv: Sequence[str], launch: LaunchCommand = java_command) -> int:
    """Run the measurement. Exit 0 when every run settled, 1 when not or nothing was written, 2 for usage errors."""
    parser = _parser()
    try:
        args = _validated_arguments(parser, argv)
    except SystemExit as usage_exit:
        return usage_exit.code if isinstance(usage_exit.code, int) else 2
    env, removed = _child_environment()
    command = launch(args.jar)
    runs: list[RunResult] = []
    try:
        for index in range(args.runs):
            config = RunConfig(
                command,
                env,
                args.workspace,
                index,
                args.timeout_seconds,
                args.poll_interval_ms / 1000,
            )
            runs.append(run_once(config))
    except OSError as error:
        print(f"cannot start the server: {type(error).__name__}", file=sys.stderr)
        return 1
    if not runs_agree_on_identity(runs):
        print(
            "runs disagree on build or runtime identity; no output written",
            file=sys.stderr,
        )
        return 1
    try:
        with args.output.open("x", encoding="utf-8") as handle:
            json.dump(_build_report(args, runs, removed), handle, indent=2)
            _ = handle.write("\n")
    except OSError as error:
        print(f"cannot write the output: {type(error).__name__}", file=sys.stderr)
        return 1
    return 0 if all(run.outcome == "settled" for run in runs) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
