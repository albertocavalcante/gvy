import unittest
import json
import tempfile
import os
from issue_wizard import LabelRegistry, LabelCategory


class TestLabelRegistry(unittest.TestCase):
    def setUp(self):
        # Create a temporary labels JSON file
        self.test_labels = [
            {
                "name": "bug",
                "color": "d73a49",
                "description": "Something isn't working",
            },
            {
                "name": "lsp/completion",
                "color": "c2e0c6",
                "description": "Completion features",
            },
            {"name": "P1-must", "color": "d93f0b", "description": "Must fix"},
            {"name": "size/S", "color": "bfd4f2", "description": "Small"},
            {
                "name": "good-first-issue",
                "color": "7057ff",
                "description": "Good for newcomers",
            },
        ]
        self.tf = tempfile.NamedTemporaryFile(mode="w", delete=False)
        json.dump(self.test_labels, self.tf)
        self.tf.close()
        self.registry = LabelRegistry(self.tf.name)

    def tearDown(self):
        os.unlink(self.tf.name)

    def test_classification(self):
        # Test Type
        types = self.registry.get_by_category(LabelCategory.TYPE)
        self.assertEqual(len(types), 1)
        self.assertEqual(types[0].name, "bug")

        # Test Area
        areas = self.registry.get_by_category(LabelCategory.AREA)
        self.assertEqual(len(areas), 1)
        self.assertEqual(areas[0].name, "lsp/completion")

        # Test Priority
        priorities = self.registry.get_by_category(LabelCategory.PRIORITY)
        self.assertEqual(len(priorities), 1)
        self.assertEqual(priorities[0].name, "P1-must")

        # Test Size
        sizes = self.registry.get_by_category(LabelCategory.SIZE)
        self.assertEqual(len(sizes), 1)
        self.assertEqual(sizes[0].name, "size/S")

        # Test Flag
        flags = self.registry.get_by_category(LabelCategory.FLAG)
        self.assertEqual(len(flags), 1)
        self.assertEqual(flags[0].name, "good-first-issue")


if __name__ == "__main__":
    unittest.main()
