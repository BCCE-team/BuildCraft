"""Regression guard for Forge 1.19.2 EditBox focus access in Replacer.

In Minecraft 1.19.2, AbstractWidget.setFocused(boolean) is protected.
ScreenReplacer must only set focus through the screen's listener API.
"""
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_lookup import resolve_target_source, read_materialized_java

SOURCE = "src/main/java/buildcraft/builders/gui/ScreenReplacer.java"


class Forge1192ReplacerFocusCompatTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = resolve_target_source("1.19.2-forge", SOURCE)
        cls.code = read_materialized_java("1.19.2-forge", SOURCE)

    def test_target_selects_1192_overlay(self):
        self.assertIn("version-src/1.19.2-forge/", self.source.as_posix())

    def test_does_not_invoke_protected_widget_focus_mutator(self):
        self.assertNotIn("nameField.setFocused(", self.code)
        self.assertIn("nameField = new EditBox(", self.code)
        self.assertIn("addRenderableWidget(nameField);", self.code)

    def test_focus_restore_remains_screen_level(self):
        self.assertIn("boolean keepFocus = nameField != null && nameField.isFocused();", self.code)
        self.assertIn("if (keepFocus) setFocused(nameField);", self.code)
        self.assertIn("setFocused(null);", self.code)

    def test_name_editing_and_explicit_replace_remain_intact(self):
        self.assertIn("nameField.setValue(existingName);", self.code)
        self.assertIn("menu.sendReplaceRequest(nameField.getValue())", self.code)
        self.assertIn("nameField.keyPressed(keyCode, scanCode, modifiers)", self.code)
        self.assertIn("nameField.canConsumeInput()", self.code)


if __name__ == "__main__":
    unittest.main()
