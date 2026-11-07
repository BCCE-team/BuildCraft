#!/usr/bin/env python3
"""Cross-target engine wrench, MJ Dynamo and six-sided quarry lattice regressions."""
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
from dynamo_rotation_fixture import execute as execute_rotation, extract_method
import subprocess
from all_engine_wrench_fixture import execute as execute_wrench
from quarry_lattice_fixture import execute as execute_quarry

TARGETS=('1.19.2-forge','1.20.1-forge','1.21.1-neoforge','1.21.11-neoforge',
         '26.1.2-neoforge','26.2-neoforge','26.3-neoforge')
FILES={
 'dynamo':'src/main/java/buildcraft/energy/block/BlockDynamoMJ.java',
 'engine':'src/main/java/buildcraft/lib/engine/TileEngineBase_BC8.java',
 'engine_block':'src/main/java/buildcraft/lib/engine/BlockEngineBase_BC8.java',
 'stone_engine':'src/main/java/buildcraft/energy/tile/TileEngineStone_BC8.java',
 'wrench_item':'src/main/java/buildcraft/core/item/ItemWrench.java',
 'creative_engine':'src/main/java/buildcraft/core/blockEntity/TileEngineCreative.java',
 'iron_engine':'src/main/java/buildcraft/energy/tile/TileEngineIron_BC8.java',
 'fe_engine':'src/main/java/buildcraft/energy/tile/TileEngineFE.java',
 'dynamo_engine':'src/main/java/buildcraft/energy/tile/TileDynamoMJ.java',
 'quarry':'src/main/java/buildcraft/builders/client/render/RenderQuarry.java',
 'quarry_tile':'src/main/java/buildcraft/builders/tile/TileQuarry.java',
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

    def test_all_engines_use_manual_wrench_rotation(self):
        for version in TARGETS:
            with self.subTest(version=version):
                block = self.text(version, 'engine_block')
                self.assertIn('return engine.attemptManualRotation();', block)
                self.assertNotIn('return engine.attemptRotation();', block)
                self.assertIn('if (world.isClientSide', block)
                self.assertIn('return InteractionResult.SUCCESS;', block)
                self.assertIn('engine.rotateIfInvalid();', block)
                self.assertIn('ICustomRotationHandler', block)

    def test_all_five_engine_wrench_paths_execute_in_java(self):
        unique = {self.effective[target]['engine_block'].read_bytes():
                  self.effective[target]['engine_block'] for target in TARGETS}
        for source in unique.values():
            with self.subTest(source=source):
                self.assertIn('145 assertions PASS', execute_wrench(source))

    def test_stirling_engine_gui_defers_to_wrench_on_all_targets(self):
        # Existing manual-rotation tests start at the rotation handler. They miss the
        # right-click dispatch bug where the GUI consumes an interaction first.
        for version in TARGETS:
            with self.subTest(version=version):
                stone = self.text(version, 'stone_engine')
                wrench = self.text(version, 'wrench_item')
                self.assertIn('BlockInteractionRuntime.rotate(', wrench)
                self.assertIn('return engine.attemptManualRotation();', self.text(version, 'engine_block'))
                guard = 'if (WrenchUtil.isWrench(player.getItemInHand(hand))) {'
                start = stone.index('public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit)')
                body = stone[start:]
                self.assertIn(guard, body)
                self.assertLess(body.index(guard), body.index('openScreen(') if 'openScreen(' in body else body.index('openMenu('))
                self.assertIn('return InteractionResult.PASS;', body[body.index(guard):body.index('openScreen(') if 'openScreen(' in body else body.index('openMenu(')])

    def test_creative_wrench_click_modes_execute_in_java(self):
        # Compile the actual materialized click handler, with only Minecraft dependencies
        # represented by small test doubles. An ordinary wrench click cycles MJ/t, while
        # sneak-wrench MUST return PASS so ItemWrench can rotate the engine instead.
        variants = {}
        for version in TARGETS:
            java = self.text(version, 'creative_engine')
            method = extract_method(java, 'public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {')
            variants.setdefault(method, []).append(version)
        for method, versions in variants.items():
            with self.subTest(versions=versions):
                harness = '''
import java.util.*;
enum InteractionResult { SUCCESS, PASS }
enum InteractionHand { MAIN_HAND }
class BlockHitResult { }
class ItemStack { final boolean wrench; ItemStack(boolean wrench) { this.wrench=wrench; } }
class WrenchUtil { static boolean isWrench(ItemStack item) { return item.wrench; } }
class Component { static Component translatable(String key, long value) { return new Component(); } }
class Level { boolean isClientSide; boolean isClientSide() { return isClientSide; } }
class Player {
    ItemStack held; boolean shift; int messages;
    Player(ItemStack item) { held=item; }
    ItemStack getItemInHand(InteractionHand hand) { return held; }
    boolean isShiftKeyDown() { return shift; }
    void displayClientMessage(Component message, boolean overlay) { messages++; }
    void sendOverlayMessage(Component message) { messages++; }
}
public class CreativeWrenchClickHarness {
    final Level level = new Level();
    static final long[] outputs = {1,2,4,8,16,32,64,128,256};
    static final int NET_RENDER_DATA = 1;
    int currentOutputIndex, changed, updates, assertions;
    void setChanged() { changed++; }
    void sendNetworkUpdate(int id) { updates++; }
    void require(boolean condition) { assertions++; if (!condition) throw new AssertionError("assertion " + assertions); }
''' + method + '''
    void run() {
        Player player = new Player(new ItemStack(true));
        BlockHitResult hit = new BlockHitResult();
        level.isClientSide=false;
        require(onActivated(player, InteractionHand.MAIN_HAND, hit) == InteractionResult.SUCCESS);
        require(currentOutputIndex == 1 && changed == 1 && updates == 1 && player.messages == 1);
        player.shift=true;
        for (int i=0;i<3;i++) {
            require(onActivated(player, InteractionHand.MAIN_HAND, hit) == InteractionResult.PASS);
            require(currentOutputIndex == 1 && changed == 1 && updates == 1 && player.messages == 1);
        }
        player.shift=false;
        for (int i=0;i<9;i++) {
            require(onActivated(player, InteractionHand.MAIN_HAND, hit) == InteractionResult.SUCCESS);
        }
        require(currentOutputIndex == 1 && changed == 10 && updates == 10 && player.messages == 10);
        player.held=new ItemStack(false);
        require(onActivated(player, InteractionHand.MAIN_HAND, hit) == InteractionResult.PASS);
        require(currentOutputIndex == 1 && changed == 10);
        player.held=new ItemStack(true);
        level.isClientSide=true;
        require(onActivated(player, InteractionHand.MAIN_HAND, hit) == InteractionResult.SUCCESS);
        require(currentOutputIndex == 1 && changed == 10 && updates == 10);
        player.shift=true;
        require(onActivated(player, InteractionHand.MAIN_HAND, hit) == InteractionResult.PASS);
        require(currentOutputIndex == 1 && changed == 10 && updates == 10);
        System.out.println(assertions + " creative-wrench assertions PASS");
    }
    public static void main(String[] args) { new CreativeWrenchClickHarness().run(); }
}
'''
                with tempfile.TemporaryDirectory(prefix='bc-creative-engine-wrench-') as tmp:
                    java_file=Path(tmp)/'CreativeWrenchClickHarness.java'
                    java_file.write_text(harness, encoding='utf-8')
                    compile_result=subprocess.run(['javac','--release','17',str(java_file)],capture_output=True,text=True,timeout=35)
                    self.assertEqual(0,compile_result.returncode,compile_result.stderr)
                    run_result=subprocess.run(['java','-cp',tmp,'CreativeWrenchClickHarness'],capture_output=True,text=True,timeout=20)
                    self.assertEqual(0,run_result.returncode,run_result.stderr)
                    self.assertIn('creative-wrench assertions PASS',run_result.stdout)

    def test_normal_and_sneak_wrench_dispatch_for_all_engine_types(self):
        # The ordinary and shifted wrench path must both reach ItemWrench.useOn
        # for Redstone, Stirling, Combustion, FE and MJ Dynamo, on all 7 targets.
        for version in TARGETS:
            with self.subTest(version=version):
                wrench=self.text(version,'wrench_item')
                self.assertIn('doesSneakBypassUse(', wrench)
                self.assertIn('BlockInteractionRuntime.rotate(', wrench)
                self.assertIn('return engine.attemptManualRotation();',self.text(version,'engine_block'))
                stone=self.text(version,'stone_engine')
                stone_handler=extract_method(stone,'public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {')
                self.assertIn('if (WrenchUtil.isWrench(player.getItemInHand(hand))) {',stone_handler)
                self.assertIn('return InteractionResult.PASS;',stone_handler)
                self.assertLess(stone_handler.index('WrenchUtil.isWrench('),stone_handler.index('openMenu(') if 'openMenu(' in stone_handler else stone_handler.index('openScreen('))
                # The remaining engine GUIs must give their item interaction back
                # to the wrench rather than swallowing it to open a menu.
                for name in ('iron_engine','fe_engine','dynamo_engine'):
                    handler=extract_method(self.text(version,name),'public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {')
                    self.assertIn('EntityUtil.getWrenchHand(player) != null',handler,(version,name))
                    self.assertIn('return InteractionResult.PASS;',handler,(version,name))
                    self.assertNotIn('isShiftKeyDown()',handler,(version,name))
                creative=extract_method(self.text(version,'creative_engine'),
                    'public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {')
                self.assertLess(creative.index('if (player.isShiftKeyDown())'),creative.index('currentOutputIndex++'))
                self.assertIn('return InteractionResult.PASS;',creative)

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




    def test_quarry_watchdog_discards_stale_iterator_bounds(self):
        for version in TARGETS:
            with self.subTest(version=version):
                tile=self.text(version,'quarry_tile')
                method=tile.split('private void rescanVisitedMiningColumns() {',1)[1]
                method=method.split('private boolean canIgnoreInFrameBox(',1)[0]
                guard=method.index('if (!boxIterator.contains(candidate)) {')
                visited=method.index('boolean visited = boxIterator.hasFinished()')
                move=method.index('boxIterator.moveTo(candidate);')
                self.assertLess(guard,visited)
                self.assertLess(visited,move)
                self.assertIn('boxIterator = createBoxIterator();',method[guard:visited])
                self.assertIn('if (!boxIterator.contains(candidate)) {\n                    continue;',method[guard:visited])

    def test_java_syntax_on_every_target(self):
        for version in TARGETS:
            with self.subTest(version=version):
                result=parse_sources([path.parent for path in self.effective[version].values()])
                self.assertIn('0 errors',result,result)

if __name__=='__main__': unittest.main()
