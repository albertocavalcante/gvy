# SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from issue_wizard import IssueWizard, LabelCategory, LabelRegistry


class TestLabelRegistry(unittest.TestCase):
    def test_classification(self) -> None:
        labels = [
            {"name": "bug", "color": "d73a49", "description": "Bug"},
            {"name": "lsp/completion", "color": "c2e0c6", "description": "Completion"},
            {"name": "P1-must", "color": "d93f0b", "description": "Must fix"},
            {"name": "size/S", "color": "bfd4f2", "description": "Small"},
            {"name": "good-first-issue", "color": "7057ff", "description": "Newcomers"},
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "labels.json"
            _ = path.write_text(json.dumps(labels), encoding="utf-8")
            registry = LabelRegistry(str(path))
        for category, expected in [
            (LabelCategory.TYPE, "bug"),
            (LabelCategory.AREA, "lsp/completion"),
            (LabelCategory.PRIORITY, "P1-must"),
            (LabelCategory.SIZE, "size/S"),
            (LabelCategory.FLAG, "good-first-issue"),
        ]:
            self.assertEqual(
                [label.name for label in registry.get_by_category(category)], [expected]
            )

    def test_rejects_malformed_registry_schema(self) -> None:
        malformed: list[object] = [
            {},
            ["viz"],
            [{"color": "abc", "description": "Visualizations"}],
            [{"name": "viz", "description": "Visualizations"}],
            [{"name": "viz", "color": "abc"}],
            [{"name": None, "color": "abc", "description": "Visualizations"}],
            [{"name": "viz", "color": 123, "description": "Visualizations"}],
            [{"name": "viz", "color": "abc", "description": False}],
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "labels.json"
            for data in malformed:
                with self.subTest(data=data):
                    _ = path.write_text(json.dumps(data), encoding="utf-8")
                    with (
                        contextlib.redirect_stdout(io.StringIO()),
                        self.assertRaises(SystemExit),
                    ):
                        _ = LabelRegistry(str(path))

    def test_ignores_additional_registry_metadata(self) -> None:
        labels = [
            {
                "name": "viz",
                "color": "abc",
                "description": "Visualizations",
                "extra": 42,
            }
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "labels.json"
            _ = path.write_text(json.dumps(labels), encoding="utf-8")
            registry = LabelRegistry(str(path))
        self.assertEqual(
            [label.name for label in registry.get_by_category(LabelCategory.AREA)],
            ["viz"],
        )

    def test_wizard_offers_viz_as_area_and_passes_it_to_gh(self) -> None:
        registry = LabelRegistry(str(Path(__file__).with_name("github-labels.json")))
        areas = [label.name for label in registry.get_by_category(LabelCategory.AREA)]
        self.assertIn("viz", areas)
        self.assertIn("lsp/completion", areas)
        self.assertNotIn(
            "viz",
            [label.name for label in registry.get_by_category(LabelCategory.FLAG)],
        )
        wizard = IssueWizard(registry)
        wizard.body_file = "test-body.md"
        output = io.StringIO()
        answers = ["Test issue", "1", str(areas.index("viz") + 1), "1", "1", "n", "y"]
        with (
            patch("builtins.input", side_effect=answers),
            patch("issue_wizard.subprocess.run") as run,
            patch("issue_wizard.os.unlink"),
            patch.object(IssueWizard, "_create_body"),
            contextlib.redirect_stdout(output),
        ):
            wizard.run()
        self.assertIn("Select Area", output.getvalue())
        self.assertNotIn("Select LSP Area", output.getvalue())
        expected_labels = [
            registry.get_by_category(LabelCategory.TYPE)[0].name,
            "viz",
            registry.get_by_category(LabelCategory.PRIORITY)[0].name,
            registry.get_by_category(LabelCategory.SIZE)[0].name,
        ]
        command = [
            "gh",
            "issue",
            "create",
            "--title",
            "Test issue",
            "--body-file",
            "test-body.md",
        ]
        for label in expected_labels:
            command.extend(["--label", label])
        run.assert_called_once_with(command, check=True)


if __name__ == "__main__":
    _ = unittest.main()
