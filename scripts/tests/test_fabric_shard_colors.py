#!/usr/bin/env python3
"""Regression coverage for the Fabric fluid-shard liquid-mask color."""
from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source, _materialize_text_file

JAVA = 'src/main/java/buildcraft/'
ASSETS = 'src/main/resources/assets/'
TARGETS = ('26.2-fabric', '26.3-fabric')
FAMILIES = ('oil', 'oil_residue', 'oil_heavy', 'oil_dense', 'oil_distilled',
            'fuel_dense', 'fuel_mixed_heavy', 'fuel_light', 'fuel_mixed_light', 'fuel_gaseous')


class FabricShardColorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.props = load_properties()
        cls.targets = [t for t in TARGETS if f'target.{t}.deps.minecraft' in cls.props]
        if not cls.targets:
            raise AssertionError('No supported Fabric targets')

    def effective(self, target, relative):
        path = resolve_effective_source(target_layout(target, self.props), self.props, relative)
        self.assertIsNotNone(path, (target, relative))
        return path

    def test_mask_and_client_registration_on_all_fabric_targets(self):
        for target in self.targets:
            with self.subTest(target=target):
                item = json.loads(self.effective(target, ASSETS + 'buildcraftcore/items/fragile_fluid_shard.json').read_text())['model']
                model = json.loads(self.effective(target, ASSETS + 'buildcraftcore/models/item/fragile_fluid_shard.json').read_text())
                self.assertEqual('minecraft:model', item['type'])
                self.assertEqual({'type': 'minecraft:constant', 'value': 16777215}, item['tints'][0])
                self.assertEqual({'type': 'buildcraftcore:fluid_shard_contents'}, item['tints'][1])
                self.assertTrue(model['textures']['layer0'].endswith('_base'))
                self.assertTrue(model['textures']['layer1'].endswith('_fluid'))
                bootstrap = self.effective(target, JAVA + 'core/client/FabricCoreClient.java').read_text()
                self.assertIn('FluidShardTintSource.MAP_CODEC', bootstrap)
                self.assertIn('"fluid_shard_contents"', bootstrap)

    def test_effective_source_uses_texture_and_component_sensitive_tint(self):
        for target in self.targets:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as directory:
                layout = target_layout(target, self.props)
                for name in ('core/client/FluidShardTintSource.java', 'core/client/FluidShardColors.java',
                             'fabric/FabricModelLifecycle263.java'):
                    relative = JAVA + name
                    path = self.effective(target, relative)
                    output = Path(directory) / relative
                    downports = tuple(p for p in (layout.family_downport_root, layout.family_platform_downport_root) if p)
                    _materialize_text_file(path, output, logical_relative=relative,
                        minecraft=self.props[f'target.{target}.deps.minecraft'], family=layout.family,
                        platform=layout.platform, preprocess=True,
                        native_source=any(path.is_relative_to(p) for p in downports))
                tint = (Path(directory) / JAVA / 'core/client/FluidShardTintSource.java').read_text()
                lifecycle = (Path(directory) / JAVA / 'fabric/FabricModelLifecycle263.java').read_text()
                for token in ('ItemFragileFluidContainer.getFluid(stack)', 'return 0x00FFFFFF;',
                              'fluid.getComponentsPatch()', 'FluidVariantRendering.getColor(variant)',
                              'FluidShardColors.multiplyRgb(textureColor(variant), tint)',
                              'getFluidStateModelSet()', 'model.stillMaterial().sprite()',
                              'getResourceManager().getResource(png)', 'computeIfAbsent(sprite,',
                              'FluidShardColors.averageTexture(image)'):
                    self.assertIn(token, tint)
                self.assertIn('FluidShardTintSource.clearTextureColors();', lifecycle)

    def test_real_oil_fuel_textures_and_rgb_multiplication(self):
        fixture = '''package buildcraft.core.client;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashSet;
import javax.imageio.ImageIO;
public final class ShardColorsFixture {
    static void eq(int expected, int actual) {
        if (expected != actual) throw new AssertionError(String.format("%08x != %08x", expected, actual));
    }
    public static void main(String[] args) throws Exception {
        BufferedImage image = new BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFFF0000);
        image.setRGB(1, 0, 0x0000FF00); // transparent green must not affect the average
        image.setRGB(2, 0, 0xFF0000FF);
        eq(0xFF800080, FluidShardColors.averageTexture(image));
        eq(0xFFFFFFFF, FluidShardColors.averageTexture(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)));
        eq(0xFF123456, FluidShardColors.multiplyRgb(0xFFFFFFFF, 0x00123456));
        eq(0xFF112233, FluidShardColors.multiplyRgb(0xFF112233, 0xFFFFFFFF));
        eq(0xFF4080FF, FluidShardColors.multiplyRgb(0xFF80FFFF, 0xFF8080FF));
        HashSet<Integer> cold = new HashSet<>();
        for (int i = 0; i < args.length; i++) {
            int color = FluidShardColors.averageTexture(ImageIO.read(new File(args[i])));
            eq(color, FluidShardColors.multiplyRgb(color, 0xFFFFFFFF));
            if (color == 0xFFFFFFFF || (color >>> 24) != 255) throw new AssertionError(args[i]);
            if (i % 3 == 0) cold.add(color);
            if (args[i].endsWith("/oil/cool_still.png")) eq(0xFF111111, color);
            if (args[i].endsWith("/fuel_dense/cool_still.png")) eq(0xFFE5860A, color);
        }
        if (cold.size() != 10) throw new AssertionError("distinct petroleum colors: " + cold.size());
    }
}
'''
        # Compile and execute the real JDK-only helper against all 30 current
        # petroleum PNGs; neither a mock renderer nor a hardcoded palette is used.
        for target in self.targets:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as directory:
                temp = Path(directory)
                java = temp / 'ShardColorsFixture.java'
                java.write_text(fixture)
                helper = self.effective(target, JAVA + 'core/client/FluidShardColors.java')
                textures = [self.effective(target, ASSETS + 'buildcraftenergy/textures/blocks/fluids/' +
                    family + '/' + heat + '_still.png') for family in FAMILIES for heat in ('cool', 'hot', 'searing')]
                subprocess.run(['javac', '-d', str(temp), str(helper), str(java)], check=True)
                subprocess.run(['java', '-Djava.awt.headless=true', '-cp', str(temp),
                    'buildcraft.core.client.ShardColorsFixture', *map(str, textures)], check=True)


if __name__ == '__main__':
    unittest.main()
