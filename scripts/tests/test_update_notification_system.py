from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_lookup import read_materialized_java  # noqa: E402

TARGETS = (
    "1.19.2-forge",
    "1.20.1-forge",
    "1.21.1-neoforge",
    "1.21.11-neoforge",
    "26.1.2-neoforge",
    "26.2-neoforge",
    "26.3-neoforge",
)


class UpdateNotificationSystemTests(unittest.TestCase):
    def test_client_screen_and_lifecycle_are_present_for_every_target(self):
        for target in TARGETS:
            with self.subTest(target=target):
                screen = read_materialized_java(
                    target, "src/main/java/buildcraft/lib/update/client/UpdateWarningScreen.java"
                )
                events = read_materialized_java(target, "src/main/java/buildcraft/lib/BCLibEventDist.java")
                self.assertIn("gui.buildcraftlib.update.ignore", screen)
                self.assertIn("gui.buildcraftlib.update.continue", screen)
                self.assertIn("gui.buildcraftlib.update.settings", screen)
                settings = read_materialized_java(
                    target, "src/main/java/buildcraft/lib/update/client/UpdateSettingsScreen.java"
                )
                self.assertIn("UpdatePreferences.setEnabled", settings)
                self.assertIn("UpdateNotificationManager.checkNow()", settings)
                self.assertIn("clearIgnoredVersions", settings)
                self.assertIn("UpdateNotificationManager.tick()", events)

    def test_warning_screen_has_four_actions_in_a_two_by_two_grid(self):
        for target in TARGETS:
            with self.subTest(target=target):
                screen = read_materialized_java(
                    target, "src/main/java/buildcraft/lib/update/client/UpdateWarningScreen.java"
                )
                for key in ("open", "settings", "ignore", "continue"):
                    self.assertEqual(screen.count(f"gui.buildcraftlib.update.{key}"), 1)
                self.assertNotIn("horizontalActions", screen)
                self.assertIn("int rightX = leftX + leftWidth + gap;", screen)
                self.assertIn("top + 26", screen)

    def test_settings_screen_uses_its_own_vanilla_background_without_rendering_parent(self):
        for target in TARGETS:
            with self.subTest(target=target):
                screen = read_materialized_java(
                    target, "src/main/java/buildcraft/lib/update/client/UpdateSettingsScreen.java"
                )
                self.assertNotIn("parent.render(", screen)
                self.assertNotIn("parent.extractRenderState(", screen)
                self.assertNotIn("0x99000000", screen)
                self.assertNotIn("renderSettingsPanel(", screen)
                self.assertIn("gui.buildcraftlib.update.settings.description", screen)
                if target == "1.19.2-forge":
                    self.assertIn("renderBackground(pose);", screen)
                elif target == "1.20.1-forge":
                    self.assertIn("renderBackground(graphics);", screen)
                elif target == "1.21.1-neoforge":
                    self.assertIn("renderBackground(graphics, mouseX, mouseY, partialTick);", screen)
                elif target == "1.21.11-neoforge":
                    self.assertNotIn("renderBackground(graphics, mouseX, mouseY, partialTick);", screen)
                    self.assertIn("super.render(graphics, mouseX, mouseY, partialTick);", screen)
                else:
                    self.assertIn("super.extractBackground(graphics, mouseX, mouseY, partialTick);", screen)

    def test_warning_restores_its_vanilla_background_while_settings_stay_independent(self):
        for target in TARGETS:
            with self.subTest(target=target):
                warning = read_materialized_java(
                    target, "src/main/java/buildcraft/lib/update/client/UpdateWarningScreen.java"
                )
                settings = read_materialized_java(
                    target, "src/main/java/buildcraft/lib/update/client/UpdateSettingsScreen.java"
                )
                self.assertNotIn("0xFFB48A42", warning)
                self.assertNotIn("0xE01D2023", warning)
                self.assertNotIn("0x183C606B", warning)
                self.assertNotIn("renderPanel(", warning)
                if target == "1.19.2-forge":
                    self.assertIn("renderBackground(pose);", warning)
                elif target == "1.20.1-forge":
                    self.assertIn("renderBackground(graphics);", warning)
                elif target == "1.21.1-neoforge":
                    self.assertIn("renderBackground(graphics, mouseX, mouseY, partialTick);", warning)
                elif target == "1.21.11-neoforge":
                    self.assertNotIn("        renderBackground(graphics, mouseX, mouseY, partialTick);", warning)
                    self.assertIn("super.renderBackground(graphics, mouseX, mouseY, partialTick);", warning)
                else:
                    self.assertIn("super.extractRenderState(graphics, mouseX, mouseY, partialTick);", warning)
                self.assertNotIn("0xFFB48A42", settings)
                self.assertNotIn("0xE01D2023", settings)
                self.assertNotIn("0x183C606B", settings)
                self.assertNotIn("renderSettingsPanel(", settings)


    def test_screen_navigation_uses_the_api_for_each_minecraft_generation(self):
        for target in TARGETS:
            with self.subTest(target=target):
                compat = read_materialized_java(
                    target, "src/main/java/buildcraft/lib/update/client/UpdateScreenCompat.java"
                )
                manager = read_materialized_java(
                    target, "src/main/java/buildcraft/lib/update/client/UpdateNotificationManager.java"
                )
                self.assertIn("UpdateScreenCompat.currentScreen()", manager)
                self.assertIn("UpdateScreenCompat.setScreen(", manager)
                self.assertNotIn("minecraft.screen", manager)
                self.assertNotIn("minecraft.setScreen(", manager)
                if target in ("26.2-neoforge", "26.3-neoforge"):
                    self.assertIn("minecraft.gui.screen()", compat)
                    self.assertIn("minecraft.gui.setScreen(screen)", compat)
                    self.assertNotIn("return minecraft.screen;", compat)
                else:
                    self.assertIn("return minecraft.screen;", compat)
                    self.assertIn("minecraft.setScreen(screen)", compat)
                if target == "26.3-neoforge":
                    self.assertIn("Blaze3D.openUri(uri)", compat)
                elif target in ("1.21.11-neoforge", "26.1.2-neoforge", "26.2-neoforge"):
                    self.assertIn("net.minecraft.util.Util.getPlatform().openUri(uri)", compat)
                else:
                    self.assertIn("net.minecraft.Util.getPlatform().openUri(uri)", compat)

    def test_12111_custom_screen_text_uses_opaque_argb_colours(self):
        for filename in ("UpdateWarningScreen.java", "UpdateSettingsScreen.java"):
            with self.subTest(filename=filename):
                screen_12111 = read_materialized_java(
                    "1.21.11-neoforge", f"src/main/java/buildcraft/lib/update/client/{filename}"
                )
                self.assertIn("0xFF000000 | (rgb & 0x00FFFFFF)", screen_12111)
                self.assertIn("guiTextColor(0xFFFFFF)", screen_12111)
                screen_1211 = read_materialized_java(
                    "1.21.1-neoforge", f"src/main/java/buildcraft/lib/update/client/{filename}"
                )
                self.assertIn("return rgb;", screen_1211)
                self.assertNotIn("0xFF000000 |", screen_1211)

    def test_26x_screens_use_extracted_render_state_for_all_supported_targets(self):
        for target in ("26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge"):
            with self.subTest(target=target):
                for filename in ("UpdateWarningScreen.java", "UpdateSettingsScreen.java"):
                    screen = read_materialized_java(
                        target, f"src/main/java/buildcraft/lib/update/client/{filename}"
                    )
                    self.assertIn("extractRenderState(GuiGraphicsExtractor", screen)
                    self.assertIn("super.extractRenderState(graphics, mouseX, mouseY, partialTick);", screen)
                    self.assertNotIn("super.render(graphics, mouseX, mouseY, partialTick);", screen)

    def test_12111_does_not_request_the_screen_blur_twice(self):
        warning = read_materialized_java(
            "1.21.11-neoforge", "src/main/java/buildcraft/lib/update/client/UpdateWarningScreen.java"
        )
        settings = read_materialized_java(
            "1.21.11-neoforge", "src/main/java/buildcraft/lib/update/client/UpdateSettingsScreen.java"
        )
        self.assertIn("super.renderBackground(graphics, mouseX, mouseY, partialTick);", warning)
        self.assertNotIn("        renderBackground(graphics, mouseX, mouseY, partialTick);", warning)
        self.assertNotIn("        renderBackground(graphics, mouseX, mouseY, partialTick);", settings)

    def test_target_identity_is_generated_for_both_loader_families(self):
        forge = (ROOT / "build-logic/loaders/forge-target.gradle").read_text(encoding="utf-8")
        neoforge = (ROOT / "build-logic/loaders/neoforge-target.gradle").read_text(encoding="utf-8")
        for source in (forge, neoforge):
            self.assertIn("targetId: targetName", source)
            self.assertIn("modVersion: modVersion,", source)
            self.assertNotIn("modVersion: project.version.toString()", source)
            self.assertIn("public static final String MOD_VERSION", source)
            self.assertIn("public static final String TARGET_ID", source)

    def test_release_manifest_contains_only_version_and_published_targets(self):
        workflow = (ROOT / ".github/workflows/publish.yml").read_text(encoding="utf-8")
        self.assertIn('manifest = {"version": os.environ["RELEASE_TAG"], "targets": sorted(set(targets))}', workflow)
        self.assertIn("bcce-release-manifest.json", workflow)
        self.assertIn('"gh", "release", "view"', workflow)
        self.assertGreater(workflow.index("Generate and attach BCCE release manifest"),
                           workflow.index("Publish 26.3 NeoForge"))

    def test_checker_is_asynchronous_and_uses_etag_cache(self):
        checker = (ROOT / "source-shared/src/main/java/buildcraft/lib/update/UpdateChecker.java").read_text(encoding="utf-8")
        self.assertIn("CompletableFuture.supplyAsync", checker)
        self.assertIn('"If-None-Match"', checker)
        self.assertIn("statusCode() == 304", checker)
        self.assertIn("MAX_MANIFEST_BYTES", checker)
        self.assertIn("MANIFEST_PENDING", checker)
        self.assertIn("MANIFEST_GRACE_PERIOD_MILLIS", checker)
        self.assertIn("LEGACY_JAR", checker)
        self.assertIn('"Retry-After"', checker)
        self.assertIn("failureRetryAt", checker)
        manager = (ROOT / "source-shared/src/main/java/buildcraft/lib/update/client/UpdateNotificationManager.java").read_text(encoding="utf-8")
        self.assertIn("MANIFEST_PENDING_RECHECK_MILLIS", manager)
        self.assertIn("manualCheck", manager)
        self.assertIn("compareTo(BCVersion.parse(version)) >= 0", manager)


if __name__ == "__main__":
    unittest.main()
