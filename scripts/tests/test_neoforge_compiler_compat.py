#!/usr/bin/env python3
"""Keep Redstone Engine and Jade source overrides compilable on 1.21.1 and 1.21.11.

Checks materialized Java, not just the canonical source layer. These assertions
cover version selectors and the two Jade source implementations without loading
Minecraft/NeoForge dependencies.
"""

from __future__ import annotations

import sys
import tomllib
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from source_config import load_properties, target_layout
from source_layout import _materialize_text_file, resolve_effective_source


class NeoForgeCompilerCompatibility(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="bcce-neoforge-compiler-")
        cls.sources = {}
        props = load_properties()
        for target in ("1.21.1-neoforge", "1.21.11-neoforge", "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge"):
            layout = target_layout(target, props)
            downports = [root for root in (layout.family_downport_root, layout.family_platform_downport_root) if root]
            for name in ("core/blockEntity/TileEngineRedstone_BC8.java", "compat/jade/BuildCraftJadePlugin.java"):
                relative = "src/main/java/buildcraft/" + name
                source = resolve_effective_source(layout, props, relative)
                assert source is not None, f"No source found for {target}: {name}"
                output = Path(cls.tmp.name) / target / relative
                _materialize_text_file(
                    source, output, logical_relative=relative,
                    minecraft=props[f"target.{target}.deps.minecraft"],
                    family=layout.family, platform=layout.platform, preprocess=True,
                    native_source=any(source.is_relative_to(root) for root in downports),
                )
                cls.sources[target, name] = output.read_text(encoding="utf-8")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def source(self, target: str, path: str) -> str:
        return self.sources[target, path]

    def test_redstone_advancement_uses_1211_profile_api(self):
        name = "core/blockEntity/TileEngineRedstone_BC8.java"
        source = self.source("1.21.1-neoforge", name)
        self.assertNotIn("import buildcraft.lib.compat.GameProfileCompat;", source)
        self.assertIn("AdvancementUtil.unlockAdvancement(getOwner().getId(), ADVANCEMENT)", source)
        self.assertNotIn("GameProfileCompat.id(getOwner())", source)
        self.assertEqual(source.count("givenAdvancement = AdvancementUtil.unlockAdvancement("), 1)

    def test_redstone_advancement_uses_modern_profile_api(self):
        name = "core/blockEntity/TileEngineRedstone_BC8.java"
        source = self.source("1.21.11-neoforge", name)
        self.assertIn("import buildcraft.lib.compat.GameProfileCompat;", source)
        self.assertIn("AdvancementUtil.unlockAdvancement(GameProfileCompat.id(getOwner()), ADVANCEMENT)", source)
        self.assertNotIn("getOwner().getId()", source)
        self.assertEqual(source.count("givenAdvancement = AdvancementUtil.unlockAdvancement("), 1)

    def test_jade_fluid_names_use_vanilla_formatting_on_121(self):
        name = "compat/jade/BuildCraftJadePlugin.java"
        for target in ("1.21.1-neoforge", "1.21.11-neoforge"):
            with self.subTest(target=target):
                source = self.source(target, name)
                self.assertIn("import net.minecraft.ChatFormatting;", source)
                self.assertIn('"FluidNames")).withStyle(ChatFormatting.WHITE)', source)
                self.assertNotIn("BCTextFormat", source)

    def test_121_metadata_preprocesses_to_valid_toml(self):
        # The 1.21.1-only Create dependency block must not leave Java-style //
        # comments in TOML, which would crash NeoForge before mods initialize.
        metadata = ROOT / "source-platforms/neoforge/src/main/resources/META-INF/neoforge.mods.toml"
        props = load_properties()
        for target in ("1.21.1-neoforge", "1.21.11-neoforge", "26.1.2-neoforge",
                       "26.2-neoforge", "26.3-neoforge"):
            with self.subTest(target=target):
                layout = target_layout(target, props)
                materialized = Path(self.tmp.name) / target / "META-INF/neoforge.mods.toml"
                _materialize_text_file(
                    metadata, materialized,
                    logical_relative="src/main/resources/META-INF/neoforge.mods.toml",
                    minecraft=props[f"target.{target}.deps.minecraft"],
                    family=layout.family, platform=layout.platform, preprocess=True,
                )
                text = materialized.read_text(encoding="utf-8")
                parsed = tomllib.loads(text)
                self.assertIn("buildcraftrobotics", [m["modId"] for m in parsed["mods"]])
                create_deps = [d for d in parsed["dependencies"]["buildcraftcompat"]
                               if d["modId"] == "create"]
                self.assertEqual(len(create_deps), 1 if target.startswith("1.21.1-") else 0)
                self.assertEqual(bool(parsed.get("mixins")), not target.startswith("1.21.1-"))

    def test_moddev_gametest_metadata_is_staged_and_cleaned(self):
        # FML 10+ discovers the dev mod from classes/java/main first. Only
        # GameTests mirror the processed mod metadata into that directory;
        # normal production JAR builds must not receive another metadata copy.
        gradle = (ROOT / 'build-logic/loaders/neoforge-target.gradle').read_text(encoding='utf-8')
        for snippet in (
            "if (gameTestRunRequested && minecraftVersion != '1.21.1')",
            "layout.buildDirectory.file('resources/main/META-INF/neoforge.mods.toml')",
            "layout.buildDirectory.file('classes/java/main/META-INF/neoforge.mods.toml')",
            "dependsOn tasks.named('compileJava'), tasks.named('processResources')",
            "tasks.register('prepareGameTestModMetadata', Copy)",
            "tasks.register('cleanGameTestModMetadata', Delete)",
            "tasks.named('runGameTestServer')",
            "dependsOn prepareGameTestModMetadata",
            "finalizedBy cleanGameTestModMetadata",
        ):
            with self.subTest(guard=snippet):
                self.assertIn(snippet, gradle)

    def test_26x_jade_retains_its_native_formatting(self):
        name = "compat/jade/BuildCraftJadePlugin.java"
        for target in ("26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge"):
            with self.subTest(target=target):
                source = self.source(target, name)
                if "import buildcraft.lib.compat.minecraft.text.BCTextFormat;" in source:
                    self.assertIn("BCTextFormat.WHITE", source)
                else:
                    self.assertNotIn("BCTextFormat", source)
                    self.assertIn("ChatFormatting.WHITE", source)


if __name__ == "__main__":
    unittest.main()
