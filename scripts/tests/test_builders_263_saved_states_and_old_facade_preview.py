#!/usr/bin/env python3
"""Source and behavior-contract regressions for 26.3 NBT and old Forge glass previews."""
from __future__ import annotations

import sys
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "scripts/tests"))
from source_lookup import read_materialized_java, resolve_target_source
from minecraft_compat_fixture import parse_sources

NATIVE = (
    "1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge", "1.21.11-neoforge",
    "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge",
)
SAVED_STATE = "src/main/java/buildcraft/lib/compat/SavedBlockStateNbt.java"
OLD_GUI = "src/main/java/buildcraft/builders/client/render/LegacyBlueprintGuiRenderer.java"
OLD_ALPHA = "src/main/java/buildcraft/transport/client/render/LegacyPipeAlphaRenderer.java"
FACADE = "src/main/java/buildcraft/silicon/plug/FacadePhasedState.java"
DEFAULT = "src/main/java/buildcraft/builders/snapshot/SchematicBlockDefault.java"
FLUID = "src/main/java/buildcraft/builders/snapshot/SchematicBlockFluid.java"


class SavedStateAndLegacyFacadePreviewTests(unittest.TestCase):
    def test_java_syntax_for_each_effective_target(self):
        for target in NATIVE:
            names = [SAVED_STATE]
            if target.endswith("-forge"):
                names.extend([OLD_ALPHA, OLD_GUI])
            elif target != "1.21.1-neoforge":
                names.extend([DEFAULT, FLUID, FACADE])
            else:
                names.append(FACADE)
            with self.subTest(target=target), tempfile.TemporaryDirectory() as tmp:
                sources = []
                for name in names:
                    path = Path(tmp) / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(read_materialized_java(target, name), encoding="utf-8")
                    sources.append(path)
                parsed = parse_sources(sources)
                self.assertIn("0 errors", parsed, parsed)

    def test_26_3_reads_both_formats_without_rewriting_saved_data(self):
        source = read_materialized_java("26.3-neoforge", SAVED_STATE)
        for expected in (
            'normalized = saved.copy()',
            'checkConflict(normalized, "Name", "id")',
            'checkConflict(normalized, "Properties", "properties")',
            'moveKey(normalized, "Name", "id")',
            'moveKey(normalized, "Properties", "properties")',
            'moveKey(result, "id", "Name")',
            'moveKey(result, "properties", "Properties")',
            'NbtUtils.readBlockState(BuiltInRegistries.BLOCK, normalized)',
        ):
            self.assertIn(expected, source)
        self.assertLess(source.index("normalized = saved.copy()"),
                        source.index('moveKey(normalized, "Name", "id")'))

    def test_other_versions_do_not_translate_modern_nbt(self):
        for target in NATIVE[:-1]:
            with self.subTest(target=target):
                source = read_materialized_java(target, SAVED_STATE)
                self.assertNotIn('moveKey(normalized, "Name", "id")', source)
                self.assertNotIn('moveKey(result, "id", "Name")', source)

    def test_blueprint_and_facade_share_compat_codec(self):
        for target in ("26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge"):
            with self.subTest(target=target):
                for name in (DEFAULT, FLUID):
                    body = read_materialized_java(target, name)
                    self.assertIn('SavedBlockStateNbt.write(blockState)', body)
                    self.assertTrue('SavedBlockStateNbt.read(nbt.getCompound("blockState"))' in body
                                    or 'SavedBlockStateNbt.read(NbtCompat.getCompound(nbt, "blockState"))' in body)
                facade = read_materialized_java(target, FACADE)
                self.assertTrue('SavedBlockStateNbt.read(nbt.getCompound("state"))' in facade
                                or 'SavedBlockStateNbt.read(NbtCompat.getCompound(nbt, "state"))' in facade)
                self.assertIn('SavedBlockStateNbt.write(stateInfo.state)', facade)
                security = read_materialized_java(target, DEFAULT)
                self.assertIn('BlueprintSecurity.validateDefaultBlock(', security)

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Java runtime unavailable")
    def test_execute_real_26_3_block_state_codec_against_nbt_fixtures(self):
        """Compile the actual materialized codec with minimal NBT/Minecraft contracts."""
        fixtures = {
            "buildcraft/lib/internal/core/InvalidInputDataException.java": '''package buildcraft.lib.internal.core;
                public class InvalidInputDataException extends java.io.IOException {
                    public InvalidInputDataException(String message) { super(message); }
                }''',
            "net/minecraft/nbt/Tag.java": '''package net.minecraft.nbt;
                public abstract class Tag { public abstract Tag copy(); }''',
            "net/minecraft/nbt/StringTag.java": '''package net.minecraft.nbt;
                public final class StringTag extends Tag {
                    public final String value;
                    public StringTag(String value) { this.value = value; }
                    @Override public Tag copy() { return new StringTag(value); }
                    @Override public boolean equals(Object obj) {
                        return obj instanceof StringTag tag && tag.value.equals(value);
                    }
                }''',
            "net/minecraft/nbt/CompoundTag.java": '''package net.minecraft.nbt;
                public final class CompoundTag extends Tag {
                    private final java.util.Map<String, Tag> data = new java.util.HashMap<>();
                    public Tag get(String name) { return data.get(name); }
                    public void put(String name, Tag value) { data.put(name, value); }
                    public void putString(String name, String value) { put(name, new StringTag(value)); }
                    public void remove(String name) { data.remove(name); }
                    @Override public CompoundTag copy() {
                        CompoundTag result = new CompoundTag();
                        data.forEach((key, value) -> result.put(key, value.copy()));
                        return result;
                    }
                    @Override public boolean equals(Object obj) {
                        return obj instanceof CompoundTag tag && tag.data.equals(data);
                    }
                }''',
            "net/minecraft/core/registries/BuiltInRegistries.java": '''package net.minecraft.core.registries;
                public final class BuiltInRegistries { public static final Object BLOCK = new Object(); }''',
            "net/minecraft/world/level/block/state/BlockState.java": '''package net.minecraft.world.level.block.state;
                import net.minecraft.nbt.CompoundTag;
                public final class BlockState {
                    public final String name;
                    public final CompoundTag properties;
                    public BlockState(String name, CompoundTag properties) {
                        this.name = name; this.properties = properties;
                    }
                }''',
            "net/minecraft/nbt/NbtUtils.java": '''package net.minecraft.nbt;
                import net.minecraft.world.level.block.state.BlockState;
                public final class NbtUtils {
                    public static CompoundTag writeBlockState(BlockState state) {
                        CompoundTag tag = new CompoundTag();
                        tag.putString("id", state.name);
                        tag.put("properties", state.properties.copy());
                        return tag;
                    }
                    public static BlockState readBlockState(Object blocks, CompoundTag tag) {
                        StringTag id = (StringTag) tag.get("id");
                        CompoundTag props = (CompoundTag) tag.get("properties");
                        return new BlockState(id.value, props == null ? new CompoundTag() : props.copy());
                    }
                }''',
            "CodecRuntimeTest.java": '''import buildcraft.lib.compat.SavedBlockStateNbt;
                import buildcraft.lib.internal.core.InvalidInputDataException;
                import net.minecraft.nbt.CompoundTag;
                import net.minecraft.world.level.block.state.BlockState;
                public final class CodecRuntimeTest {
                    public static void main(String[] args) throws Exception {
                        CompoundTag old = new CompoundTag();
                        old.putString("Name", "buildcraftcore:engine");
                        CompoundTag props = new CompoundTag();
                        props.putString("type", "creative");
                        old.put("Properties", props);
                        CompoundTag untouched = old.copy();
                        BlockState restored = SavedBlockStateNbt.read(old);
                        if (!restored.name.equals("buildcraftcore:engine") ||
                            !restored.properties.equals(props) || !old.equals(untouched))
                            throw new AssertionError("Legacy engine state lost or input changed");
                        CompoundTag save = SavedBlockStateNbt.write(restored);
                        if (save.get("Name") == null || save.get("Properties") == null ||
                            save.get("id") != null || save.get("properties") != null)
                            throw new AssertionError("New saves are not backward compatible");
                        CompoundTag modern = new CompoundTag();
                        modern.putString("id", "minecraft:glass");
                        if (!SavedBlockStateNbt.read(modern).name.equals("minecraft:glass"))
                            throw new AssertionError("Modern format failed");
                        CompoundTag conflicting = old.copy();
                        conflicting.putString("id", "minecraft:bedrock");
                        try {
                            SavedBlockStateNbt.read(conflicting);
                            throw new AssertionError("Conflicting block IDs accepted");
                        } catch (InvalidInputDataException correct) { }
                        CompoundTag conflictProps = old.copy();
                        CompoundTag otherProps = new CompoundTag();
                        otherProps.putString("type", "redstone");
                        conflictProps.put("properties", otherProps);
                        try {
                            SavedBlockStateNbt.read(conflictProps);
                            throw new AssertionError("Conflicting engine properties accepted");
                        } catch (InvalidInputDataException correct) { }
                    }
                }''',
        }
        with tempfile.TemporaryDirectory(prefix="bcce-263-state-codec-") as tmp:
            project = Path(tmp)
            for name, source in fixtures.items():
                path = project / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding="utf-8")
            codec = project / "buildcraft/lib/compat/SavedBlockStateNbt.java"
            codec.parent.mkdir(parents=True, exist_ok=True)
            codec.write_text(read_materialized_java("26.3-neoforge", SAVED_STATE), encoding="utf-8")
            sources = sorted(str(path) for path in project.rglob("*.java"))
            subprocess.run(["javac", *sources], cwd=project, check=True,
                           capture_output=True, text=True, timeout=30)
            subprocess.run(["java", "-cp", str(project), "CodecRuntimeTest"],
                           cwd=project, check=True, capture_output=True, text=True, timeout=15)

    def test_old_facades_receive_alpha_quads_without_world_queue(self):
        for target in ("1.19.2-forge", "1.20.1-forge"):
            with self.subTest(target=target):
                gui = read_materialized_java(target, OLD_GUI)
                alpha = read_materialized_java(target, OLD_ALPHA)
                self.assertIn('ModelPipe.getAlphaQuads(tile)', alpha)
                self.assertIn('LegacyPipeAlphaRenderer.renderPreview(pipe, pose, buffers,', gui)
                self.assertIn('groupAlphaQuads(ModelPipe.getAlphaQuads(tile))', alpha)
                self.assertIn('renderer.renderModel(pose.last(), consumer, tile.getBlockState(), model,', alpha)
                self.assertIn('1.0F, 1.0F, 1.0F, 15728880, overlay)', alpha)
                self.assertIn('groupAlphaQuads(source)', alpha)
                self.assertIn('tile.getLevel() != Minecraft.getInstance().level', alpha)
                self.assertIn('batch.getBuffer(glassFacade ? GLASS_LAYER : OTHER_LAYER)', alpha)
                self.assertIn('new FixedAlphaConsumer(', alpha)
                self.assertIn('tile, false)', alpha)
                self.assertIn('if (!worldOcclusion || alpha != STAINED_GLASS_ALPHA', alpha)
                self.assertIn('if (be instanceof TilePipeHolder pipe)', gui)
                self.assertNotIn('LegacyPipeAlphaRenderer.flush(', gui)
                self.assertLess(gui.index('LegacyPipeAlphaRenderer.renderPreview('),
                                gui.index('buffers.endBatch()'))


if __name__ == "__main__":
    unittest.main()
