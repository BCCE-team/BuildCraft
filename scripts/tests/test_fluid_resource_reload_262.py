#!/usr/bin/env python3
"""Regression: fluid sprite reload ordering and 26.2 BCCE model warnings."""
from __future__ import annotations

import json
from pathlib import Path
import sys
import unittest

import struct

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'source-shared/src/main/resources/assets'
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source

# Exact local-model diagnostics reported by the 26.2 game startup log.
MODEL_WARNINGS = """
buildcraftrobotics:item/robot/lumberjack
buildcraftrobotics:item/robot/planter
buildcraftenergy:fluids/oil_distilled/hot
buildcraftrobotics:item/robot/picker
buildcraftrobotics:item/robot/shovelman
buildcraftrobotics:item/robot/fluid_carrier
buildcraftenergy:fluids/oil_distilled/searing
buildcrafttransport:item/plug_power_adaptor
buildcraftcore:item/engine_redstone
buildcraftenergy:fluids/oil_residue/searing
buildcraftrobotics:item/robot/delivery
buildcraftbuilders:block/builder/slot_template
buildcraftcore:item/engine_creative
buildcraftbuilders:block/builder/slot_empty
buildcraftrobotics:item/robot/builder
buildcraftenergy:fluids/fuel_mixed_light/searing
buildcraftenergy:fluids/oil_residue/hot
buildcraftrobotics:item/robot/empty
buildcraftenergy:fluids/fuel_gaseous/cool
buildcraftrobotics:item/robot/miner
buildcraftenergy:item/engine_fe
buildcraftrobotics:item/robot/leave_cutter
buildcraftrobotics:item/robot/farmer
buildcraftenergy:fluids/oil_residue/cool
buildcraftenergy:fluids/fuel_mixed_heavy/searing
buildcraftenergy:fluids/oil_dense/searing
buildcraftenergy:fluids/fuel_gaseous/hot
buildcraftenergy:fluids/oil_dense/hot
buildcraftenergy:fluids/fuel_gaseous/searing
buildcraftrobotics:item/robot/knight
buildcraftenergy:fluids/fuel_dense/searing
buildcraftenergy:fluids/oil/cool
buildcraftenergy:fluids/oil_dense/cool
buildcraftenergy:fluids/oil/hot
buildcraftcore:item/volume_box
buildcraftenergy:fluids/fuel_light/searing
buildcraftfactory:item/heat_exchange_middle
buildcraftenergy:fluids/fuel_dense/hot
buildcraftfactory:item/distiller
buildcraftenergy:fluids/fuel_dense/cool
buildcraftenergy:item/engine_stone
buildcraftlib:item/engine_base
buildcraftenergy:fluids/oil_heavy/searing
buildcraftrobotics:item/robot/butcher
buildcraftrobotics:item/robot/pump
buildcraftfactory:item/heat_exchange_start
buildcraftbuilders:block/builder/slot_blueprint
buildcraftenergy:fluids/fuel_mixed_heavy/hot
buildcraftrobotics:item/robot/bomber
buildcraftfactory:item/heat_exchange_end
buildcraftrobotics:item/robot/stripes
buildcraftrobotics:item/robot/harvester
buildcraftenergy:fluids/fuel_light/hot
buildcraftenergy:fluids/fuel_mixed_heavy/cool
buildcraftenergy:fluids/oil/searing
buildcraftenergy:item/engine_iron
buildcraftenergy:fluids/oil_distilled/cool
buildcraftenergy:fluids/oil_heavy/hot
buildcraftenergy:fluids/oil_heavy/cool
buildcraftenergy:fluids/fuel_mixed_light/hot
buildcraftenergy:fluids/fuel_light/cool
buildcraftenergy:fluids/fuel_mixed_light/cool
buildcraftrobotics:item/robot/carrier
""".split()


class ResourceReload26Regression(unittest.TestCase):
    def test_fluid_stitch_does_not_query_uninitialized_model_manager(self):
        props = load_properties()
        targets = ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge',
                   '1.21.11-neoforge', '26.1.2-neoforge',
                   '26.2-neoforge', '26.3-neoforge')
        for target in targets:
            with self.subTest(target=target):
                layout = target_layout(target, props)
                src = resolve_effective_source(layout, props,
                    'src/main/java/buildcraft/lib/client/render/fluid/FluidRenderer.java')
                self.assertIsNotNone(src)
                code = src.read_text(encoding='utf-8')
                stitch = code.split('public static void onTextureStitchPost(ClientAtlas.After event)', 1)[1]
                stitch = stitch.split('private static void clearSpriteCache()', 1)[0]
                self.assertIn('clearSpriteCache()', stitch)
                self.assertNotIn('for (Fluid fluid : BuiltInRegistries.FLUID)', stitch)
                self.assertNotIn('getStillTextureSafe(fluid)', stitch)
                self.assertNotIn('getFlowingTextureSafe(fluid)', stitch)
                self.assertIn('getFluidSprite(', code)
                if target in ('26.2-neoforge', '26.3-neoforge'):
                    self.assertIn('return switch (type)', code)
                    self.assertIn('fluidSprite(fluid, true)', code)

    def test_particle_references_are_all_defined_and_point_at_real_sprites(self):
        for model in MODEL_WARNINGS:
            namespace, path = model.split(':', 1)
            src = ASSETS / namespace / 'models' / (path + '.json')
            with self.subTest(model=model):
                self.assertTrue(src.exists(), model)
                data = json.loads(src.read_text(encoding='utf-8'))
                particle = data['textures']['particle']
                if particle.startswith('#'):
                    self.assertIn(particle[1:], data['textures'])
                    self.assertNotEqual(particle, data['textures'][particle[1:]])
                else:
                    ns, tex = particle.split(':', 1)
                    self.assertTrue((ASSETS / ns / 'textures' / (tex + '.png')).is_file(), particle)

    def test_no_legacy_bucket_texture_is_missing_from_item_atlas(self):
        ids = ('buildcraftenergy:items/bucket_fuel', 'buildcraftenergy:items/bucket_red_plasma')
        for version in ('1.21.11', '26.2', '26.3'):
            target = version + '-neoforge'
            props = load_properties()
            layout = target_layout(target, props)
            if version == '1.21.11':
                src = ROOT / 'resource-src/1.21.X/1.21.11/assets/minecraft/atlases/items.json'
            else:
                src = resolve_effective_source(layout, props,
                    'src/main/resources/assets/minecraft/atlases/items.json')
            self.assertIsNotNone(src, target)
            self.assertTrue(src.is_file())
            resources = json.loads(src.read_text(encoding='utf-8'))['sources']
            for rid in ids:
                with self.subTest(target=target, sprite=rid):
                    self.assertIn({'type': 'minecraft:single', 'resource': rid}, resources)
                    ns, tex = rid.split(':', 1)
                    self.assertTrue((ASSETS / ns / 'textures' / (tex + '.png')).exists())

    def test_architect_keeps_six_different_faces(self):
        for suffix in ('architect_on', 'architect_off'):
            data = json.loads((ASSETS / 'buildcraftbuilders/models/block' / (suffix + '.json')).read_text())
            self.assertEqual('block/cube', data['parent'])
            self.assertTrue({'up','down','north','south','east','west'}.issubset(data['textures']))

    def test_builder_base_depends_only_on_facing(self):
        paths = (
            ROOT / 'source-families/old/src/main/resources/assets/buildcraftbuilders/blockstates/builder.json',
            ROOT / 'source-families/1.21.X/src/main/resources/assets/buildcraftbuilders/blockstates/builder.json',
            ROOT / 'source-shared/src/main/resources/assets/buildcraftbuilders/blockstates/builder2.json')
        for src in paths:
            data = json.loads(src.read_text())
            self.assertEqual(4, sum(1 for part in data['multipart'] if part['apply']['model'].endswith('/main')))
            for part in data['multipart']:
                self.assertNotEqual('none|blueprint|template', part.get('when', {}).get('snapshot_type'), src)

    @staticmethod
    def png_dimensions(path: Path) -> tuple[int, int]:
        header = path.read_bytes()[:24]
        assert header.startswith(b"\x89PNG\r\n\x1a\n") and header[12:16] == b"IHDR"
        return struct.unpack(">II", header[16:24])

    def test_sprite_dimensions_match_animation_metadata_and_mip_level(self):
        p = ASSETS / 'buildcraftfactory/textures/blocks/plain_pipe/end.png'
        self.assertEqual((16,16), self.png_dimensions(p))
        for name in ('oil_still','redplasma_still','fuel_still'):
            p = ASSETS / 'buildcraftenergy/textures/blocks/fluids' / (name + '.png')
            meta = json.loads(Path(str(p) + '.mcmeta').read_text())['animation']['frames']
            indices = [f if isinstance(f, int) else f['index'] for f in meta]
            width, height = self.png_dimensions(p)
            self.assertEqual((16,320),(width,height))
            self.assertLess(max(indices),height // width)

if __name__ == '__main__':
    unittest.main()
