#!/usr/bin/env python3
"""26.3 API-removal regression: isolated NeoForge 26.3 transfer bridges and oil Feature codec.

These tests verify source selection and resource compatibility, not full Minecraft compilation.
"""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_layout import resolve_effective_source, target_layout, load_properties
from source_layout import _materialize_text_file
from transforms.neoforge263 import relocate_removed_neoforge_apis

REMOVED = (
    'net.neoforged.neoforge.items.IItemHandler',
    'net.neoforged.neoforge.fluids.capability.IFluidHandler',
    'net.neoforged.neoforge.energy.IEnergyStorage',
    'net.neoforged.neoforge.fluids.FluidUtil',
    'net.neoforged.neoforge.fluids.IFluidTank',
    'net.neoforged.neoforge.items.wrapper.InvWrapper',
)

class Compile263Regression(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import tempfile
        cls.tmp = tempfile.TemporaryDirectory(prefix='bcce-263-api-')
        cls.props = load_properties()
        cls.sources = {}
        selected = (
            'lib/compat/transfer/TransferInterop.java',
            'lib/platform/storage/PlatformStorage.java',
            'lib/misc/CapUtil.java',
            'lib/fluid/Tank.java',
            'energy/BCEnergyWorldGen.java',
            'energy/generation/features/OilGenFeature.java',
            'energy/generation/features/OilFeatureConfiguration.java',
            'builders/snapshot/FakeWorld.java',
        )
        for ver in ('26.2','26.3'):
            layout=target_layout(ver+'-neoforge',cls.props)
            downs=tuple(r for r in (layout.family_downport_root,layout.family_platform_downport_root) if r)
            for rel in selected:
                logical='src/main/java/buildcraft/'+rel
                source=resolve_effective_source(layout,cls.props,logical)
                assert source is not None,logical
                output=Path(cls.tmp.name)/ver/logical
                _materialize_text_file(source,output,logical_relative=logical,
                  minecraft=ver,family=layout.family,platform=layout.platform,
                  preprocess=True,native_source=any(source.is_relative_to(d) for d in downs))
                cls.sources[ver,rel] = output.read_text(encoding='utf-8')

    @classmethod
    def tearDownClass(cls): cls.tmp.cleanup()

    def test_api_relocations_only_on_263(self):
        sample='\n'.join('import '+s+';' for s in REMOVED)
        self.assertEqual(sample, relocate_removed_neoforge_apis(sample,minecraft='26.2',relative='Any.java'))
        new=relocate_removed_neoforge_apis(sample,minecraft='26.3',relative='Any.java')
        for old in REMOVED: self.assertNotIn(old,new)
        self.assertIn('buildcraft.lib.compat.neoforge263.',new)

    def test_262_previous_api_preserved(self):
        for rel in ('lib/compat/transfer/TransferInterop.java','lib/misc/CapUtil.java'):
            self.assertIn('net.neoforged.neoforge.items.IItemHandler;',self.sources['26.2',rel])
            self.assertNotIn('buildcraft.lib.compat.neoforge263.',self.sources['26.2',rel])

    def test_263_transfer_bridge_not_legacy_loader_api(self):
        for rel in ('lib/compat/transfer/TransferInterop.java','lib/misc/CapUtil.java',
                    'lib/platform/storage/PlatformStorage.java','lib/fluid/Tank.java'):
            src=self.sources['26.3',rel]
            for old in REMOVED: self.assertNotIn(old,src,rel)
        self.assertIn('TransferInterop.importEnergy(handler)',
                      self.sources['26.3','lib/platform/storage/PlatformStorage.java'])
        self.assertIn('TransferInterop.importFluids(modern)',
                      self.sources['26.3','lib/misc/CapUtil.java'])

    def test_263_only_java_contracts(self):
        base=ROOT/'source-families/26.X/src/main/java/buildcraft/lib/compat/neoforge263'
        classes=('items/IItemHandler.java','items/IItemHandlerModifiable.java',
           'items/SlotItemHandler.java','items/ItemHandlerCopySlot.java',
           'items/wrapper/CombinedInvWrapper.java','items/wrapper/InvWrapper.java',
           'fluids/IFluidHandler.java','fluids/IFluidHandlerItem.java',
           'fluids/IFluidTank.java','fluids/FluidUtil.java','energy/IEnergyStorage.java')
        for cls in classes:
            path=base/cls
            self.assertTrue(path.is_file(),cls)
            self.assertTrue(path.read_text().startswith('//? source if >=26.3'),cls)

    def test_263_worldgen_uses_feature_type_and_map_codec(self):
        wg=self.sources['26.3','energy/BCEnergyWorldGen.java']
        self.assertIn('"minecraft:worldgen/feature_type"',wg)
        self.assertIn('BCDeferredRegister<MapCodec<? extends Feature>>',wg)
        feature=self.sources['26.3','energy/generation/features/OilGenFeature.java']
        self.assertIn('implements Feature',feature)
        self.assertIn('MapCodec<OilGenFeature>',feature)
        self.assertNotIn('FeaturePlaceContext',feature)
        config=self.sources['26.3','energy/generation/features/OilFeatureConfiguration.java']
        self.assertIn('MapCodec<OilFeatureConfiguration> CODEC = RecordCodecBuilder.mapCodec',config)
        self.assertNotIn('implements FeatureConfiguration',config)
        old=self.sources['26.2','energy/generation/features/OilGenFeature.java']
        self.assertIn('extends Feature<OilFeatureConfiguration>',old)

    def test_263_fake_world_removed_262_hooks(self):
        old=self.sources['26.2','builders/snapshot/FakeWorld.java']
        new=self.sources['26.3','builders/snapshot/FakeWorld.java']
        self.assertIn('FuelValues fuelValues()',old)
        self.assertNotIn('FuelValues fuelValues()',new)
        self.assertNotIn('PotionBrewing',new)

    def test_263_oil_placement_inline(self):
        src=ROOT/'resource-src/26.X/26.3/data/buildcraftenergy/worldgen/placed_feature/oil_placed_feature.json'
        value=json.loads(src.read_text(encoding='utf-8'))
        self.assertEqual('buildcraftenergy:worldgen.feature.oil',value['feature']['type'])
        self.assertIn('oilStructureSetting',value['feature'])
        self.assertEqual('buildcraftenergy:oil', value['feature']['oilStructureSetting']['genOilState'])
        self.assertNotIsInstance(value['feature']['oilStructureSetting']['genOilState'], dict)
        self.assertNotIn('config',value['feature'])
        self.assertIn('placement',value)

    def test_263_neoforge_dependency_range_tracks_pinned_version(self):
        dep = self.props['target.26.3-neoforge.deps.neoforge']
        version_range = self.props['target.26.3-neoforge.neoforge.version_range']
        self.assertTrue(dep.startswith('26.3.'), dep)
        self.assertEqual(f'[{dep},)', version_range)
        self.assertNotEqual(dep, self.props['target.26.2-neoforge.deps.neoforge'])

if __name__=='__main__': unittest.main()
