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

    def test_configuration_screens_use_version_appropriate_navigation_and_localization(self):
        from source_lookup import read_materialized_java
        base = 'src/main/java/buildcraft/lib/'
        targets = ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge', '1.21.11-neoforge',
                   '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge', '26.3-fabric')
        for target in targets:
            with self.subTest(target=target):
                modern = target.startswith(('26.2-', '26.3-'))
                navigation = read_materialized_java(target, base + 'client/gui/ClientGuiModeNavigation.java')
                self.assertIn('return mc.gui.screen();' if modern else 'return mc.screen;', navigation)
                self.assertIn('mc.gui.setScreen(screen);' if modern else 'mc.setScreen(screen);', navigation)
                self.assertNotIn('return mc.screen;' if modern else 'return mc.gui.screen();', navigation)
                self.assertNotIn('mc.setScreen(screen);' if modern else 'mc.gui.setScreen(screen);', navigation)
                screen = read_materialized_java(target, base + 'client/config/BuildCraftConfigurationScreen.java')
                key = read_materialized_java(target, base + 'client/config/ConfigurationKey.java')
                legacy = read_materialized_java(target, base + 'client/config/LoggingSettingsScreen.java')
                locale = read_materialized_java(target, base + 'misc/LocaleUtil.java')
                self.assertIn('LocaleUtil.canLocalize(key)?I18n.get(key):fallback', screen)
                self.assertIn('Language.getInstance().has(key)' if modern else 'I18n.exists(key)', locale)
                self.assertNotIn('ConfirmScreen', screen)
                self.assertIn('showConfirmation(', screen)
                self.assertIn('closeConfirmation(', screen)
                self.assertIn('ClientGuiModeNavigation.show(parent)', screen)
                self.assertIn('ClientGuiModeNavigation.currentScreen()==null', key)
                self.assertIn('ClientGuiModeNavigation.show(new BuildCraftConfigurationScreen(null))', key)
                self.assertIn('ClientGuiModeNavigation.show(new BuildCraftConfigurationScreen(parent))', legacy)
                for code in (screen, key, legacy):
                    self.assertNotIn('.setScreen(', code)
                    self.assertNotIn('client.screen', code)
                    self.assertNotIn('I18n.exists(', code)

    def test_configuration_screen_renderer_for_every_target(self):
        from source_lookup import read_materialized_java
        logical = 'src/main/java/buildcraft/lib/client/config/BuildCraftConfigurationScreen.java'
        targets = ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge', '1.21.11-neoforge',
                   '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge', '26.3-fabric')
        for target in targets:
            with self.subTest(target=target):
                code = read_materialized_java(target, logical)
                if target.startswith('26.'):
                    self.assertIn('import net.minecraft.client.gui.GuiGraphicsExtractor;', code)
                    self.assertIn('void extractBackground(GuiGraphicsExtractor ', code)
                    self.assertIn('void extractRenderState(GuiGraphicsExtractor ', code)
                    self.assertIn('super.extractRenderState(g,mx,my,pt)', code)
                    self.assertIn('g.text(font,s,x,y,c,true)', code)
                    self.assertNotIn('import net.minecraft.client.gui.GuiGraphics;', code)
                    self.assertNotIn('void renderBackground(', code)
                    self.assertNotIn('void render(', code)
                    self.assertNotIn('g.drawString(', code)
                elif target.startswith('1.19.2-'):
                    self.assertIn('void renderBackground(PoseStack pose)', code)
                    self.assertIn('void render(PoseStack pose,', code)
                    self.assertNotIn('GuiGraphicsExtractor', code)
                    self.assertNotIn('import net.minecraft.client.gui.GuiGraphics;', code)
                else:
                    self.assertIn('import net.minecraft.client.gui.GuiGraphics;', code)
                    self.assertIn('void render(GuiGraphics g,', code)
                    self.assertIn('void renderBackground(GuiGraphics g)' if target.startswith('1.20.1-')
                                  else 'void renderBackground(GuiGraphics g,', code)
                    self.assertNotIn('GuiGraphicsExtractor', code)
                self.assertIn('panels(', code)
                self.assertIn('tooltip(', code)

    def test_configuration_tooltip_layers_for_every_target(self):
        from source_lookup import read_materialized_java
        targets = ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge', '1.21.11-neoforge',
                   '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge', '26.3-fabric')
        for target in targets:
            with self.subTest(target=target):
                code = read_materialized_java(target, 'src/main/java/buildcraft/lib/client/config/BuildCraftConfigurationScreen.java')
                tooltip = code.split('private void tooltip(', 1)[1].split('@Override', 1)[0]
                self.assertEqual(2, tooltip.count('nextLayer.run();'))
                self.assertLess(tooltip.index('nextLayer.run();'), tooltip.index('0xFF161616'))
                self.assertLess(tooltip.index('0xFF161616'), tooltip.rindex('nextLayer.run();'))
                self.assertLess(tooltip.rindex('nextLayer.run();'), tooltip.index('for(int j=0;j<wrapped.size();j++)'))
                self.assertNotIn('0xFA161616', tooltip)
                render = code[code.index('private void tooltip('):]
                if target.startswith(('1.21.11-', '26.')):
                    super_call = 'super.extractRenderState(g,mx,my,pt);' if target.startswith('26.') else 'super.render(g,mx,my,pt);'
                    self.assertLess(render.index(super_call), render.index('tooltip(g::fill,t,g::nextStratum,mx,my);'))
                    self.assertNotIn('translate(0,0,400)',render)
                else:
                    self.assertNotIn('nextStratum',render)
                    self.assertIn('translate(0,0,400)',render)
                    self.assertIn('finally {',render)
                    self.assertIn('popPose();',render)

    def test_configuration_screen_mouse_buttons_for_every_target(self):
        from source_lookup import read_materialized_java
        import re
        logical = 'src/main/java/buildcraft/lib/client/config/BuildCraftConfigurationScreen.java'
        targets = ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge', '1.21.11-neoforge',
                   '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge', '26.3-fabric')
        for target in targets:
            with self.subTest(target=target):
                code = read_materialized_java(target, logical)
                if target.startswith(('1.19.2-', '1.20.1-', '1.21.1-')):
                    self.assertNotIn('MouseButtonEvent', code)
                    self.assertIn('clickList(mx,my,button)', code)
                    self.assertIn('dragList(mx,my,button)', code)
                    self.assertIn('releaseList(button)', code)
                    continue
                body = re.search(r'private static int mouseButton\(MouseButtonEvent event\)\s*\{([^}]+)\}', code)
                self.assertIsNotNone(body)
                if target.startswith('26.3-'):
                    # Both 26.3 loaders use SDL (left = 1, right = 3, middle = 2).
                    self.assertIn('return buildcraft.lib.compat.minecraft.gui.BCGuiInput.legacyButton(event.button());', body.group(1))
                    adapter = read_materialized_java(target, 'src/main/java/buildcraft/lib/compat/minecraft/gui/BCGuiInput.java')
                    for name, legacy in (('LEFT', 0), ('RIGHT', 1), ('MIDDLE', 2)):
                        self.assertIn(f'case SDLMouse.SDL_BUTTON_{name} -> {legacy};', adapter)
                else:
                    self.assertIn('return event.button();', body.group(1))
                    self.assertNotIn('legacyButton', body.group(1))
                self.assertIn('clickList(event.x(),event.y(),mouseButton(event))', code)
                self.assertIn('dragList(event.x(),event.y(),mouseButton(event))', code)
                self.assertIn('releaseList(mouseButton(event))', code)
                # Vanilla widgets still receive their original native events.
                self.assertIn('super.mouseClicked(event,doubleClick)', code)
                self.assertIn('super.mouseDragged(event,dx,dy)', code)
                self.assertIn('super.mouseReleased(event)', code)

    def test_native_mouse_super_calls_do_not_revive_old_overloads(self):
        from transforms.java_compat import upgrade_symbols
        sample = """package sample;
class NativeScreen extends Screen {
    boolean mouseClicked(MouseButtonEvent e, boolean twice) { return super.mouseClicked(e, twice); }
    boolean mouseDragged(MouseButtonEvent e, double dx, double dy) { return super.mouseDragged(e, dx, dy); }
    boolean mouseReleased(MouseButtonEvent e) { return super.mouseReleased(e); }
    boolean oldClick(double x, double y, int button) { return super.mouseClicked(x, y, button); }
    boolean oldDrag(double x, double y, int button, double dx, double dy) { return super.mouseDragged(x, y, button, dx, dy); }
    boolean oldRelease(double x, double y, int button) { return super.mouseReleased(x, y, button); }
}
"""
        for version in ('1.21.11', '26.1.2', '26.2', '26.3'):
            with self.subTest(version=version):
                code = upgrade_symbols(sample, minecraft=version, relative='src/main/java/sample/NativeScreen.java')
                for native in ('super.mouseClicked(e, twice)', 'super.mouseDragged(e, dx, dy)', 'super.mouseReleased(e)'):
                    self.assertIn(native, code)
                self.assertEqual(3, code.count('return false;'))

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
