#!/usr/bin/env python3
"""MJ Dynamo wrench/facing and six-sided quarry lattice regression tests for all targets."""
from __future__ import annotations
import sys
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'scripts'),str(ROOT/'scripts/tests')]
from source_config import load_properties,target_layout
from source_layout import resolve_effective_source,_materialize_text_file
from minecraft_compat_fixture import parse_sources
from dynamo_rotation_fixture import execute as execute_rotation
from quarry_lattice_fixture import execute as execute_quarry

TARGETS=('1.19.2-forge','1.20.1-forge','1.21.1-neoforge','1.21.11-neoforge',
         '26.1.2-neoforge','26.2-neoforge','26.3-neoforge')
FILES={
 'dynamo':'src/main/java/buildcraft/energy/block/BlockDynamoMJ.java',
 'engine':'src/main/java/buildcraft/lib/engine/TileEngineBase_BC8.java',
 'quarry':'src/main/java/buildcraft/builders/client/render/RenderQuarry.java',
}

class DynamoAndQuarryAllTargets(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='bc-dynamo-quarry-targets-')
        cls.effective={}
        props=load_properties()
        for version in TARGETS:
            layout=target_layout(version,props)
            dest=Path(cls.temp.name)/version
            sources={}
            downport_roots=tuple(d for d in (layout.family_downport_root,layout.family_platform_downport_root) if d)
            for label,logical in FILES.items():
                source=resolve_effective_source(layout,props,logical)
                if source is None:raise AssertionError((version,logical))
                target=dest/logical
                _materialize_text_file(source,target,logical_relative=logical,
                    minecraft=props[f'target.{version}.deps.minecraft'],family=layout.family,
                    platform=layout.platform,preprocess=True,
                    native_source=any(source.is_relative_to(d) for d in downport_roots))
                sources[label]=target
            cls.effective[version]=sources

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def text(self,version,label): return self.effective[version][label].read_text(encoding='utf-8')

    def test_all_targets_rotate_dynamo_without_receiver(self):
        for version in TARGETS:
            with self.subTest(version=version):
                block=self.text(version,'dynamo')
                engine=self.text(version,'engine')
                self.assertIn('dynamo.attemptManualRotation()',block)
                self.assertTrue(
                    'if (world.isClientSide) return InteractionResult.SUCCESS;' in block
                    or 'if (world.isClientSide()) return InteractionResult.SUCCESS;' in block)
                self.assertIn('return attemptRotation(false);',engine)
                self.assertIn('return attemptRotation(true);',engine)
                self.assertIn('if (manual || isFacingReceiver(current))',engine)
                self.assertIn('if (manual) manuallySelectedDirection = true;',engine)

    def test_all_targets_keep_wrench_direction_until_player_changes_it(self):
        for version in TARGETS:
            with self.subTest(version=version):
                engine=self.text(version,'engine')
                self.assertIn('if (manuallySelectedDirection || (currentDirection != null && isFacingReceiver(currentDirection)))',engine)
                self.assertIn('manuallySelectedDirection = false;',engine)
                self.assertIn('manualDirection',engine)
                self.assertIn('markChunkDirty();',engine)
                self.assertIn('sendNetworkUpdate(NET_RENDER_DATA);',engine)
                self.assertIn('level.neighborChanged(',engine)
                if 'forge' in version and 'neoforge' not in version:
                    self.assertIn('nbt.getBoolean("manualDirection")',engine)
                    self.assertIn('nbt.putBoolean("manualDirection", true)',engine)
                else:
                    self.assertIn('bcData.readBoolean("manualDirection")',engine)
                    self.assertIn('bcData.writeBoolean("manualDirection", true)',engine)

    def test_rotation_methods_compile_and_exercise_real_code(self):
        unique_sources={self.effective[version]['engine'].read_bytes():self.effective[version]['engine'] for version in TARGETS}
        for source in unique_sources.values():
            with self.subTest(source=source):
                self.assertEqual('21 assertions PASS',execute_rotation(source))
        print('Rotation variants compiled:',len(unique_sources),flush=True)

    def test_all_targets_restore_original_drill_material_and_distinct_uv_regions(self):
        # The gantry retains its open frame lattice, but its cutting head uses
        # the dedicated opaque drill texture, with different cap/side UVs.
        frame_cap='new LaserData_BC8.LaserRow(sprite, 4, 4, 12, 12)'
        frame_side='new LaserData_BC8.LaserRow(sprite, 0, 4, 16, 12)'
        drill_cap='new LaserData_BC8.LaserRow(sprite, 6, 0, 10, 4)'
        drill_side='new LaserData_BC8.LaserRow(sprite, 0, 0, 16, 4)'
        for version in TARGETS:
            with self.subTest(version=version):
                quarry=self.text(version,'quarry')
                self.assertEqual(2,quarry.count('var sprite = BCBuildersSprites.QUARRY_FRAME;'))
                self.assertEqual(1,quarry.count('var sprite = BCBuildersSprites.QUARRY_DRILL;'))
                self.assertEqual(4,quarry.count(frame_cap))
                self.assertEqual(4,quarry.count(frame_side))
                self.assertEqual(2,quarry.count(drill_cap))
                self.assertEqual(1,quarry.count(drill_side))
                self.assertIn('DRILL = new LaserData_BC8.LaserType(capStart, start, middle, end, capEnd)',quarry)
                self.assertIn('FRAME_BOTTOM = new LaserData_BC8.LaserType(capStart, start, middle, end, capEnd)',quarry)
                self.assertTrue('RenderType.cutoutMipped()' in quarry or 'RenderCompat.cutout()' in quarry)
                for piece in ('LaserData_BC8.of(FRAME,','LaserData_BC8.of(FRAME_BOTTOM,','LaserData_BC8.of(DRILL,'):
                    self.assertIn(piece,quarry)
                for suffix in ('ID','ID+1','ID+2','ID+3','ID+4'):
                    self.assertIn('1 / 16D, true, true, 0, '+suffix+')',quarry)
                # Solid cutting head doesn't need coplanar reverse windings.
                self.assertIn('1 / 16D, true, false, 0, ID+5)',quarry)
                self.assertNotIn('1 / 32D, true, true, 0, ID+5)',quarry)
                self.assertEqual(8 / 16, .5)
                self.assertEqual(4 / 16, .25)

    def test_original_quarry_sprite_artwork_is_present_and_distinct(self):
        import struct
        folder=ROOT/'source-shared/src/main/resources/assets/buildcraftbuilders/textures/blocks'
        frame=(folder/'frame/default.png').read_bytes()
        drill=(folder/'quarry/drill.png').read_bytes()
        self.assertNotEqual(frame,drill)
        for pixels in (frame,drill):
            self.assertEqual(b'\x89PNG\r\n\x1a\n',pixels[:8])
            self.assertEqual((16,16),struct.unpack('>II',pixels[16:24]))
        sprite=(ROOT/'source-shared/src/main/java/buildcraft/builders/BCBuildersSprites.java').read_text()
        self.assertIn('QUARRY_FRAME = getHolder("blocks/frame/default")',sprite)
        self.assertIn('QUARRY_DRILL = getHolder("blocks/quarry/drill")',sprite)

    def test_actual_laser_row_tessellation_covers_all_six_faces(self):
        source=ROOT/'source-families/26.X/src/main/java/buildcraft/lib/client/render/laser/CompiledLaserRow.java'
        self.assertTrue(execute_quarry(source).endswith('assertions PASS'))




    def test_java_syntax_on_every_target(self):
        for version in TARGETS:
            with self.subTest(version=version):
                result=parse_sources([path.parent for path in self.effective[version].values()])
                self.assertIn('0 errors',result,result)

if __name__=='__main__': unittest.main()
