#!/usr/bin/env python3
"""26.x NeoForge preview and snapshot portability guards.

These are source-resolution, syntax and small executable Java probes. They are
not a complete Minecraft Gradle compile, live renderer, or GameTest.
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]
from source_lookup import read_materialized_java, resolve_target_source
from minecraft_compat_fixture import parse_sources

TARGETS = ('26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')
GUI = 'src/main/java/buildcraft/builders/client/render/BlueprintScreenPreview.java'
FLUID = 'src/main/java/buildcraft/builders/client/render/pip/PreviewFluidRenderer.java'
TINT = 'src/main/java/buildcraft/builders/client/render/pip/PreviewFluidTint.java'
NET = 'src/main/java/buildcraft/builders/snapshot/MessageSnapshotResponse.java'


class Builders26xPreviewCompatibilityTests(unittest.TestCase):
    def test_modern_gui_uses_26x_gui_text_facade(self):
        for target in TARGETS:
            with self.subTest(target=target):
                source = read_materialized_java(target, GUI)
                self.assertIn('BCGraphics.text(graphics, Minecraft.getInstance().font,', source)
                self.assertIn('y + VIEW + 3, 0xFFE4E7ED, true);', source)
                self.assertNotIn('graphics.drawString(', source)
        # Base 1.21.11 GUI rendering must still use the legacy method.
        previous = read_materialized_java('1.21.11-neoforge', GUI)
        self.assertIn('graphics.drawString(', previous)
        self.assertNotIn('BCGraphics.text(graphics,', previous)

    def test_fluid_color_comes_from_26x_baked_fluid_model(self):
        for target in TARGETS:
            with self.subTest(target=target):
                preview = read_materialized_java(target, FLUID)
                tint = read_materialized_java(target, TINT)
                self.assertIn('int tint = PreviewFluidTint.color(fluidState);', preview)
                self.assertNotIn('.getTintColor(', preview)
                self.assertIn('getFluidStateModelSet().get(state)', tint)
                self.assertIn('model.fluidTintSource()', tint)
                self.assertIn('tint.color(state)', tint)
                self.assertNotIn('IClientFluidTypeExtensions', preview.split('int tint = ', 1)[1])
                self.assertIn('source-family-platforms/26.X/neoforge/',
                              resolve_target_source(target, TINT).as_posix())
        self.assertIn('IClientFluidTypeExtensions.of(fluid).getTintColor()',
                      read_materialized_java('1.21.11-neoforge', FLUID))

    def test_26x_network_uses_its_own_codec_overlay(self):
        for target in TARGETS:
            with self.subTest(target=target):
                path = resolve_target_source(target, NET).as_posix()
                code = read_materialized_java(target, NET)
                self.assertIn('source-family-platforms/26.X/neoforge/', path)
                self.assertIn('new GZIPInputStream(new ByteBufInputStream(buf))', code)
                self.assertIn('NbtIo.read(input, NbtAccounter.create(MAX_UNCOMPRESSED_SNAPSHOT_BYTES))', code)
                self.assertIn('if (input.read() != -1)', code)
                self.assertIn('FMLEnvironment.getDist()', code)
                self.assertNotIn('NbtIo.readCompressed(', code.replace('NbtIo.readCompressed() finishes', ''))
                self.assertNotIn('buf.readerIndex(buf.writerIndex())', code)
        self.assertIn('source-platforms/neoforge/',
                      resolve_target_source('1.21.11-neoforge', NET).as_posix())

    def test_native_26x_materialized_java_syntax(self):
        for target in TARGETS:
            with self.subTest(target=target), tempfile.TemporaryDirectory(prefix='26x-preview-') as temp:
                root = Path(temp)
                files = []
                for logical in (GUI, FLUID, TINT, NET):
                    path = root / logical.removeprefix('src/main/java/')
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(read_materialized_java(target, logical), encoding='utf-8')
                    files.append(path)
                self.assertIn('0 errors', parse_sources(files))

    def test_tint_helper_executes_for_tinted_untinted_and_unavailable_models(self):
        code = read_materialized_java('26.1.2-neoforge', TINT)
        stub_java = {
            'net/minecraft/client/Minecraft.java': '''package net.minecraft.client;
public class Minecraft {
    public boolean broken;
    private static final Minecraft INSTANCE = new Minecraft();
    public static Minecraft getInstance(){return INSTANCE;}
    public Manager getModelManager(){ if(broken) throw new IllegalStateException(); return new Manager(); }
    public static class Manager{public Models getFluidStateModelSet(){return new Models();}}
    public static class Models{public net.minecraft.client.renderer.block.FluidModel get(net.minecraft.world.level.material.FluidState s){
        return new net.minecraft.client.renderer.block.FluidModel(s.value); }}
}''',
            'net/minecraft/client/renderer/block/FluidModel.java': '''package net.minecraft.client.renderer.block;
public class FluidModel {
    final int color;
    public FluidModel(int color){this.color=color;}
    public Tint fluidTintSource(){return color == 0 ? null : new Tint(color);}
    public static class Tint {
        final int color;Tint(int color){this.color=color;}
        public int color(net.minecraft.world.level.material.FluidState state){return color;}
    }
}''',
            'net/minecraft/world/level/material/FluidState.java': '''package net.minecraft.world.level.material;
public class FluidState {
    public int value;public boolean empty;
    public FluidState(int value,boolean empty){this.value=value;this.empty=empty;}
    public boolean isEmpty(){return empty;}
}''',
            'buildcraft/builders/client/render/pip/PreviewFluidTintProbe.java': '''package buildcraft.builders.client.render.pip;
public class PreviewFluidTintProbe {
    public static void main(String[] args) {
        var dyed = new net.minecraft.world.level.material.FluidState(0xB0553388,false);
        var clean = new net.minecraft.world.level.material.FluidState(0,false);
        var empty = new net.minecraft.world.level.material.FluidState(42,true);
        if(PreviewFluidTint.color(dyed)!=0xB0553388)throw new AssertionError("tint lost");
        if(PreviewFluidTint.color(clean)!=-1)throw new AssertionError("untinted lost");
        if(PreviewFluidTint.color(empty)!=-1)throw new AssertionError("empty lost");
        net.minecraft.client.Minecraft.getInstance().broken=true;
        if(PreviewFluidTint.color(dyed)!=-1)throw new AssertionError("reload failure");
        System.out.println("26x fluid preview tint PASS");
    }
}''',
        }
        with tempfile.TemporaryDirectory(prefix='26x-fluid-api-') as temp:
            root = Path(temp)
            files = []
            for relative, contents in (*stub_java.items(), ('buildcraft/builders/client/render/pip/PreviewFluidTint.java', code)):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(contents, encoding='utf-8')
                files.append(path)
            result = subprocess.run(['javac', '-d', str(root), *[str(p) for p in files]],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(['java', '-cp', str(root),
                                     'buildcraft.builders.client.render.pip.PreviewFluidTintProbe'],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('26x fluid preview tint PASS', result.stdout)


if __name__ == '__main__':
    unittest.main()
