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
        self.assertIn('tintIndex, shade ? null : Direction.UP, lightEmission, true',source)
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



# ---- Facade geometry cardinality and UV rotation ----

FACADE_ROTATION_ROOT = Path(__file__).resolve().parents[2]
FACADE_ROTATION_VECTORS = {
    'DOWN': (0, -1, 0), 'UP': (0, 1, 0),
    'NORTH': (0, 0, -1), 'SOUTH': (0, 0, 1),
    'WEST': (-1, 0, 0), 'EAST': (1, 0, 0),
}


def FACADE_ROTATION_rotate(vec, source, target):
    """The axis/quarter-turn mapping of MutableQuad.rotate and MutableVertex.rotate*_90."""
    if source == target:
        return vec
    x, y, z = vec
    sx, sy, sz = FACADE_ROTATION_VECTORS[source]
    tx, ty, tz = FACADE_ROTATION_VECTORS[target]
    axis_s = next(i for i, component in enumerate((sx, sy, sz)) if component)
    axis_t = next(i for i, component in enumerate((tx, ty, tz)) if component)
    if axis_s == axis_t:
        if axis_s == 0 or axis_s == 2:
            return (-x, y, -z)
        return (-x, -y, z)
    if axis_s == 0 and axis_t == 1:
        k = sx * ty
        return (-k * y, k * x, z)
    if axis_s == 0 and axis_t == 2:
        k = sx * tz
        return (-k * z, y, k * x)
    if axis_s == 1 and axis_t == 0:
        k = -sy * tx
        return (-k * y, k * x, z)
    if axis_s == 1 and axis_t == 2:
        k = sy * tz
        return (x, -k * z, k * y)
    if axis_s == 2 and axis_t == 0:
        k = -sz * tx
        return (-k * z, y, k * x)
    if axis_s == 2 and axis_t == 1:
        k = -sz * ty
        return (x, -k * z, k * y)
    raise AssertionError((source, target))


class FacadeRotationPort(unittest.TestCase):
    def test_all_36_direction_pairs(self):
        for src, vector in FACADE_ROTATION_VECTORS.items():
            for dst, target in FACADE_ROTATION_VECTORS.items():
                with self.subTest(src=src, dst=dst):
                    self.assertEqual(target, FACADE_ROTATION_rotate(vector, src, dst))

    def test_216_face_rotations_remain_cardinal(self):
        for face, vector in FACADE_ROTATION_VECTORS.items():
            for src in FACADE_ROTATION_VECTORS:
                for dst in FACADE_ROTATION_VECTORS:
                    with self.subTest(face=face, src=src, dst=dst):
                        self.assertIn(FACADE_ROTATION_rotate(vector, src, dst), FACADE_ROTATION_VECTORS.values())

    def test_materialized_sources_cover_all_targets(self):
        paths = [
            'source-families/old/src/main/java/buildcraft/lib/client/model/MutableQuad.java',
            'source-families/1.21.X/src/main/java/buildcraft/lib/client/model/MutableQuad.java',
            'source-families/26.X/src/main/java/buildcraft/lib/client/model/MutableQuad.java',
            'source-downports/26.X/26.1.2/neoforge/src/main/java/buildcraft/lib/client/model/MutableQuad.java',
            'source-downports/26.X/26.2/neoforge/src/main/java/buildcraft/lib/client/model/MutableQuad.java',
        ]
        for rel in paths:
            with self.subTest(rel=rel):
                code = (FACADE_ROTATION_ROOT / rel).read_text(encoding='utf-8')
                self.assertIn('face = rotateFace(face, from, to)', code)
                self.assertIn('probe.rotate(from, to, 0, 0, 0)', code)
                self.assertIn('if (face != null)', code)
                self.assertIn('DirectionCompat.nearest(', code)
        self.assertIn('Direction.getNearest(', (
            FACADE_ROTATION_ROOT / 'source-downports/1.21.X/1.21.1/family/src/main/java/buildcraft/lib/misc/DirectionCompat.java'
        ).read_text())

    def test_blocker_uv_fix(self):
        code = (FACADE_ROTATION_ROOT / 'source-shared/src/main/resources/assets/buildcrafttransport/models/plugs/blocker.json').read_text()
        self.assertIn('"north": { "uv": [ 4, 4, 2, 12 ]', code)


if __name__ == '__main__':
    unittest.main()
