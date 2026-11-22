#!/usr/bin/env python3
"""Ensure 1.21.11 GUI input events keep both constructor parameters after materialization."""
from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))

from source_config import load_properties, target_layout
from source_layout import _materialize_text_file, resolve_effective_source

GUI = 'src/main/java/buildcraft/builders/gui/GuiElectronicLibrary.java'


class ElectronicLibraryCharacterEventCompatibilityTests(unittest.TestCase):
    def materialize(self, target: str) -> str:
        properties = load_properties()
        layout = target_layout(target, properties)
        source = resolve_effective_source(layout, properties, GUI)
        self.assertIsNotNone(source)
        with tempfile.TemporaryDirectory(prefix='bcce-char-event-') as directory:
            destination = Path(directory) / GUI
            _materialize_text_file(
                source, destination, logical_relative=GUI,
                minecraft=properties[f'target.{target}.deps.minecraft'],
                family=layout.family, platform=layout.platform, preprocess=True,
            )
            return destination.read_text(encoding='utf-8')

    def test_12111_character_event_retains_codepoint_and_modifiers(self):
        source = self.materialize('1.21.11-neoforge')
        self.assertIn(
            'searchField.charTyped(new net.minecraft.client.input.CharacterEvent(codePoint, modifiers))',
            source,
        )
        self.assertNotIn('new net.minecraft.client.input.CharacterEvent(codePoint)', source)
        # This screen uses the legacy GuiBC8 bridge for super input handling.
        self.assertIn('super.charTyped(codePoint, modifiers)', source)

    def test_1211_keeps_legacy_widget_input(self):
        source = self.materialize('1.21.1-neoforge')
        self.assertIn('searchField.charTyped(codePoint, modifiers)', source)
        self.assertNotIn('new net.minecraft.client.input.CharacterEvent(', source)

    def test_26x_character_event_uses_one_argument_on_both_loaders(self):
        for target in ('26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge', '26.3-fabric'):
            with self.subTest(target=target):
                source = self.materialize(target)
                self.assertIn(
                    'searchField.charTyped(new net.minecraft.client.input.CharacterEvent(codePoint))',
                    source,
                )
                self.assertNotIn(
                    'new net.minecraft.client.input.CharacterEvent(codePoint, modifiers)', source,
                )
                self.assertIn('super.charTyped(codePoint, modifiers)', source)

    def test_26x_non_bridge_super_call_uses_single_argument(self):
        from transforms.java_compat import upgrade_symbols
        input_java = ("package sample; class NonBridge { "
                      "boolean charTyped(char codePoint, int modifiers) { "
                      "return super.charTyped(codePoint, modifiers); } }")
        converted = upgrade_symbols(input_java, minecraft='26.3', relative='src/main/java/sample/NonBridge.java')
        self.assertIn(
            'super.charTyped(new net.minecraft.client.input.CharacterEvent(codePoint))', converted,
        )

    def test_26x_logging_screen_uses_version_appropriate_navigation(self):
        screen_path = 'src/main/java/buildcraft/lib/client/config/LoggingSettingsScreen.java'
        for target in ('26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge', '26.3-fabric'):
            with self.subTest(target=target):
                properties = load_properties()
                layout = target_layout(target, properties)
                source = resolve_effective_source(layout, properties, screen_path)
                self.assertIsNotNone(source)
                with tempfile.TemporaryDirectory(prefix='bcce-screen-nav-') as directory:
                    dest = Path(directory) / screen_path
                    _materialize_text_file(
                        source, dest, logical_relative=screen_path,
                        minecraft=properties[f'target.{target}.deps.minecraft'],
                        family=layout.family, platform=layout.platform, preprocess=True,
                    )
                    materialized = dest.read_text(encoding='utf-8')
                expected = 'Minecraft.getInstance().gui.setScreen(parent)' if target != '26.1.2-neoforge' else 'Minecraft.getInstance().setScreen(parent)'
                self.assertIn(expected, materialized)
                unexpected = 'Minecraft.getInstance().setScreen(parent)' if target != '26.1.2-neoforge' else 'Minecraft.getInstance().gui.setScreen(parent)'
                self.assertNotIn(unexpected, materialized)
                self.assertNotIn('UpdateScreenCompat', materialized)

    def test_12111_non_bridge_super_call_keeps_modifier_argument(self):
        from transforms.java_compat import upgrade_symbols
        input_java = ("package sample; class NonBridge { "
                      "boolean charTyped(char codePoint, int modifiers) { "
                      "return super.charTyped(codePoint, modifiers); } }")
        converted = upgrade_symbols(input_java, minecraft='1.21.11', relative='src/main/java/sample/NonBridge.java')
        self.assertIn(
            'super.charTyped(new net.minecraft.client.input.CharacterEvent(codePoint, modifiers))',
            converted,
        )


if __name__ == '__main__':
    unittest.main()
