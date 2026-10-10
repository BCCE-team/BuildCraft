#!/usr/bin/env python3
"""26.X transport source ownership, transfer behavior and downport contracts."""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
sys.path.insert(0,str(ROOT/'scripts/tests'))
from source_config import load_properties,target_layout
from source_layout import effective_source_files,_materialize_text_file
from minecraft_compat_fixture import parse_sources
from transport_26_fixture import execute

PREFIX='src/main/java/buildcraft/transport'

class Transport26EffectiveContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory(prefix='transport26-effective-')
        cls.targets={}
        properties=load_properties()
        for ver in ('26.1.2','26.2','26.3'):
            target=ver+'-neoforge';layout=target_layout(target,properties)
            downs=tuple(root for root in (layout.family_downport_root,layout.family_platform_downport_root) if root)
            out=Path(cls.tmp.name)/ver
            for rel,src in sorted(effective_source_files(layout,properties,PREFIX).items()):
                _materialize_text_file(src,out/rel,logical_relative=rel,
                    minecraft=properties[f'target.{target}.deps.minecraft'],family=layout.family,
                    platform=layout.platform,preprocess=True,
                    native_source=any(src.is_relative_to(p) for p in downs))
            cls.targets[ver]=out

    @classmethod
    def tearDownClass(cls):cls.tmp.cleanup()

    def java(self,ver,rel):return (self.targets[ver]/PREFIX/rel).read_text(encoding='utf-8')

    def test_2612_transport_sources_materialize(self):
        root = self.targets["26.1.2"] / PREFIX
        for relative in ("BCTransport.java", "pipe/flow/PipeFlowFluids.java"):
            with self.subTest(source=relative):
                self.assertTrue((root / relative).is_file())

    def test_26_2_legacy_transfer_boundaries(self):
        root = self.targets["26.2"] / PREFIX
        self.assertTrue((root / "BCTransport.java").is_file())
        self.assertFalse((root / "compat/NativeFluidStorage263.java").exists())
        self.assertFalse((root / "compat/NativeEnergyStorage263.java").exists())

    def test_26_3_exports_native_transfer_capabilities(self):
        text=self.java('26.3','BCTransport.java')
        self.assertIn('Capabilities.Fluid.BLOCK',text)
        self.assertIn('Capabilities.Energy.BLOCK',text)
        self.assertIn('fluid.resourceHandler(side)',text)
        self.assertIn('energy.resourceHandler(side)',text)
        self.assertNotIn('registerBlockEntity(event, CapUtil.CAP_FLUIDS, pipeHolderType)',text)

    def test_26_3_fluid_transfer_preserves_flow_and_render(self):
        text=self.java('26.3','pipe/flow/PipeFlowFluids.java')
        for required in ('adjacentFluidStorage(', 'ResourceHandler<FluidResource>',
                         'Transaction.open(transaction)', 'transferJournal.record()',
                         'section.ticksInDirection = COOLDOWN_OUTPUT',
                         'section.ticksInDirection = COOLDOWN_INPUT',
                         'center.drainInternal(filled, true)'):
            self.assertIn(required,text)
        self.assertNotIn('StorageAdapters.fromNativeFluids',text)

    def test_26_3_fe_mj_paths(self):
        text=self.java('26.3','pipe/flow/PipeFlowForgeEnergy.java')
        self.assertIn('Capabilities.Energy.BLOCK',text)
        self.assertIn('NativeEnergyStorage263',text)
        self.assertIn('Transaction.open(context)',text)
        self.assertIn('section.receiveEnergy(amount, false)',text)
        self.assertIn('transferJournal.record()',text)
        power=self.java('26.3','pipe/flow/PipeFlowPower.java')
        self.assertIn('new NativeEnergyStorage263(fe)',power)
        self.assertIn('new NativeEnergyStorage263(external)',power)

    def test_native_transfers_work_with_root_and_nested_transactions(self):
        native=self.targets['26.3']/PREFIX/'compat'
        result=execute(native/'NativeFluidStorage263.java', native/'NativeEnergyStorage263.java')
        self.assertIn('51 assertions PASS',result)
        print(result,flush=True)

    def test_26_3_schematic_handles_mutable_item_remainder(self):
        source=self.java('26.3','pipe/SchematicBlockPipe.java')
        self.assertIn('ItemStacksResourceHandler',source)
        self.assertIn('ItemAccess.forHandlerIndexStrict(',source)
        self.assertIn('stackHolder.getResource(0)',source)
        self.assertIn('tx.commit()',source)
        self.assertNotIn('IFluidHandler.FluidAction',source)

    def test_26_3_pipe_material_and_attachments(self):
        source=self.java('26.3','client/model/ModelPipeNative2612.java')
        self.assertIn('new BakedQuad.MaterialInfo(sprite',source)
        self.assertIn('Sheets.translucentBlockItemGlintSheet()',source)
        self.assertIn('Sheets.cutoutBlockItemGlintSpecialSheet()',source)
        self.assertIn('tintIndex, shade ? null : Direction.UP, lightEmission, ambientOcclusion',source)
        self.assertIn('material(sprite, translucentLayer, -1, true, 0, true)',source)
        self.assertIn('!translucentLayer || isOuterGlassFace(quad, face))',source)
        self.assertFalse((self.targets['26.1.2']/PREFIX/'internal/pipe/FluidAction.java').exists())
        self.assertTrue((self.targets['26.3']/PREFIX/'internal/pipe/FluidAction.java').exists())

    def test_no_removed_neoforge_imports_in_transport_26_3(self):
        removed=('net.neoforged.neoforge.items.IItemHandler',
                 'net.neoforged.neoforge.energy.IEnergyStorage',
                 'net.neoforged.neoforge.fluids.capability.IFluidHandler',
                 'net.neoforged.neoforge.fluids.FluidUtil')
        for path in (self.targets['26.3']/PREFIX).rglob('*.java'):
            text=path.read_text(encoding='utf-8')
            for rem in removed:
                with self.subTest(source=path.name,api=rem):self.assertNotIn('import '+rem,text)

    def test_transport_26_java_syntax(self):
        roots=[self.targets[x]/PREFIX for x in ('26.2','26.3')]
        result=parse_sources(roots)
        self.assertIn('0 errors',result)
        print(result,flush=True)

if __name__=='__main__':unittest.main()
