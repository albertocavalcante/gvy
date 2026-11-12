# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0
"""Offline regression tests for PR message generation and editing."""

import re
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import click
import httpx
import pr
import typer
from typer.testing import CliRunner


class MessageTests(unittest.TestCase):
    def test_related_issues_are_strings_sorted_and_exclude_closing_issues(self) -> None:
        body, related = pr.generate_merge_body(
            {
                "title": "fix: example",
                "body": "See #12; relates to #2; see #12; towards #3",
                "closingIssuesReferences": [{"number": 3}],
            },
            42,
        )
        self.assertEqual(related, ["2", "12"])
        self.assertIn("Fixes #3", body)
        self.assertIn("Relates to #2", body)
        self.assertNotIn("Relates to #3", body)

    def test_malformed_body_does_not_break_message_generation(self) -> None:
        _, related = pr.generate_merge_body({"title": "fix: example", "body": 123}, 42)
        self.assertEqual(related, [])

    def test_cli_provider_success_and_failure_cleanup_temporary_prompt(self) -> None:
        captured: list[Path] = []

        def run(
            cmd: list[str], *, timeout: int, input_file: Path
        ) -> subprocess.CompletedProcess[str]:
            self.assertEqual(cmd[0], "gemini")
            self.assertGreater(timeout, 0)
            self.assertEqual(input_file.read_text(), "secret prompt")
            captured.append(input_file)
            return subprocess.CompletedProcess(
                cmd, 0, "TITLE: fix: example\nBODY: details", ""
            )

        with patch.object(pr, "run_with_timeout", side_effect=run):
            output, _, error = pr._call_ai_via_cli(
                "secret prompt", pr.AIProvider.GEMINI
            )
        self.assertIsNone(error)
        self.assertIn("TITLE:", output)
        self.assertFalse(captured[0].exists())
        with patch.object(
            pr, "run_with_timeout", side_effect=subprocess.TimeoutExpired("gemini", 1)
        ):
            _, _, error = pr._call_ai_via_cli("secret prompt", pr.AIProvider.GEMINI)
        self.assertIn("timed out", error or "")

    def test_subprocess_input_file_is_closed_on_success_and_timeout(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "prompt"
            path.write_text("payload", encoding="utf-8")
            with patch.object(
                pr.subprocess,
                "run",
                return_value=subprocess.CompletedProcess(["provider"], 0, "ok", ""),
            ) as run:
                result = pr.run_with_timeout(["provider"], input_file=path)
            self.assertEqual(result.stdout, "ok")
            self.assertTrue(run.call_args.kwargs["stdin"].closed)
            self.assertFalse(run.call_args.kwargs["check"])
            with (
                patch.object(
                    pr.subprocess,
                    "run",
                    side_effect=subprocess.TimeoutExpired("provider", 1),
                ) as failed,
                self.assertRaises(subprocess.TimeoutExpired),
            ):
                pr.run_with_timeout(["provider"], input_file=path)
            self.assertTrue(failed.call_args.kwargs["stdin"].closed)

    def test_ai_success_and_oom_retry_use_mocked_providers(self) -> None:
        with (
            patch.object(
                pr,
                "prepare_diff_for_ai",
                side_effect=[("full diff", "full"), ("stats", "stats_only")],
            ) as diff,
            patch.object(pr, "_build_ai_prompt", return_value="prompt"),
            patch.object(
                pr,
                "_call_ai_via_cli",
                side_effect=[
                    ("", "out of memory", "exit 1"),
                    ("TITLE: fix: retry\nBODY: details", "", None),
                ],
            ) as cli,
            patch.object(pr, "_call_gemini_via_api") as api,
        ):
            self.assertEqual(pr.generate_ai_message({}, 42), ("fix: retry", "details"))
        self.assertEqual(diff.call_count, 2)
        self.assertEqual(cli.call_count, 2)
        api.assert_not_called()

    def test_adc_uses_mocked_requests_transport_and_reports_auth_failure(self) -> None:
        import google.auth
        import google.auth.transport.requests

        with (
            patch.object(google.auth, "default") as default,
            patch.object(google.auth.transport.requests, "Request") as request,
        ):
            credentials = MagicMock()
            credentials.token = "mock-token"
            default.return_value = (credentials, None)
            self.assertEqual(pr._get_google_auth_token(), "mock-token")
            credentials.refresh.assert_called_once_with(request.return_value)
            default.side_effect = RuntimeError("auth unavailable")
            self.assertIsNone(pr._get_google_auth_token())

    def test_direct_api_handles_success_and_network_failure_without_network(
        self,
    ) -> None:
        response = httpx.Response(
            200,
            json={
                "candidates": [
                    {"content": {"parts": [{"text": "TITLE: fix: api\nBODY: details"}]}}
                ]
            },
            request=httpx.Request("POST", "https://example.invalid"),
        )
        with (
            patch.object(pr, "_get_google_auth_token", return_value="fake-token"),
            patch.object(pr.httpx, "Client") as client,
        ):
            client.return_value.__enter__.return_value.post.return_value = response
            self.assertEqual(pr._call_gemini_via_api("prompt"), ("fix: api", "details"))
            client.return_value.__enter__.return_value.post.side_effect = (
                httpx.ConnectError("offline")
            )
            self.assertEqual(pr._call_gemini_via_api("prompt"), ("", ""))

    def test_cli_exposes_related_issue_option(self) -> None:
        result = CliRunner().invoke(pr.app, ["merge", "--help"])
        self.assertEqual(result.exit_code, 0, result.output)
        plain_output = re.sub(r"\x1b\[[0-9;]*m", "", result.output)
        self.assertIn("--relates-to", plain_output)

    def test_cli_accepts_repeated_related_issues_without_merging(self) -> None:
        details = {
            "title": "fix: example",
            "body": "",
            "commits": [],
            "closingIssuesReferences": [],
        }
        with (
            patch.object(pr, "get_pr_details", return_value=details),
            patch.object(pr, "save_msg_version", return_value="v1") as save,
            patch.object(pr.subprocess, "run") as run,
        ):
            result = CliRunner().invoke(
                pr.app,
                [
                    "merge",
                    "42",
                    "--dry-run",
                    "--relates-to",
                    "17",
                    "--relates-to",
                    "18",
                ],
            )
        self.assertEqual(result.exit_code, 0, result.output)
        self.assertIn("Relates to #17", save.call_args.args[2])
        self.assertIn("Relates to #18", save.call_args.args[2])
        run.assert_not_called()

    def test_ai_failure_falls_back_without_calling_another_provider(self) -> None:
        with (
            patch.object(pr, "prepare_diff_for_ai", return_value=("diff", "full")),
            patch.object(pr, "_build_ai_prompt", return_value="prompt"),
            patch.object(
                pr, "_call_ai_via_cli", return_value=("", "failed", "exit 1")
            ) as cli,
            patch.object(pr, "_call_gemini_via_api") as api,
        ):
            self.assertEqual(
                pr.generate_ai_message({}, 42, auto_fallback=False), ("", "")
            )
        self.assertEqual(cli.call_count, 1)
        api.assert_not_called()


class EditorTests(unittest.TestCase):
    def setUp(self) -> None:
        self.pr_details = {
            "title": "fix: example",
            "body": "",
            "commits": [],
            "closingIssuesReferences": [],
        }

    def run_merge(self, editor_result: str | None) -> list[tuple[object, ...]]:
        with (
            patch.object(pr, "get_pr_details", return_value=self.pr_details),
            patch.object(pr, "save_msg_version", return_value="v1") as save,
            patch.object(click, "edit", return_value=editor_result) as editor,
            patch.object(pr.subprocess, "run") as run,
        ):
            pr.merge(
                42,
                None,
                True,
                None,
                False,
                "gemini",
                None,
                False,
                False,
                True,
                None,
                False,
            )
            run.assert_not_called()
            editor.assert_called_once()
            return [call.args for call in save.call_args_list]

    def test_editor_success_saves_changed_message(self) -> None:
        saves = self.run_merge("fix: revised (#42)\n\nnew body")
        self.assertEqual(len(saves), 2)
        self.assertEqual(saves[1][:3], (42, "fix: revised (#42)", "new body"))

    def test_cancelled_editor_keeps_original_message(self) -> None:
        saves = self.run_merge(None)
        self.assertEqual(len(saves), 1)

    def test_invalid_edited_title_cannot_be_used_for_merge(self) -> None:
        with self.assertRaises(typer.Exit):
            self.run_merge("not a conventional title\n\nbody")

    def test_editor_failure_is_reported_without_merging(self) -> None:
        with (
            patch.object(pr, "get_pr_details", return_value=self.pr_details),
            patch.object(pr, "save_msg_version", return_value="v1"),
            patch.object(
                click, "edit", side_effect=click.ClickException("editor failed")
            ),
            patch.object(pr.subprocess, "run") as run,
            self.assertRaises(typer.Exit),
        ):
            pr.merge(
                42,
                None,
                True,
                None,
                False,
                "gemini",
                None,
                False,
                False,
                True,
                None,
                False,
            )
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
