#!/usr/bin/env python3
"""26.X compat and bucket-model contracts across native and legacy targets."""
from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT/'scripts'), str(ROOT/'scripts/tests')]
from source_config import load_properties, target_layout
from source_layout import effective_source_files, _materialize_text_file
from minecraft_compat_fixture import parse_sources
from compat_26_fixture import execute
from transforms.resources import (
    _dynamic_fluid_bucket_client_item_1_21_11,
    _buildcraftenergy_bucket_generated_model_1_21_11,
)

PREFIX = 'src/main/java/buildcraft/compat'


class CompatBuckets26Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bc-compat-buckets-26-')
        cls.outputs = {}
        props=load_properties()
        for ver in ('26.1.2','26.2','26.3'):
            layout=target_layout(ver+'-neoforge',props)
            downs=tuple(p for p in (layout.family_downport_root,layout.family_platform_downport_root) if p)
            out=Path(cls.temp.name)/ver
            for relative,source in sorted(effective_source_files(layout,props,PREFIX).items()):
                _materialize_text_file(source,out/relative,logical_relative=relative,
                    minecraft=props[f'target.{ver}-neoforge.deps.minecraft'],family=layout.family,
                    platform=layout.platform,preprocess=True,
                    native_source=any(source.is_relative_to(p) for p in downs))
            cls.outputs[ver] = out

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def source(self,ver,path):
        return (self.outputs[ver]/PREFIX/path).read_text(encoding='utf-8')


    def test_2612_compat_sources_materialize(self):
        self.assertIn("enum CompatCapTransfromer", self.source("26.1.2", "CompatCapTransfromer.java"))

    def test_262_compat_sources_materialize(self):
        self.assertIn("enum CompatCapTransfromer", self.source("26.2", "CompatCapTransfromer.java"))

    def test_2612_bucket_definition_semantics(self):
        canonical = {
            "model": {
                "type": "neoforge:fluid_container",
                "textures": {
                    "particle": "minecraft:item/bucket",
                    "base": "minecraft:item/bucket",
                    "fluid": "neoforge:item/mask/bucket_fluid",
                },
                "fluid": "buildcraftenergy:oil",
                "flip_gas": True,
                "apply_fluid_luminosity": False,
            }
        }
        actual = json.loads(_dynamic_fluid_bucket_client_item_1_21_11("buildcraftenergy:oil", minecraft="26.1.2"))
        self.assertEqual(canonical, actual)

    def test_26_2_and_26_3_bucket_models_are_dynamic_and_keep_metal_untinted(self):
        # Compare against distinct fixed contracts instead of the models' own
        # generated values. These model definitions must not regress to the
        # pre-26.2 static BuildCraft bucket artwork.
        for ver in ('26.2', '26.3'):
            for family in (
                'oil', 'oil_dense', 'oil_distilled', 'oil_heavy', 'oil_residue',
                'fuel_dense', 'fuel_gaseous', 'fuel_light',
                'fuel_mixed_light', 'fuel_mixed_heavy',
            ):
                for heat, fluid_suffix in (('cool', ''), ('hot', '_heat_1'), ('searing', '_heat_2')):
                    item = f'{family}/{heat}_bucket'
                    fluid = 'buildcraftenergy:' + family + fluid_suffix
                    with self.subTest(version=ver, item=item):
                        definition = json.loads(_dynamic_fluid_bucket_client_item_1_21_11(
                            fluid, minecraft=ver))['model']
                        model = json.loads(_buildcraftenergy_bucket_generated_model_1_21_11(
                            '/src/main/resources/assets/buildcraftenergy/models/item/' + item + '.json',
                            minecraft=ver))
                        self.assertEqual('neoforge:fluid_container', definition['type'])
                        self.assertEqual(fluid, definition['fluid'])
                        self.assertEqual('minecraft:item/bucket', definition['textures']['base'])
                        self.assertEqual('buildcraftenergy:block/mask/bucket_surface',
                                         definition['textures']['fluid'])
                        self.assertNotIn('cover', definition['textures'])
                        self.assertNotIn('cover_is_mask', definition)
                        self.assertEqual('minecraft:item/generated', model['parent'])
                        self.assertEqual('minecraft:item/bucket', model['textures']['layer0'])

    def test_modern_bucket_surface_mask_is_only_the_visible_liquid(self):
        from PIL import Image
        mask = ROOT / ('source-families/26.X/src/main/resources/assets/buildcraftenergy/'
                       'textures/block/mask/bucket_surface.png')
        self.assertTrue(mask.is_file())
        # Exact visible liquid silhouette observed in vanilla Minecraft 26.2
        # water_bucket (metal rim and body are excluded). No vanilla PNG is
        # copied into the project.
        expected = {(x, 3) for x in range(4, 12)}
        expected |= {(x, 4) for x in range(3, 13)}
        expected |= {(x, 5) for x in range(5, 11)}
        self.assertEqual(24, len(expected))
        with Image.open(mask) as source:
            self.assertEqual((16, 16), source.size)
            pixels = source.convert('RGBA')
            for y in range(16):
                for x in range(16):
                    self.assertEqual((255, 255, 255, 255) if (x, y) in expected
                                     else (0, 0, 0, 0), pixels.getpixel((x, y)), (x, y))
        # Older target models do not reference the new sprite.
        old = json.loads(_dynamic_fluid_bucket_client_item_1_21_11(
            'buildcraftenergy:oil', minecraft='26.1.2'))['model']
        self.assertEqual('neoforge:item/mask/bucket_fluid', old['textures']['fluid'])
        self.assertNotIn('cover', old['textures'])
        self.assertEqual({'parent': 'minecraft:item/generated',
                          'textures': {'layer0': 'minecraft:item/bucket',
                                       'particle': 'minecraft:item/bucket'}},
                         json.loads(_buildcraftenergy_bucket_generated_model_1_21_11(
                             '/src/main/resources/assets/buildcraftenergy/models/item/oil/cool_bucket.json',
                             minecraft='26.1.2')))

    def test_26_3_jade_native_resource_and_energy_apis(self):
        jade=self.source('26.3','jade/BuildCraftJadePlugin.java')
        view=self.source('26.3','jade/JadeViewData.java')
        for term in ('ResourceHandler<ItemResource>','ResourceHandler<FluidResource>',
                     'Capabilities.Item.BLOCK','Capabilities.Fluid.BLOCK',
                     'Capabilities.Energy.BLOCK','robot.resourceHandler()'):
            self.assertIn(term,jade)
        for removed in ('net.neoforged.neoforge.items.IItemHandler',
                        'net.neoforged.neoforge.fluids.capability.IFluidHandler',
                        'net.neoforged.neoforge.energy.IEnergyStorage',
                        'ChatFormatting'):
            self.assertNotIn(removed,jade)
        self.assertNotIn('ChatFormatting',view)
        self.assertIn('BCTextFormat.WHITE',view)

    def test_26_3_jei_bucket_lookup_no_removed_fluidutil(self):
        jei=self.source('26.3','jei/BuildCraftJeiPlugin.java')
        self.assertIn('bucketFluid.getFluid().getBucket()',jei)
        self.assertNotIn('net.neoforged.neoforge.fluids.FluidUtil',jei)
        self.assertIn('addRecipeClickArea',jei)
        self.assertIn('new AdvancedCraftingRecipeTransferHandler()',jei)
        self.assertIn('new AutoWorkbenchRecipeTransferHandler()',jei)
        self.assertIn('new HeatExchangeCategory(guiHelper)',jei)

    def test_26_3_transformer_native_capability_fallback(self):
        src=self.source('26.3','CompatCapTransfromer.java')
        self.assertIn('ResourceHandler<FluidResource>',src)
        self.assertIn('Capabilities.Fluid.BLOCK',src)
        self.assertIn('provider.getLevel().getCapability(',src)
        self.assertIn('registerFluidCapFallback(',src)
        self.assertIn('CapUtil.CAP_FLUIDS',src)  # legacy adapters from lib can still call through
        self.assertNotIn('import net.neoforged.neoforge.fluids.capability.IFluidHandler;',src)
        old=self.source('26.1.2','CompatCapTransfromer.java')
        self.assertIn('IFluidHandler',old)

    def test_native_263_compat_capability_runtime(self):
        source=self.outputs['26.3']/PREFIX/'CompatCapTransfromer.java'
        result=execute(source)
        self.assertIn('18 assertions PASS',result)
        print(result,flush=True)

    def test_26_2_old_jade_and_jei_remain_untouched(self):
        jade=self.source('26.2','jade/BuildCraftJadePlugin.java')
        self.assertIn('IFluidHandler',jade)
        self.assertIn('IItemHandler',jade)
        jei=self.source('26.2','jei/BuildCraftJeiPlugin.java')
        self.assertIn('FluidUtil.getFilledBucket',jei)

    def test_java_syntax_for_all_compat_versions(self):
        result=parse_sources([self.outputs[v]/PREFIX for v in ('26.1.2','26.2','26.3')])
        self.assertIn('0 errors',result)
        print(result,flush=True)

if __name__=='__main__':unittest.main()
