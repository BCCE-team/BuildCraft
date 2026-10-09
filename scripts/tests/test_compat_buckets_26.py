#!/usr/bin/env python3
"""26.X compat and bucket-model contracts; legacy materialization is frozen."""
from __future__ import annotations

import hashlib
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
    _buildcraftenergy_bucket_legacy_sprite,
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

    def hashes(self,ver):
        return {p.relative_to(self.outputs[ver]).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
                for p in (self.outputs[ver]/PREFIX).rglob('*.java')}

    def test_all_26_1_2_compat_java_byte_exact(self):
        manifest=json.loads((ROOT/'build-config/materialized-baselines/26.1.2-neoforge.json').read_text())['files']
        expected={n:v for n,v in manifest.items() if n.startswith(PREFIX+'/') and n.endswith('.java')}
        self.assertEqual(11,len(expected))
        self.assertEqual(expected,self.hashes('26.1.2'))

    def test_all_26_2_compat_java_byte_exact(self):
        manifest=json.loads((ROOT/'build-config/materialized-baselines/26.2-compat.json').read_text())
        self.assertEqual(11,manifest['file_count'])
        self.assertEqual(manifest['files'],self.hashes('26.2'))

    def test_original_26_1_2_bucket_definition_bytes(self):
        # This exact expected text is the 26.1.2 definition from before the patch.
        canonical={
            'model': {
                'type':'neoforge:fluid_container',
                'textures':{'particle':'minecraft:item/bucket','base':'minecraft:item/bucket','fluid':'neoforge:item/mask/bucket_fluid'},
                'fluid':'buildcraftenergy:oil','flip_gas':True,'apply_fluid_luminosity':False
            }
        }
        expected=json.dumps(canonical,indent=2,ensure_ascii=False)+'\n'
        actual=_dynamic_fluid_bucket_client_item_1_21_11('buildcraftenergy:oil',minecraft='26.1.2')
        self.assertEqual(expected.encode('utf-8'),actual.encode('utf-8'))

    def test_26_2_and_26_3_use_original_full_bucket_sprites(self):
        for ver in ('26.2','26.3'):
            for family in (
                'oil','oil_dense','oil_distilled','oil_heavy','oil_residue',
                'fuel_dense','fuel_gaseous','fuel_light','fuel_mixed_light','fuel_mixed_heavy',
            ):
                for heat, fluid_suffix in (('cool',''),('hot','_heat_1'),('searing','_heat_2')):
                    item=f'{family}/{heat}_bucket'
                    fluid='buildcraftenergy:'+family+fluid_suffix
                    with self.subTest(version=ver,item=item):
                        definition=json.loads(_dynamic_fluid_bucket_client_item_1_21_11(fluid,minecraft=ver))['model']
                        model=json.loads(_buildcraftenergy_bucket_generated_model_1_21_11(
                            '/src/main/resources/assets/buildcraftenergy/models/item/'+item+'.json',minecraft=ver))
                        self.assertEqual({'type':'minecraft:model','model':'buildcraftenergy:item/'+item},definition)
                        self.assertEqual('minecraft:item/generated',model['parent'])
                        self.assertEqual(_buildcraftenergy_bucket_legacy_sprite(item),model['textures']['layer0'])
                        self.assertEqual(model['textures']['layer0'],model['textures']['particle'])

    def test_pixel_art_buckets_have_original_complete_silhouette(self):
        from PIL import Image
        assets=ROOT/'source-shared/src/main/resources/assets/buildcraftenergy/textures'
        sprites={_buildcraftenergy_bucket_legacy_sprite(family+'/cool_bucket')
                 for family in ('oil','oil_dense','oil_distilled','oil_heavy','oil_residue',
                                'fuel_dense','fuel_gaseous','fuel_light','fuel_mixed_light','fuel_mixed_heavy')}
        self.assertEqual(10,len(sprites))
        for sprite in sprites:
            with self.subTest(sprite=sprite):
                relative=sprite.split(':',1)[1]+'.png'
                with Image.open(assets/relative) as image:
                    self.assertEqual((16,16),image.size)
                    self.assertGreaterEqual(sum(v>0 for v in image.convert('RGBA').getchannel('A').getdata()),100)
        # Old 26.1.2 model remains byte-for-byte unchanged, including the dynamic fluid mask.
        original=json.loads(_dynamic_fluid_bucket_client_item_1_21_11('buildcraftenergy:oil',minecraft='26.1.2'))['model']
        self.assertEqual('neoforge:fluid_container',original['type'])
        self.assertEqual('neoforge:item/mask/bucket_fluid',original['textures']['fluid'])
        self.assertEqual({'parent':'minecraft:item/generated',
                          'textures':{'layer0':'minecraft:item/bucket','particle':'minecraft:item/bucket'}},
            json.loads(_buildcraftenergy_bucket_generated_model_1_21_11(
                '/src/main/resources/assets/buildcraftenergy/models/item/oil/cool_bucket.json',minecraft='26.1.2')))

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
