"""Targeted source-compatibility guards for the BCU-parity NeoForge Replacer UI.

These tests deliberately do not replace compileJava/GameTests: they guard the
render pipeline, request boundary and the BCU geometry across source families.
"""
from pathlib import Path
import json
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
CLIENT_121 = ROOT / "source-families/1.21.X/src/main/java/buildcraft/builders/gui"
CLIENT_26 = ROOT / "source-families/26.X/src/main/java/buildcraft/builders/gui"
DOWNPORT = ROOT / "source-downports/1.21.X/1.21.1/family/src/main/java/buildcraft/builders/gui/ScreenReplacer.java"
MENU = ROOT / "source-platforms/neoforge/src/main/java/buildcraft/builders/gui/MenuReplacer.java"
TILE = ROOT / "source-platforms/neoforge/src/main/java/buildcraft/builders/tile/TileReplacer.java"
LANG = ROOT / "source-shared/src/main/resources/assets/buildcraft/lang/en_us.json"


class ReplacerBcuUiTests(unittest.TestCase):
    def test_bcu_controls_on_all_modern_screens(self):
        for screen in (CLIENT_121 / "ScreenReplacer.java", CLIENT_26 / "ScreenReplacer.java", DOWNPORT):
            with self.subTest(screen=str(screen)):
                source = screen.read_text()
                self.assertIn("PREVIEW_W = 160, PREVIEW_H = 100", source)
                self.assertIn("leftPos + 30, topPos + 117, 138, 12", source)
                self.assertIn("leftPos + 80, topPos + 135, 60, 20", source)
                self.assertIn("menu.sendReplaceRequest(nameField.getValue())", source)
                self.assertIn("BlueprintRenderer.renderSnapshot", source)
                self.assertIn("summaryText()", source)
                self.assertNotIn("getPreviewStacks", source)

    def test_bcu_preview_is_non_destructive_and_remove_supported(self):
        for family in (CLIENT_121, CLIENT_26):
            with self.subTest(family=str(family)):
                preview = (family / "ReplacerGuiPreview.java").read_text()
                self.assertIn("Blueprint candidate = latest.copy()", preview)
                self.assertIn("candidate.replace(find, replacement)", preview)
                self.assertIn("new SchematicBlockAir()", preview)
                self.assertIn("latest.countMatchingSchematic(find)", preview)
                self.assertNotIn("persist(", preview)
                self.assertIn("isSameItemSameComponents", preview)

    def test_server_only_button_request_validated(self):
        menu = MENU.read_text()
        self.assertIn('NET_REPLACE = IDS.allocId("REPLACE")', menu)
        self.assertIn("side == BCNetworkSide.SERVER", menu)
        self.assertIn("sender.containerMenu == this", menu)
        self.assertIn("stillValid(sender)", menu)
        self.assertIn("!sender.isSpectator()", menu)
        self.assertIn("buffer.readUtf(MAX_BLUEPRINT_NAME_LENGTH)", menu)

    def test_replacer_no_longer_auto_consumes_schematics(self):
        tile = TILE.read_text()
        self.assertIn("public void doReplace(String requestedName)", tile)
        self.assertNotIn("public void update()", tile)
        self.assertNotIn("invSchematicFrom.extractItem", tile)
        self.assertNotIn("invSchematicTo.extractItem", tile)
        self.assertIn("SnapshotCreationPersistence.persist", tile)
        self.assertIn("header.allowCreative, header.canRotate, header.canExcavate", tile)

    def test_neoforge_slot_filters_compile_against_insertion_checker(self):
        # StackInsertionChecker accepts (slot, stack), so all three method
        # references must resolve to matching static methods on TileReplacer.
        tile = TILE.read_text()
        checker = (ROOT / "source-shared/src/main/java/buildcraft/lib/tile/item/StackInsertionChecker.java").read_text()
        self.assertIn("boolean canSet(int slot,", checker)
        self.assertIn("private static boolean isUsedBlueprint(int slot, @Nonnull ItemStack stack)", tile)
        self.assertIn("private static boolean isValidSingleSchematic(int slot, @Nonnull ItemStack stack)", tile)
        self.assertIn("ItemSnapshot.EnumItemSnapshotType.BLUEPRINT_USED", tile)
        self.assertIn("ItemSchematicSingle.isValidUsed(stack)", tile)
        self.assertEqual(1, tile.count("private static boolean isUsedBlueprint("))
        self.assertEqual(1, tile.count("private static boolean isValidSingleSchematic("))

    def test_1211_downport_uses_legacy_tooltip_path(self):
        # The 1.21.1 BuildCraftGui integrates tooltips inside drawElementForegrounds;
        # drawTooltips() only exists in the later 1.21.11 implementation.
        downport = DOWNPORT.read_text()
        legacy_gui = (ROOT / "source-downports/1.21.X/1.21.1/family/src/main/java/buildcraft/lib/gui/BuildCraftGui.java").read_text()
        modern_gui = (ROOT / "source-families/1.21.X/src/main/java/buildcraft/lib/gui/BuildCraftGui.java").read_text()
        self.assertIn("mainGui.drawElementForegrounds(", downport)
        self.assertNotIn("mainGui.drawTooltips(", downport)
        self.assertNotIn("public void drawTooltips(", legacy_gui)
        self.assertIn("GuiUtil.drawVerticallyAppending(mouse, getAllTooltips()", legacy_gui)
        self.assertIn("public void drawTooltips(", modern_gui)

    def test_localization_and_version_boundaries(self):
        lang = json.loads(LANG.read_text())
        for key in ("gui.buildcraftbuilders.replacer.replace", "gui.buildcraftbuilders.replacer.remove",
                    "gui.buildcraftbuilders.replacer.summary", "gui.buildcraftbuilders.replacer.summary_remove",
                    "buildcraft.help.replacer.name.title", "buildcraft.help.replacer.replace.desc1"):
            self.assertIn(key, lang)
        self.assertEqual("Remove", lang["gui.buildcraftbuilders.replacer.remove"])
        self.assertNotIn("ReplacerGuiPreview", (ROOT / "source-platforms/forge/src/main/java/buildcraft/builders/gui/MenuReplacer.java").read_text())


if __name__ == "__main__":
    unittest.main()
