#!/usr/bin/env python3
"""Prevent the security hardening from reintroducing newer Minecraft APIs in Forge 1.19/1.20.

These are preprocessed-source compatibility checks. Full Gradle and Minecraft GameTests
remain authoritative for actual loader/mapping compilation and gameplay behavior.
"""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from source_config import load_properties, target_layout
from source_layout import resolve_effective_source, _materialize_text_file

NAMES = (
    "BlueprintSecurity.java",
    "BlueprintBuilder.java",
    "SchematicBlockDefault.java",
    "SchematicEntityDefault.java",
)
TARGETS = (
    "1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge", "1.21.11-neoforge",
    "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge",
)


class BlueprintSecurityCompilerCompatibility(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bcce-security-api-")
        cls.sources = {}
        props = load_properties()
        for target in TARGETS:
            layout = target_layout(target, props)
            downports = tuple(d for d in (
                layout.family_downport_root, layout.family_platform_downport_root,
            ) if d is not None)
            for name in NAMES:
                relative = f"src/main/java/buildcraft/builders/snapshot/{name}"
                source = resolve_effective_source(layout, props, relative)
                if source is None:
                    raise AssertionError(f"Missing {target} {relative}")
                dest = Path(cls.temp.name) / target / relative
                _materialize_text_file(
                    source, dest, logical_relative=relative,
                    minecraft=props[f"target.{target}.deps.minecraft"],
                    family=layout.family, platform=layout.platform, preprocess=True,
                    native_source=any(source.is_relative_to(d) for d in downports),
                )
                cls.sources[target, name] = dest.read_text(encoding="utf-8")

            pipe_relative = "src/main/java/buildcraft/transport/pipe/SchematicBlockPipe.java"
            pipe_source = resolve_effective_source(layout, props, pipe_relative)
            if pipe_source is None:
                raise AssertionError(f"Missing {target} {pipe_relative}")
            pipe_dest = Path(cls.temp.name) / target / pipe_relative
            _materialize_text_file(
                pipe_source, pipe_dest, logical_relative=pipe_relative,
                minecraft=props[f"target.{target}.deps.minecraft"],
                family=layout.family, platform=layout.platform, preprocess=True,
                native_source=any(pipe_source.is_relative_to(d) for d in downports),
            )
            cls.sources[target, "SchematicBlockPipe.java"] = pipe_dest.read_text(encoding="utf-8")

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def source(self, target, name):
        return self.sources[target, name]

    def test_1192_shared_registry_uses_legacy_minecraft_registry(self):
        sec = self.source("1.19.2-forge", "BlueprintSecurity.java")
        self.assertIn("import net.minecraft.core.Registry;", sec)
        self.assertIn("return Registry.BLOCK.getKey(block);", sec)
        self.assertNotIn("BuiltInRegistries", sec)
        self.assertNotIn("net.minecraftforge", sec)
        self.assertIn("PRIVILEGED_BLOCKS", sec)

    def test_1201_shared_registry_uses_builtin_registry(self):
        sec = self.source("1.20.1-forge", "BlueprintSecurity.java")
        self.assertIn("import net.minecraft.core.registries.BuiltInRegistries;", sec)
        self.assertIn("return BuiltInRegistries.BLOCK.getKey(block);", sec)
        self.assertNotIn("Registry.BLOCK.getKey(block)", sec)
        self.assertNotIn("net.minecraftforge", sec)

    def test_legacy_forge_entity_and_block_entity_registries(self):
        for target in TARGETS[:2]:
            with self.subTest(target=target):
                entity = self.source(target, "SchematicEntityDefault.java")
                block = self.source(target, "SchematicBlockDefault.java")
                self.assertIn("ForgeRegistries.ENTITY_TYPES.getValue(requestedType)", entity)
                self.assertIn("ForgeRegistries.BLOCK_ENTITY_TYPES", block)
                self.assertNotIn("BuiltInRegistries.ENTITY_TYPE", entity)
                self.assertNotIn("BuiltInRegistries.BLOCK_ENTITY_TYPE", block)

    def test_forge_robot_accounting_uses_compatible_exact_stack_comparison(self):
        for target in TARGETS[:2]:
            with self.subTest(target=target):
                builder = self.source(target, "BlueprintBuilder.java")
                self.assertIn("StackUtil.canMerge(required, found)", builder)
                self.assertNotIn("ItemStack.isSameItemSameComponents(required, found)", builder)
                self.assertIn("required.getCount() == found.getCount()", builder)

    def test_entity_placement_position_is_version_specific(self):
        old = self.source("1.19.2-forge", "BlueprintBuilder.java")
        modern = self.source("1.20.1-forge", "BlueprintBuilder.java")
        self.assertIn("BlockPos targetPos = new BlockPos(expectedPosition);", old)
        self.assertNotIn("BlockPos.containing(expectedPosition)", old)
        self.assertIn("BlockPos targetPos = BlockPos.containing(expectedPosition);", modern)
        self.assertNotIn("new BlockPos(expectedPosition)", modern)

    def test_block_entity_copy_does_not_capture_a_reassigned_local(self):
        # The old lambda compiled during syntax checks but javac rejected it
        # because buildWithoutChecks later reassigned newTileNbt.
        for target in TARGETS:
            with self.subTest(target=target):
                source = self.source(target, "SchematicBlockDefault.java")
                method = source.split("boolean buildWithoutChecks(", 1)[1]
                self.assertIn("CompoundTag newTileNbt = tileNbt.copy();", method)
                self.assertNotIn(".forEach(kv -> newTileNbt.put(", method)

    def test_block_entity_sanitizer_has_distinct_local_names(self):
        for target in TARGETS:
            with self.subTest(target=target):
                source = self.source(target, "BlueprintSecurity.java")
                method = source.split("CompoundTag forBlockEntity(", 1)[1].split(
                    "CompoundTag forPipe(", 1)[0]
                self.assertRegex(method, r"(?:ResourceLocation|Identifier) blockKey = blockId\(")
                self.assertIn("for (String tagKey : Set.of(", method)
                self.assertNotIn("for (String key : Set.of(", method)

    def test_pipe_schematic_nbt_compatibility_on_all_targets(self):
        for target in TARGETS:
            with self.subTest(target=target):
                source = self.source(target, "SchematicBlockPipe.java")
                self.assertNotIn("//? if", source)
                self.assertNotIn("//? } else", source)
                if target in ("1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge"):
                    self.assertNotIn("NbtCompat", source)
                    self.assertIn('CompoundTag savedPipe = tileNbt.getCompound("pipe");', source)
                    self.assertIn('String definition = savedPipe.getString("def");', source)
                    self.assertIn('savedPipe.contains("def", Tag.TAG_STRING)', source)
                else:
                    self.assertIn("import buildcraft.lib.compat.NbtCompat;", source)
                    self.assertIn('CompoundTag savedPipe = NbtCompat.getCompound(tileNbt, "pipe");', source)
                    self.assertIn('String definition = NbtCompat.getString(savedPipe, "def");', source)
                    self.assertIn('NbtCompat.contains(savedPipe, "def", Tag.TAG_STRING)', source)
                    self.assertNotIn('savedPipe.contains("def", Tag.TAG_STRING)', source)
                    if target == "1.21.11-neoforge":
                        self.assertIn('NbtCompat.getCompound(tileNbt, "pipe")', source)
                        self.assertNotIn('tileNbt.getCompound("pipe")', source)

    def test_block_update_offsets_use_three_coordinate_blockpos_constructor(self):
        # Minecraft 26.3 removed BlockPos(Vec3i). Older targets still support
        # Direction step components, so use the int/int/int constructor everywhere.
        for target in TARGETS:
            with self.subTest(target=target):
                sec = self.source(target, "BlueprintSecurity.java")
                self.assertIn(
                    "new BlockPos(dir.getStepX(), dir.getStepY(), dir.getStepZ())",
                    sec,
                )
                self.assertNotIn("new BlockPos(dir.getNormal())", sec)
                self.assertNotIn("new BlockPos(dir.getUnitVec3i())", sec)

    def test_modern_security_source_paths_are_still_materializable(self):
        for target in TARGETS[2:]:
            with self.subTest(target=target):
                sec = self.source(target, "BlueprintSecurity.java")
                self.assertIn("private static", sec)
                self.assertIn("PRIVILEGED_BLOCKS", sec)
                self.assertNotIn("//? if", sec)
                self.assertNotIn("net.minecraftforge", sec)


if __name__ == "__main__":
    unittest.main()
