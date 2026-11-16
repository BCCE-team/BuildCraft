#!/usr/bin/env python3
"""Focused checks for Electronic Library input, item list highlight, and canvas sizing."""
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCES = [
    ROOT/'version-src/1.19.2-forge/src/main/java/buildcraft/builders/gui/GuiElectronicLibrary.java',
    ROOT/'version-src/1.20.1-forge/src/main/java/buildcraft/builders/gui/GuiElectronicLibrary.java',
    ROOT/'source-families/1.21.X/src/main/java/buildcraft/builders/gui/GuiElectronicLibrary.java',
    ROOT/'source-families/26.X/src/main/java/buildcraft/builders/gui/GuiElectronicLibrary.java'
]
HELPER = ROOT/'source-shared/src/main/java/buildcraft/builders/gui/ElectronicLibraryNameHighlight.java'

class ElectronicLibrarySearchQoL(unittest.TestCase):
    def test_canvas_size_does_not_crop_last_inventory_row(self):
        menu = (ROOT/'source-shared/src/main/java/buildcraft/builders/menu/ContainerElectronicLibrary.java').read_text()
        self.assertIn('addFullPlayerInventory(148)', menu)
        for p in SOURCES:
            with self.subTest(file=p):
                text = p.read_text()
                self.assertIn('SIZE_Y = 230;', text)
                self.assertIn('GUI_OLD_HEIGHT = 220;', text)
                self.assertIn('ICON_GUI_FOOTER.drawAt(', text)
                self.assertIn('new GuiIcon(TEXTURE_BASE, 0, GUI_OLD_HEIGHT, 176, SIZE_Y - GUI_OLD_HEIGHT)', text)
                self.assertLess(148 + 58 + 16, 230)

    def test_placeholder_is_one_pixel_higher_without_moving_typed_text(self):
        for p in SOURCES:
            with self.subTest(file=p):
                text = p.read_text()
                self.assertIn('new GuiRectangle(7, 15, 155, 14)', text)
                self.assertIn('(int) RECT_SEARCH.y + 3,', text)
                self.assertIn('x + 5, y + 4, 0xFF_C8_C8_C8', text)
                self.assertNotIn('x + 5, y + 5, 0xFF_C8_C8_C8', text)

    def test_search_captures_keyboard_and_escape_unfocuses(self):
        for p in SOURCES:
            with self.subTest(file=p):
                text = p.read_text()
                handler = text.split('public boolean keyPressed(int keyCode, int scanCode, int modifiers) {', 1)[1].split(
                    '    @Override\n    public boolean charTyped', 1)[0]
                escape_check = ('if (keyCode == InputConstants.KEY_ESCAPE)' if '/26.X/' in str(p)
                                else 'if (keyCode == 256)')
                self.assertIn(escape_check, handler)
                # Escape must be processed before ordinary typing and key bindings.
                self.assertLess(handler.index(escape_check), handler.index('searchField.keyPressed('))
                if '/26.X/' in str(p):
                    self.assertIn('import com.mojang.blaze3d.platform.InputConstants;', text)
                    self.assertNotIn('if (keyCode == 256)', handler)
                    self.assertIn('SDL input backend in 26.3', handler)
                self.assertIn('if (searchField != null && searchField.isFocused())', handler)
                if '1.19.2-forge' in str(p):
                    self.assertIn('searchField.mouseClicked(-1, -1, 0);', handler)
                    self.assertNotIn('searchField.setFocused(false);', handler)
                else:
                    self.assertIn('searchField.setFocused(false);', handler)
                self.assertIn('setFocused(null);', handler)
                self.assertIn('} else {\n                onClose();', handler)
                self.assertIn('return true;', handler)
                self.assertIn('searchField.keyPressed(keyCode, scanCode, modifiers);', handler)
                self.assertIn('searchField.charTyped(codePoint, modifiers);', text)
                self.assertIn('return super.keyPressed(keyCode, scanCode, modifiers);', handler)

    def test_modern_native_key_event_reaches_library_escape_handler(self):
        bridge = (ROOT/'source-families/26.X/src/main/java/buildcraft/lib/compat/minecraft/gui/BCContainerScreen.java').read_text()
        # 26.3 uses SDL-style event keys. They reach the legacy callback unchanged.
        self.assertIn('public boolean keyPressed(KeyEvent event)', bridge)
        self.assertIn('return keyPressed(event.key(), event.keycode(), event.modifiers()) || super.keyPressed(event);', bridge)
        library = SOURCES[3].read_text()
        self.assertIn('keyCode == InputConstants.KEY_ESCAPE', library)
        self.assertNotIn('keyCode == 256', library)

    def test_search_help_tab_covers_input_and_has_localized_text(self):
        lang_file = ROOT/'source-shared/src/main/resources/assets/buildcraft/lang/en_us.json'
        lang = json.loads(lang_file.read_text(encoding='utf-8'))
        self.assertEqual(lang['buildcraft.help.library.search.title'], 'Search library')
        help_text = lang['buildcraft.help.library.search.desc']
        for phrase in ('blueprint', 'template', 'yellow', 'Esc'):
            self.assertIn(phrase, help_text)
        for p in SOURCES:
            with self.subTest(file=p):
                source = p.read_text(encoding='utf-8')
                self.assertIn('GuiHelpUtil.addRoot(mainGui, (int) RECT_SEARCH.x, (int) RECT_SEARCH.y,', source)
                self.assertIn('(int) RECT_SEARCH.width, (int) RECT_SEARCH.height,', source)
                self.assertIn('"buildcraft.help.library.search.title", 0xFF_66_AA_FF,', source)
                self.assertIn('"buildcraft.help.library.search.desc"', source)
                self.assertIn('new GuiRectangle(7, 15, 155, 14)', source)

    def test_delete_button_label_size_and_help_highlight(self):
        lang = json.loads((ROOT/'source-shared/src/main/resources/assets/buildcraft/lang/en_us.json')
                          .read_text(encoding='utf-8'))
        self.assertEqual(lang['gui.buildcraftbuilders.library.delete'], 'Delete')
        self.assertEqual(lang['gui.del'], 'Del')  # Other machines remain unchanged.
        self.assertIn('blueprint or template', lang['buildcraft.help.library.delete.desc'])
        for p in SOURCES:
            with self.subTest(file=p):
                text = p.read_text(encoding='utf-8')
                self.assertIn('RECT_DELETE_BUTTON = new GuiRectangle(174, 119, 50, 15);', text)
                self.assertIn('mainGui.rootElement.offset(174, 119)', text)
                self.assertIn('StandardSpriteButtons.QUARTER_BUTTON_DRAWABLE', text)
                self.assertIn('delButton.createTextElement(Component.translatable("gui.buildcraftbuilders.library.delete"))', text)
                self.assertIn('GuiHelpUtil.addRoot(mainGui, (int) RECT_DELETE_BUTTON.x, (int) RECT_DELETE_BUTTON.y, (int) RECT_DELETE_BUTTON.width,', text)
                self.assertIn('(int) RECT_DELETE_BUTTON.height, "buildcraft.help.library.delete.title",', text)
                self.assertNotIn('StandardSpriteButtons.EIGHTH_BUTTON_DRAWABLE', text)

    def test_match_color_changes_only_letter_glyphs(self):
        for p in SOURCES:
            with self.subTest(file=p):
                text = p.read_text()
                self.assertIn('ElectronicLibraryNameHighlight.segments(visibleName, query)', text)
                self.assertIn('segment.highlighted() ? 0xFF_FF_D5_35 : baseColour', text)
                self.assertIn('offset += font.width(segment.text());', text)

    def test_java_case_insensitive_segments_are_correct(self):
        with tempfile.TemporaryDirectory(prefix='bc-elibrary-highlight-') as tmp:
            probe = Path(tmp)/'LibraryHighlightProbe.java'
            probe.write_text('''import java.util.*;
import buildcraft.builders.gui.ElectronicLibraryNameHighlight;
class LibraryHighlightProbe {
  static void check(String source, String query, List<String> expected) {
    var segments = ElectronicLibraryNameHighlight.segments(source, query);
    var got = segments.stream().map(s -> (s.highlighted()? "Y" : "N")+s.text()).toList();
    if (!got.equals(expected)) throw new AssertionError("Got: "+got+" expected: "+expected);
    if (!String.join("", segments.stream().map(ElectronicLibraryNameHighlight.Segment::text).toList()).equals(source))
      throw new AssertionError("Original name changed");
  }
  public static void main(String[] args) {
    check("Blueprint BP blueprint", "bLue", List.of("YBlue", "Nprint BP ", "Yblue", "Nprint"));
    check("Нефть Нефть", "НЕФТЬ", List.of("YНефть", "N ", "YНефть"));
    check("abc", "", List.of("Nabc"));
    check("abc", "   ", List.of("Nabc"));
    check("abc", "abcde", List.of("Nabc"));
    check("  Factory  ", " factory ", List.of("N  ", "YFactory", "N  "));
    check("", "a", List.of());
    System.out.println("Library name highlight: PASS");
  }
}''')
            compiled=subprocess.run(['javac','-encoding','UTF-8','-d',tmp,str(HELPER),str(probe)],capture_output=True,text=True,timeout=25)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            executed=subprocess.run(['java','-cp',tmp,'LibraryHighlightProbe'],capture_output=True,text=True,timeout=15)
            self.assertEqual(executed.returncode,0,executed.stderr)
            self.assertIn('PASS',executed.stdout)


class ElectronicLibraryClickBlurRegression(unittest.TestCase):
    def test_click_outside_search_releases_focus_without_consuming_click(self):
        for p in SOURCES:
            with self.subTest(version=p):
                source = p.read_text(encoding='utf-8')
                # The whole search bar should stay an active click target.
                hitbox = source.split('private boolean insideSearch(double x, double y) {', 1)[1].split('\n    }', 1)[0]
                self.assertIn('mainGui.rootElement.getX() + RECT_SEARCH.x', hitbox)
                self.assertIn('mainGui.rootElement.getY() + RECT_SEARCH.y', hitbox)
                self.assertIn('x >= left && x < left + RECT_SEARCH.width', hitbox)
                self.assertIn('y >= top && y < top + RECT_SEARCH.height', hitbox)

                mouse = source.split('public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {', 1)[1].split(
                    '    @Override\n    public boolean mouseDragged', 1)[0]
                blur_condition = 'searchField != null && searchField.isFocused() && !insideSearch(mouseX, mouseY)'
                self.assertIn(blur_condition, mouse)
                self.assertIn('setFocused(null);', mouse)
                if '1.19.2-forge' in str(p):
                    self.assertIn('searchField.mouseClicked(-1, -1, 0);', mouse)
                    self.assertNotIn('searchField.setFocused(false);', mouse)
                else:
                    self.assertIn('searchField.setFocused(false);', mouse)
                self.assertNotIn('searchField.setValue(', mouse)
                self.assertLess(mouse.index(blur_condition), mouse.index('if (mouseButton == 0 && insideList('))
                self.assertIn('return super.mouseClicked(mouseX, mouseY, mouseButton);', mouse)
                self.assertIn('container.sendSelectedToServer(key);', mouse)


if __name__ == '__main__':
    unittest.main(verbosity=2)
