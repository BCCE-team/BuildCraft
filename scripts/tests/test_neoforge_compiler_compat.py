#!/usr/bin/env python3
"""Keep Redstone Engine and Jade source overrides compilable on 1.21.1 and 1.21.11.

Checks materialized Java, not just the canonical source layer. These assertions
cover version selectors and the two Jade source implementations without loading
Minecraft/NeoForge dependencies.
"""

from __future__ import annotations

import sys
import json
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
            for name in ("core/blockEntity/TileEngineRedstone_BC8.java", "compat/jade/BuildCraftJadePlugin.java",
                         "lib/crops/CropHandlerPlantable.java",
                         "transport/pipe/flow/TravellingItem.java"):
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

    def test_travelling_item_nbt_accessor_compatibility_across_neoforge(self):
        name = "transport/pipe/flow/TravellingItem.java"
        old = self.source("1.21.1-neoforge", name)
        # The 1.21.1 downport must not select the modern Optional facade.
        self.assertNotIn('NbtCompat.getCompound(nbt, "stack")', old)

        # 1.21.11 and all 26.X targets expose Optional-returning getters.
        # Test the effective source so preprocessing and downports cannot
        # silently bypass this compatibility contract.
        guarded = (
            'ItemStackUtil.parseOptional(registries, NbtCompat.getCompound(nbt, "stack"))',
            'NbtCompat.getByte(nbt, "colour")',
            'NbtCompat.getBoolean(nbt, "toCenter")',
            'NbtCompat.getBoolean(nbt, "pausedAtCenter")',
            'NbtCompat.getDouble(nbt, "speed")',
            'NbtCompat.getInt(nbt, "timeToDest")',
            'NbtCompat.getInt(nbt, "tickFinished")',
            'NbtCompat.getBoolean(nbt, "isPhantom")',
            'NbtCompat.getAllKeys(entries)',
        )
        for target in ("1.21.11-neoforge", "26.1.2-neoforge",
                       "26.2-neoforge", "26.3-neoforge"):
            with self.subTest(target=target):
                java = self.source(target, name)
                for token in guarded:
                    self.assertIn(token, java)

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
        # All targets must materialize valid TOML without an optional
        # Create dependency: Create is no longer part of the build.
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
                self.assertEqual(create_deps, [])
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

    def test_plantable_crop_adapter_survives_vegetation_hierarchy_change(self):
        name = "lib/crops/CropHandlerPlantable.java"
        legacy = self.source("1.21.1-neoforge", name)
        self.assertIn("block instanceof BushBlock", legacy)
        self.assertNotIn("import net.minecraft.world.level.block.VegetationBlock;", legacy)
        for target in ("1.21.11-neoforge", "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge"):
            with self.subTest(target=target):
                current = self.source(target, name)
                self.assertIn("block instanceof VegetationBlock", current)
                self.assertNotIn("block instanceof BushBlock", current)

    def test_guide_corruption_fixture_replaces_a_frame_instead_of_adding_one(self):
        test = (ROOT / "source-platforms/neoforge/src/gametest/java/buildcraft/gametest/"
                "SystemGuideRecipeBatchGameTests.java").read_text(encoding="utf-8")
        self.assertIn("altered.writeVarInt(frameCount);", test)
        self.assertNotIn("altered.writeVarInt(frameCount + 1);", test)
        self.assertIn("i == CHUNK_SIZE / 2 ? new byte[] {0} : frame", test)
        self.assertIn("received.size() == (part == 1 ? CHUNK_SIZE - 1 : CHUNK_SIZE)", test)

    def test_pack_metadata_respects_1_21_11_supported_formats_boundary(self):
        props = load_properties()
        source = ROOT / "source-shared/src/main/resources/pack.mcmeta"
        for target in ("1.21.1-neoforge", "1.21.11-neoforge", "26.1.2-neoforge",
                       "26.2-neoforge", "26.3-neoforge"):
            with self.subTest(target=target):
                layout = target_layout(target, props)
                output = Path(self.tmp.name) / target / "src/main/resources/pack.mcmeta"
                _materialize_text_file(
                    source, output, logical_relative="src/main/resources/pack.mcmeta",
                    minecraft=props[f"target.{target}.deps.minecraft"],
                    family=layout.family, platform=layout.platform, preprocess=True,
                )
                text = output.read_text(encoding="utf-8")
                for key, prop_key in (("pack_format", "pack.format"),
                                      ("resource_pack_format", "pack.resource_format"),
                                      ("data_pack_format", "pack.data_format")):
                    text = text.replace("${" + key + "}", props[f"target.{target}.{prop_key}"])
                pack = json.loads(text)["pack"]
                if target == "1.21.11-neoforge":
                    self.assertEqual(pack["supported_formats"], [75, 81])
                    self.assertNotIn("min_format", pack)
                    self.assertNotIn("max_format", pack)
                elif target.startswith("26."):
                    self.assertEqual(pack["min_format"], pack["pack_format"])
                    self.assertEqual(pack["max_format"], pack["pack_format"])
                    self.assertNotIn("supported_formats", pack)
                else:
                    self.assertNotIn("supported_formats", pack)
                    self.assertNotIn("min_format", pack)
                    self.assertNotIn("max_format", pack)


if __name__ == "__main__":
    unittest.main()
