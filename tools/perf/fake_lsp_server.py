# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0
"""Scripted stand-in for the language server, used by test_startup_stats.py.

Usage: ``python -I fake_lsp_server.py <scenario>``. Speaks LSP over stdio and mimics the parts of the server that the
startup measurement uses: ``groovy/serverStats``, ``groovy/runtimeFacts`` and ``groovy/status``. Scenarios:

- ``settle``: the first stats answer shows running phases; a quiescent status follows, then all phases are completed.
  It also echoes the workspace folder in a log message and on stderr, which the tool must never record.
- ``never``: phases stay running and no quiescent status is sent.
- ``exit-early``: exits after ``initialized``.
- ``method-not-found``: answers ``groovy/serverStats`` with MethodNotFound.
- ``invalid-stats``: answers with a build version that looks like a path.
- ``requests``: sends three server requests after ``initialized`` and settles only if each gets the right answer;
  a wrong answer exits with status 3.
- ``deep-frame``: sends a frame whose JSON nests 100,000 levels deep.
- ``deep-stats``: answers ``groovy/serverStats`` with a build value nested 200 levels deep.
- ``grandchild``: starts a process that inherits stdout and lingers for 4 s, then exits after ``initialized``.
- ``drifting-identity``: like ``settle``, but the reported maximum heap differs for every process.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from typing import IO, TypeAlias, TypeGuard, cast, final

JsonValue: TypeAlias = (
    "None | bool | int | float | str | list[JsonValue] | dict[str, JsonValue]"
)
JsonObject: TypeAlias = dict[str, JsonValue]

PHASES = (
    "lsp.initialize",
    "workspace.setup",
    "dependencies.resolve",
    "workspace.model.publish",
    "workspace.index",
    "workspace.compile",
)
BUILD: JsonObject = {
    "versionState": "known",
    "version": "0.0.0-test",
    "commitState": "unknown",
    "dirtyState": "unknown",
}
SERVER_REQUESTS: tuple[tuple[int, str, JsonValue, JsonValue], ...] = (
    (100, "workspace/configuration", {"items": [{}, {}]}, [None, None]),
    (101, "window/workDoneProgress/create", {"token": "t"}, None),
    (102, "client/registerCapability", {"registrations": []}, None),
)


def _is_list(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def _is_dict(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def json_value(raw: object) -> JsonValue:
    if raw is None or isinstance(raw, (bool, int, float, str)):
        return raw
    if _is_list(raw):
        return [json_value(item) for item in raw]
    if _is_dict(raw):
        return {str(key): json_value(item) for key, item in raw.items()}
    raise ValueError("not a JSON value")


def read_message(stream: IO[bytes]) -> JsonObject | None:
    length = 0
    while True:
        line = stream.readline()
        if not line:
            return None
        if line in (b"\r\n", b"\n"):
            break
        name, _, value = line.decode("ascii").partition(":")
        if name.lower() == "content-length":
            length = int(value)
    # The stdlib decoder is typed as Any; json_value validates the structure.
    body = json_value(cast(object, json.loads(stream.read(length))))
    return body if isinstance(body, dict) else None


def send(message: JsonObject) -> None:
    body = json.dumps({"jsonrpc": "2.0", **message}).encode("utf-8")
    _ = sys.stdout.buffer.write(
        b"Content-Length: " + str(len(body)).encode("ascii") + b"\r\n\r\n" + body
    )
    _ = sys.stdout.buffer.flush()


def stats(settled: bool) -> JsonObject:
    status = "completed" if settled else "running"
    phases: list[JsonValue] = []
    for index, name in enumerate(PHASES):
        phase: JsonObject = {"name": name, "status": status, "startOffsetMs": index}
        if settled:
            phase["durationMs"] = 10 + index
        phases.append(phase)
    return {
        "schemaVersion": 1,
        "build": BUILD,
        "startup": {"observedOffsetMs": 50, "phases": phases},
    }


def runtime_facts(scenario: str) -> JsonObject:
    heap = 2147483648 + (os.getpid() if scenario == "drifting-identity" else 0)
    return {
        "schemaVersion": 1,
        "runtime": {
            "processState": "known",
            "processId": os.getpid(),
            "javaState": "known",
            "javaFeature": 17,
            "javaVersion": "17.0.13",
            "heapState": "known",
            "maxHeapBytes": heap,
        },
        "build": BUILD,
        "hoverEngine": {"configured": "openrewrite", "state": "unknown"},
    }


def send_status() -> None:
    send(
        {
            "method": "groovy/status",
            "params": {"health": "ok", "quiescent": True, "filesTotal": 1},
        }
    )


@final
class Fake:
    def __init__(self, scenario: str) -> None:
        self.scenario = scenario
        self.folder = ""
        self.quiescent = False
        self.unanswered = {
            request_id: result for request_id, _, _, result in SERVER_REQUESTS
        }

    def on_initialize(self, message: JsonObject) -> None:
        params = message.get("params")
        folders = params.get("workspaceFolders") if isinstance(params, dict) else None
        first = folders[0] if isinstance(folders, list) and folders else None
        self.folder = str(first.get("uri")) if isinstance(first, dict) else ""
        send({"id": message.get("id"), "result": {"capabilities": {}}})

    def on_initialized(self) -> None:
        if self.scenario == "exit-early":
            sys.exit(0)
        if self.scenario == "grandchild":
            _ = subprocess.Popen(
                [sys.executable, "-I", "-c", "import time; time.sleep(4)"]
            )
            sys.exit(0)
        if self.scenario == "deep-frame":
            body = b'{"a":' + b"[" * 100000 + b"]" * 100000 + b"}"
            header = b"Content-Length: " + str(len(body)).encode("ascii") + b"\r\n\r\n"
            _ = sys.stdout.buffer.write(header + body)
            _ = sys.stdout.buffer.flush()
        if self.scenario in ("settle", "drifting-identity"):
            _ = sys.stderr.write(f"opened {self.folder}\n")
            _ = sys.stderr.flush()
            send(
                {
                    "method": "window/logMessage",
                    "params": {"type": 3, "message": f"root {self.folder}"},
                }
            )
        if self.scenario == "requests":
            for request_id, method, params, _ in SERVER_REQUESTS:
                send({"id": request_id, "method": method, "params": params})

    def on_response(self, message: JsonObject) -> None:
        request_id = message.get("id")
        if not isinstance(request_id, int) or request_id not in self.unanswered:
            return
        if message.get("result") != self.unanswered.pop(request_id):
            sys.exit(3)
        if not self.unanswered:
            self.quiescent = True
            send_status()

    def on_stats(self, message: JsonObject) -> None:
        request_id = message.get("id")
        if self.scenario == "method-not-found":
            send(
                {
                    "id": request_id,
                    "error": {"code": -32601, "message": "Method not found"},
                }
            )
        elif self.scenario == "deep-stats":
            nested: JsonValue = "x"
            for _ in range(200):
                nested = [nested]
            deep = stats(True)
            deep["build"] = {"deep": nested}
            send({"id": request_id, "result": deep})
        elif self.scenario == "invalid-stats":
            invalid = stats(True)
            invalid["build"] = {"version": "/private/secret-project"}
            send({"id": request_id, "result": invalid})
        else:
            was_quiescent = self.quiescent
            send({"id": request_id, "result": stats(was_quiescent)})
            if self.scenario in ("settle", "drifting-identity") and not was_quiescent:
                self.quiescent = True
                send_status()

    def handle(self, message: JsonObject) -> bool:
        """Process one message; False after ``exit``."""
        method = message.get("method")
        if method is None:
            self.on_response(message)
        elif method == "initialize":
            self.on_initialize(message)
        elif method == "initialized":
            self.on_initialized()
        elif method == "groovy/serverStats":
            self.on_stats(message)
        elif method == "groovy/runtimeFacts":
            send({"id": message.get("id"), "result": runtime_facts(self.scenario)})
        elif method == "shutdown":
            send({"id": message.get("id"), "result": None})
        elif method == "exit":
            return False
        return True


def main(argv: list[str]) -> int:
    fake = Fake(argv[1])
    while (message := read_message(sys.stdin.buffer)) is not None:
        if not fake.handle(message):
            break
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
