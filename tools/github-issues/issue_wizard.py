#!/usr/bin/env python3
import json
import os
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from enum import Enum
from typing import TypeGuard, cast


def _is_list(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def _is_object(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def _validate_labels(data: object) -> list[dict[str, str]]:
    if not _is_list(data):
        raise ValueError("expected a list of labels")
    labels: list[dict[str, str]] = []
    for index, item in enumerate(data):
        if not _is_object(item):
            raise ValueError(f"label {index + 1} must be an object")
        fields: dict[str, str] = {}
        for key in ("name", "color", "description"):
            value = item.get(key)
            if not isinstance(value, str):
                raise TypeError(f"label {index + 1} must have a string {key}")
            fields[key] = value
        labels.append(fields)
    return labels


class LabelCategory(Enum):
    TYPE = "type"
    AREA = "area"
    PRIORITY = "priority"
    SIZE = "size"
    FLAG = "flag"


@dataclass
class Label:
    name: str
    color: str
    description: str
    category: LabelCategory


class LabelRegistry:
    def __init__(self, json_path: str) -> None:
        self.labels: list[Label] = []
        self._load_labels(json_path)

    def _load_labels(self, json_path: str) -> None:
        try:
            with open(json_path, "r") as f:
                # The stdlib JSON decoder is typed as Any; validate before using its result.
                data = cast(object, json.load(f))
                for item in _validate_labels(data):
                    self.labels.append(self._classify_label(item))
        except FileNotFoundError:
            print(f"Error: Labels file not found at {json_path}")
            sys.exit(1)
        except json.JSONDecodeError:
            print(f"Error: Invalid JSON in {json_path}")
            sys.exit(1)
        except (TypeError, ValueError) as exc:
            print(f"Error: Invalid labels in {json_path}: {exc}")
            sys.exit(1)

    def _classify_label(self, item: dict[str, str]) -> Label:
        name = item["name"]
        if name == "viz" or name.startswith("lsp/"):
            category = LabelCategory.AREA
        elif name.startswith("size/"):
            category = LabelCategory.SIZE
        elif name.startswith("P") and any(
            p in name for p in ["-critical", "-must", "-should", "-nice"]
        ):
            category = LabelCategory.PRIORITY
        elif name in [
            "bug",
            "enhancement",
            "documentation",
            "architecture",
            "tech-debt",
        ]:
            category = LabelCategory.TYPE
        else:
            category = LabelCategory.FLAG

        return Label(
            name=name,
            color=item["color"],
            description=item["description"],
            category=category,
        )

    def get_by_category(self, category: LabelCategory) -> list[Label]:
        return [label for label in self.labels if label.category == category]


class IssueWizard:
    def __init__(self, registry: LabelRegistry) -> None:
        self.registry: LabelRegistry = registry
        self.selected_labels: list[str] = []
        self.title: str = ""
        self.body_file: str = ""

    def run(self) -> None:
        print("\n🧙 Groovy LSP Issue Wizard 🧙\n")

        # 1. Title
        while not self.title:
            self.title = input("📝 Issue Title: ").strip()

        # 2. Type (Single Select)
        self._select_single("Type", LabelCategory.TYPE)

        # 3. Area (Multi Select)
        self._select_multiple("Area", LabelCategory.AREA)

        # 4. Priority (Single Select)
        self._select_single("Priority", LabelCategory.PRIORITY)

        # 5. Size (Single Select)
        self._select_single("Size", LabelCategory.SIZE)

        # 6. Flags (Optional Multi Select)
        if self._confirm("Add special flags (help-wanted, blocked, etc)?"):
            self._select_multiple("Flags", LabelCategory.FLAG)

        # 7. Body
        self._create_body()

        # 8. Review and Submit
        self._submit()

    def _select_single(self, name: str, category: LabelCategory) -> None:
        options = self.registry.get_by_category(category)
        print(f"\n📂 Select {name}:")
        for i, label in enumerate(options, 1):
            print(f"  {i}. {label.name} ({label.description})")

        while True:
            try:
                user_input = input(f"Choose (1-{len(options)}): ")
                choice = int(user_input)
                if 1 <= choice <= len(options):
                    selected = options[choice - 1]
                    self.selected_labels.append(selected.name)
                    print(f"✅ Selected: {selected.name}")
                    break
                else:
                    print(
                        f"❌ Invalid input. Please enter a number between 1 and {len(options)}."
                    )
            except ValueError:
                print("❌ Invalid input, please enter a number.")

    def _select_multiple(self, name: str, category: LabelCategory) -> None:
        options = self.registry.get_by_category(category)
        print(f"\n📂 Select {name} (comma-separated, e.g. '1,3'):")
        for i, label in enumerate(options, 1):
            print(f"  {i}. {label.name} ({label.description})")

        while True:
            try:
                choices = input("Choose (or Enter to skip): ").strip()
                if not choices:
                    break

                indices = [int(c.strip()) for c in choices.split(",")]
                valid = True
                temp_selected: list[str] = []

                for idx in indices:
                    if 1 <= idx <= len(options):
                        temp_selected.append(options[idx - 1].name)
                    else:
                        valid = False

                if valid:
                    self.selected_labels.extend(temp_selected)
                    print(f"✅ Selected: {', '.join(temp_selected)}")
                    break
                else:
                    print("❌ Invalid selection, try again.")
            except ValueError:
                print("❌ Invalid format, use numbers separated by commas.")

    def _confirm(self, question: str) -> bool:
        return input(f"\n❓ {question} (y/n): ").lower().startswith("y")

    def _create_body(self) -> None:
        print("\n📝 Opening editor for description...")
        with tempfile.NamedTemporaryFile(suffix=".md", delete=False, mode="w") as tf:
            _ = tf.write("## Summary\n\n## Details\n\n## Acceptance Criteria\n\n")
            self.body_file = tf.name

        editor = os.environ.get("EDITOR", "nano")
        try:
            _ = subprocess.run([editor, self.body_file], check=True)
        except FileNotFoundError:
            print(
                f"❌ Editor '{editor}' not found. Please set the EDITOR environment variable to a valid editor."
            )
            try:
                os.unlink(self.body_file)
            except OSError:
                pass
            sys.exit(1)
        except subprocess.CalledProcessError as e:
            print(
                f"❌ Editor '{editor}' exited with an error (exit code {e.returncode})."
            )
            try:
                os.unlink(self.body_file)
            except OSError:
                pass
            sys.exit(1)

    def _submit(self) -> None:
        print("\n📋 Issue Preview:")
        print(f"Title: {self.title}")
        print(f"Labels: {', '.join(self.selected_labels)}")
        print(f"Body: {self.body_file}")

        if self._confirm("Create this issue on GitHub?"):
            cmd = [
                "gh",
                "issue",
                "create",
                "--title",
                self.title,
                "--body-file",
                self.body_file,
            ]
            for label in self.selected_labels:
                cmd.extend(["--label", label])

            try:
                _ = subprocess.run(cmd, check=True)
                print("🚀 Issue created successfully!")
                try:
                    os.unlink(self.body_file)
                except OSError:
                    pass
            except subprocess.CalledProcessError:
                print("❌ Failed to create issue.")
        else:
            print("❌ Cancelled.")
            try:
                os.unlink(self.body_file)
            except OSError:
                pass


if __name__ == "__main__":
    # Determine path to github-labels.json relative to script location
    script_dir = os.path.dirname(os.path.abspath(__file__))
    labels_path = os.path.join(script_dir, "github-labels.json")

    registry = LabelRegistry(labels_path)
    wizard = IssueWizard(registry)
    wizard.run()
