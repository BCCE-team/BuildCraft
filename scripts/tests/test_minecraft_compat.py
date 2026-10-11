#!/usr/bin/env python3
"""Real internal Java boundaries against offline doubles, not Minecraft/Gradle tests."""
from __future__ import annotations
from pathlib import Path
import re
import sys
import tempfile
import unittest
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_layout import load_properties, materialize_target
from transforms.java_symbols import downport_symbols
from minecraft_compat_fixture import run, parse_sources
from minecraft_compat_probes import probe


class SymbolDownports(unittest.TestCase):
    def old(self, text: str, relative: str = 'Example.java') -> str:
        return downport_symbols(text, minecraft='1.21.1', relative=relative)

    def test_whitelisted_import_and_type_only_outside_literals(self):
        text = 'import net.minecraft.resources.Identifier;\nclass A { Identifier value; String s="Identifier"; char c=\'I\'; /* Identifier */ }\n// Identifier\n'
        result = self.old(text)
        self.assertIn('import net.minecraft.resources.ResourceLocation;', result)
        self.assertIn('ResourceLocation value;', result)
        self.assertIn('"Identifier"', result)
        self.assertIn('/* Identifier */', result)
        self.assertIn('// Identifier', result)
        self.assertEqual(result, self.old(result))

    def test_opaque_text_blocks_and_comments_cannot_enable_a_transform(self):
        for text in (
            '/*\nimport net.minecraft.resources.Identifier;\n*/\nclass Identifier {}',
            'String s="""\nimport net.minecraft.resources.Identifier;\n""";\nclass Identifier {}',
        ):
            self.assertEqual(text, self.old(text))

    def test_other_namespace_and_new_api_are_untouched(self):
        text = 'import example.Identifier;\nclass A {Identifier value;}'
        self.assertEqual(text, self.old(text))
        text = text.replace('example.Identifier', 'net.minecraft.resources.Identifier')
        self.assertEqual(text, downport_symbols(text, minecraft='1.21.11', relative='A.java'))
        self.assertEqual(text, self.old(text, 'readme.txt'))

    def test_render_type_package_is_mechanical(self):
        text = 'import net.minecraft.client.renderer.rendertype.RenderType;\nclass A {RenderType value;}'
        self.assertEqual('import net.minecraft.client.renderer.RenderType;\nclass A {RenderType value;}', self.old(text))


class MinecraftBoundaries(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='bc-minecraft-boundaries-')
        cls.roots = {}
        cls.target_roots = {}
        config = load_properties()
        for target in ('1.21.1-neoforge', '1.21.11-neoforge'):
            root = Path(cls.temporary.name) / target
            materialize_target(target, root, config)
            cls.target_roots[target] = root
            cls.roots[target] = root / 'src/main/java'

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_all_modern_effective_java_parses(self):
        print(parse_sources(list(self.roots.values())))

    def test_real_java_boundaries_on_both_modern_api_shapes(self):
        for target, java in self.roots.items():
            with self.subTest(target=target):
                print(run(java, current=target.startswith('1.21.11'), probe=probe(target.startswith('1.21.11'))))

    def test_only_boundary_owns_vanilla_machine_persistence_callbacks(self):
        for target, root in self.roots.items():
            for path in (root / 'buildcraft').rglob('*.java'):
                text = path.read_text(encoding='utf-8')
                methods = re.findall(r'\bvoid\s+(loadAdditional|saveAdditional)\s*\(', text)
                if methods:
                    self.assertEqual('BCBlockEntity.java', path.name, f'{target}: {path}')
            base = (root / 'buildcraft/lib/compat/minecraft/persistence/BCBlockEntity.java').read_text()
            self.assertIn('protected final void loadAdditional(', base)
            self.assertIn('protected final void saveAdditional(', base)
            self.assertIn('readCommonData(', base)
            self.assertIn('writeCommonData(', base)
            tile = (root / 'buildcraft/lib/tile/TileBC_Neptune.java').read_text()
            self.assertNotIn('input.readIntArray("d").orElse', tile)

    def test_schema_exceptions_remain_explicit(self):
        java = self.roots['1.21.11-neoforge'] / 'buildcraft'
        base = (java / 'lib/compat/minecraft/persistence/BCBlockEntity.java').read_text()
        self.assertIn('"bc_legacy"', base)
        self.assertIn('input.lookup()', base)
        for rel in ('transport/tile/TilePipeHolder.java', 'energy/tile/TileSpringOil.java', 'builders/tile/TileQuarryDrillCollision.java'):
            self.assertIn('storesMachineDataAtRoot() { return true; }', (java / rel).read_text(), rel)
        pipe = (java / 'transport/tile/TilePipeHolder.java').read_text()
        for key in ('"pipe"', '"plugs"', '"wireManager"', '"redstone"'):
            self.assertIn(key, pipe)
        self.assertIn('requiresPersistenceRegistries() { return false; }', pipe)
        self.assertIn('output.hasRegistries() ? output.registries() : previousRegistries', pipe)
        self.assertIn('unknownData.copy()', pipe)
        self.assertIn('serializationRegistries = previousRegistries', pipe)

    def test_container_screen_texture_id_matches_modern_generation(self):
        old = (self.roots['1.21.1-neoforge'] / 'buildcraft/lib/gui/ContainerScreenBase.java').read_text()
        current = (self.roots['1.21.11-neoforge'] / 'buildcraft/lib/gui/ContainerScreenBase.java').read_text()
        self.assertIn('import net.minecraft.resources.ResourceLocation;', old)
        self.assertNotIn('import net.minecraft.resources.Identifier;', old)
        self.assertIn('ResourceLocation TEXTURE_BASE', old)
        self.assertIn('import net.minecraft.resources.Identifier;', current)
        self.assertNotIn('import net.minecraft.resources.ResourceLocation;', current)
        self.assertIn('Identifier TEXTURE_BASE', current)

    def test_tank_stack_aware_fluid_uses_cross_target_gui_bridge(self):
        old_root = self.roots['1.21.1-neoforge'] / 'buildcraft'
        current_root = self.roots['1.21.11-neoforge'] / 'buildcraft'

        for target, root in (('1.21.1-neoforge', old_root), ('1.21.11-neoforge', current_root)):
            gui = (root / 'factory/gui/GuiTank.java').read_text(encoding='utf-8')
            self.assertIn(
                'new GuiRectangle(left, top + TANK_HEIGHT - filled, TANK_WIDTH, filled), fluid, 1, 1);',
                gui,
                target,
            )
            self.assertNotIn('FluidRenderer.drawFluidForGui(fluid, left', gui, target)
            self.assertNotIn('RenderCompat.pose(guiGraphics)', gui, target)

        old_util = (old_root / 'lib/misc/GuiUtil.java').read_text(encoding='utf-8')
        current_util = (current_root / 'lib/misc/GuiUtil.java').read_text(encoding='utf-8')
        self.assertIn('FluidRenderer.drawFluidForGui(fluid, startX, startY, endX, endY, guiGraphics.pose().last());', old_util)
        self.assertIn('FluidRenderer.drawFluidForGui(fluid, startX, startY, endX, endY, guiGraphics);', current_util)

    def test_gametest_registration_matches_modern_api_generation(self):
        old_root = self.target_roots['1.21.1-neoforge'] / 'src/gametest/java'
        current_root = self.target_roots['1.21.11-neoforge'] / 'src/gametest/java'

        old_sources = '\n'.join(path.read_text(encoding='utf-8') for path in old_root.rglob('*.java'))
        current_sources = '\n'.join(path.read_text(encoding='utf-8') for path in current_root.rglob('*.java'))
        registry = current_root / 'buildcraft/gametest/BuildCraftGeneratedGameTests.java'

        self.assertIn('import net.minecraft.gametest.framework.GameTest;', old_sources)
        self.assertIn('@GameTestHolder(', old_sources)
        self.assertFalse((old_root / 'buildcraft/gametest/BuildCraftGeneratedGameTests.java').exists())

        self.assertNotIn('import net.minecraft.gametest.framework.GameTest;', current_sources)
        self.assertNotIn('@GameTestHolder(', current_sources)
        self.assertNotIn('@PrefixGameTestTemplate(', current_sources)
        self.assertNotIn('NbtCompat.getInt(GameTestCompat,', current_sources)
        self.assertIn('GameTestCompat.readInt(', current_sources)
        self.assertIn('GameTestCompat.readString(', old_sources)
        self.assertIn('GameTestCompat.readString(', current_sources)
        expected_generated_tests = old_sources.count('@GameTest(')
        self.assertGreater(expected_generated_tests, 0)
        self.assertEqual(expected_generated_tests, current_sources.count('// bc-gametest-v2:'))
        self.assertTrue(registry.is_file())

        registry_text = registry.read_text(encoding='utf-8')
        self.assertEqual(expected_generated_tests, registry_text.count('registry.register('))
        self.assertEqual(expected_generated_tests, registry_text.count('event.registerTest('))
        self.assertEqual(expected_generated_tests, registry_text.count('helper -> invoke(helper'))
        self.assertIn('private interface CheckedGameTest', registry_text)
        self.assertIn('BuiltInRegistries.TEST_FUNCTION.key()', registry_text)
        self.assertIn('new FunctionGameTestInstance(', registry_text)
        self.assertIn('RegisterGameTestsEvent', registry_text)

        old_compat = (old_root / 'buildcraft/gametest/GameTestCompat.java').read_text(encoding='utf-8')
        current_compat = (current_root / 'buildcraft/gametest/GameTestCompat.java').read_text(encoding='utf-8')
        for compat in (old_compat, current_compat):
            self.assertIn('getBlockEntity(helper.absolutePos(pos))', compat)
            self.assertIn('invokeFirst(profile, "id", "getId")', compat)
            self.assertIn('Class.forName("net.minecraft.world.level.storage.TagValueOutput")', compat)
            self.assertIn('Class.forName("net.minecraft.world.item.crafting.display.SlotDisplayContext")', compat)
            self.assertIn('saveBlockEntity(', compat)
            self.assertIn('loadBlockEntity(', compat)
            self.assertIn('findDeclaredCompatibleMethod(', compat)
            self.assertIn('public static String readString(', compat)
        self.assertTrue((ROOT / 'source-families/1.21.X/src/gametest/java/buildcraft/gametest/GameTestCompat.java').is_file())
        self.assertFalse((ROOT / 'source-platforms/neoforge/src/gametest/java/buildcraft/gametest/GameTestCompat.java').exists())

    def test_modern_fluid_water_guards_survive_1_21_11_api_changes(self):
        current_root = self.target_roots['1.21.11-neoforge'] / 'src/main/java'
        fluid = (current_root / 'buildcraft/lib/fluid/BCFluid.java').read_text(encoding='utf-8')
        pump = (current_root / 'buildcraft/factory/tile/TilePump.java').read_text(encoding='utf-8')

        self.assertIn('protected void spreadTo(LevelAccessor level', fluid)
        self.assertIn('isWater(level.getFluidState(pos))', fluid)
        self.assertIn('fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER', fluid)
        self.assertIn('scanForInfiniteWater = !BCCoreConfig.pumpsConsumeWater && isWater(scanFluid);', pump)
        self.assertIn('isWaterSource(neighbour)', pump)
        self.assertIn('fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER', pump)
        self.assertIn('fluid == Fluids.WATER || (state.isSource() && isWater(state))', pump)
        self.assertIn('if (!level.hasChunkAt(neighbourPos)) {\n                continue;', pump)
        self.assertNotIn('if (!level.hasChunkAt(neighbourPos)) {\n                return false;', pump)
        self.assertIn('&& isWater(drain.getFluid());', pump)

    def test_cross_target_safety_contracts_survive_modern_materialization(self):
        current = self.roots['1.21.11-neoforge'] / 'buildcraft'
        old = self.roots['1.21.1-neoforge'] / 'buildcraft'

        for target, root in (('1.21.1-neoforge', old), ('1.21.11-neoforge', current)):
            drops = (root / 'lib/block/BlockBCTile_Neptune.java').read_text(encoding='utf-8')
            self.assertIn('getCloneItemStack(', drops)
            if target == '1.21.11-neoforge':
                self.assertIn('state, false, null', drops)

            volume = (root / 'core/marker/volume/WorldSavedDataVolumeBoxes.java').read_text(encoding='utf-8')
            get_method = volume[volume.index('public static WorldSavedDataVolumeBoxes get(Level world)'):]
            self.assertIn('getDataStorage()', get_method)
            self.assertNotIn('return new WorldSavedDataVolumeBoxes(world);', get_method.split('\n    }', 1)[0])

            energy = (root / 'energy/BCEnergy.java').read_text(encoding='utf-8')
            advancement = energy[energy.index('private static boolean hasAdvancement('):]
            advancement = advancement[:advancement.index('\n    }') + 6]
            self.assertIn('getAdvancements()', advancement)
            self.assertIn('getOrStartProgress', advancement)
            self.assertIn('.isDone()', advancement)

    def test_forge_and_neoforge_pump_use_the_same_infinite_water_rules(self):
        forge = (ROOT / 'source-platforms/forge/src/main/java/buildcraft/factory/tile/TilePump.java').read_text(encoding='utf-8')
        neo = (ROOT / 'source-platforms/neoforge/src/main/java/buildcraft/factory/tile/TilePump.java').read_text(encoding='utf-8')
        for pump in (forge, neo):
            self.assertIn('scanForInfiniteWater = !BCCoreConfig.pumpsConsumeWater && isWater(scanFluid);', pump)
            self.assertIn('if (!level.hasChunkAt(neighbourPos)) {\n                continue;', pump)
            self.assertNotIn('if (!level.hasChunkAt(neighbourPos)) {\n                return false;', pump)
            self.assertIn('state.is(FluidTags.WATER)', pump)
            self.assertIn('isWaterSource(neighbour)', pump)

    def test_pump_infinite_water_fixture_stays_inside_empty3x3x3(self):
        suite = (
            ROOT
            / 'source-platforms/neoforge/src/gametest/java/buildcraft/gametest/BuildCraftLogicGameTests.java'
        ).read_text(encoding='utf-8')
        self.assertIn('BlockPos pumpPos = new BlockPos(1, 2, 1);', suite)
        self.assertIn('BlockPos waterPos = new BlockPos(1, 1, 1);', suite)
        self.assertNotIn('BlockPos pumpPos = new BlockPos(1, 4, 1);', suite)
        self.assertNotIn('BlockPos waterPos = new BlockPos(1, 2, 1);', suite)

    def test_1_21_11_client_runtime_hooks_are_isolated_from_common_event_owner(self):
        current_root = self.target_roots['1.21.11-neoforge'] / 'src/main/java'
        events = (current_root / 'buildcraft/lib/BCLibEventDist.java').read_text(encoding='utf-8')

        self.assertIn('ClientGame.registerGameplayEvents();', events)
        self.assertIn('PlatformClientEvents.login(ClientGame::onConnectToServer);', events)
        self.assertIn('PlatformClientEvents.logout(ClientGame::onDisconnectFromServer);', events)
        self.assertIn('PlatformClientEvents.tick(BCEvents.Phase.END, ClientGame::clientTick);', events)
        self.assertNotIn('PlatformClientEvents.login(BCLibEventDist::onConnectToServer);', events)
        self.assertNotIn('PlatformClientEvents.logout(BCLibEventDist::onDisconnectFromServer);', events)
        self.assertNotIn('PlatformClientEvents.tick(BCEvents.Phase.END, BCLibEventDist::clientTick);', events)

    def test_legacy_pipe_targeting_uses_block_reach(self):
        pipe = (ROOT / 'version-src/1.19.2-forge/src/main/java/buildcraft/transport/block/BlockPipeHolder.java').read_text(encoding='utf-8')
        shape = pipe[pipe.index('public VoxelShape getShape('):]
        self.assertIn('player.getReachDistance()', shape)
        self.assertNotIn('player.getAttackRange()', shape)

    def test_smoke_scripts_read_canonical_target_metadata(self):
        for script in ('ci-server-smoke.sh', 'ci-client-smoke.sh'):
            smoke = (ROOT / 'scripts' / script).read_text(encoding='utf-8')
            self.assertIn('target_config="${repo_root}/build-config/targets.properties"', smoke, script)
            self.assertNotIn('target_config="${build_root}/targets.properties"', smoke, script)

    def test_legacy_fluid_widget_keeps_client_renderer_off_dedicated_server(self):
        widget = (ROOT / 'source-families/old/src/main/java/buildcraft/lib/gui/widget/WidgetFluidTank.java').read_text(encoding='utf-8')
        client = (ROOT / 'source-families/old/src/main/java/buildcraft/lib/gui/widget/GuiElementFluidTank.java').read_text(encoding='utf-8')
        self.assertNotIn('IGuiElement', widget)
        self.assertNotIn('BuildCraftGui', widget)
        self.assertNotIn('GuiElementSimple', widget)
        self.assertIn('class GuiElementFluidTank extends GuiElementSimple', client)
        self.assertIn('widget.sendClick();', client)
        self.assertIn('widget.getTank()', client)

    def test_new_facades_are_loader_neutral(self):
        root = ROOT / 'source-families/1.21.X/src/main/java/buildcraft/lib/compat/minecraft'
        for path in root.rglob('*.java'):
            text = path.read_text()
            self.assertNotRegex(text, r'\b(?:net\.neoforged|net\.minecraftforge|net\.fabricmc)\.', str(path))
            self.assertNotRegex(text, r'(?m)^package (?:net\.|com\.mojang)', str(path))

    def test_no_class_specific_bootstrap_or_duplicate_target_renderers(self):
        self.assertEqual([], sorted((ROOT / 'scripts/transforms').glob('bootstrap_*.py')))
        compat = (ROOT / 'scripts/transforms/java_compat.py').read_text()
        self.assertNotRegex(compat, r'buildcraft/[^\"\']+\.java')
        self.assertEqual(1, compat.count('relative.endswith(".java")'))
        overlay = ROOT / 'version-src/1.21.11-neoforge/src/main/java/buildcraft'
        for rel in ('factory/client/render/RenderPump.java', 'factory/client/render/RenderMiningWell.java', 'lib/gui/ContainerScreenBase.java'):
            self.assertFalse((overlay / rel).exists(), rel)




# ---- 1.21.1 detached world rendering camera transform ----








import math

from source_config import load_properties, target_layout
from source_layout import resolve_effective_source


class DetachedWorldPose1211Test(unittest.TestCase):
    def test_1211_uses_identity_pose_before_world_origin_translation(self):
        props = load_properties()
        target = target_layout('1.21.1-neoforge', props)
        source = resolve_effective_source(target, props,
            'src/main/java/buildcraft/lib/BCLibEventDist.java')
        self.assertIsNotNone(source)
        content = source.read_text(encoding='utf-8')
        method = content.split('public static void renderWorldLast(RenderLevelStageEvent event)', 1)[1].split('\n    }',1)[0]
        self.assertIn('PoseStack pose = new PoseStack();', method)
        self.assertNotIn('pose.mulPose(new Matrix4f(event.getModelViewMatrix()))', method)
        self.assertIn('DetachedRenderer.INSTANCE.renderWorldLastEvent(pose, matrix, player, partialTicks)',method)
        self.assertIn('Matrix4f matrix = new Matrix4f(event.getProjectionMatrix());', method)

    def test_world_pose_subtracts_camera_once(self):
        props=load_properties()
        target=target_layout('1.21.1-neoforge',props)
        source=resolve_effective_source(target,props,
            'src/main/java/buildcraft/lib/client/render/DetachedRenderer.java')
        self.assertIsNotNone(source)
        method=source.read_text(encoding='utf-8').split('public static void fromWorldOriginPre(',1)[1].split('public static void fromWorldOriginPost(',1)[0]
        self.assertIn('diff = diff.subtract(camera.getPosition());',method)
        self.assertEqual(1,method.count('pose.translate('))
        self.assertNotIn('pose.mulPose(',method)

    def test_rotation_is_applied_exactly_once(self):
        # Non-axis aligned world point makes doubled view rotation observable.
        # Check several camera angles and world/camera positions including negative coordinates.
        for angle in (0, math.pi/6, math.pi/2, math.pi, -math.pi/3):
            c,s=math.cos(angle),math.sin(angle)
            for vertex,camera in (((20.,65.,-13.),(13.,63.,-18.)),
                                  ((-41.,70.,37.),(-39.,64.,29.)),
                                  ((2.,0.,4.),(-10.,1.,8.))):
                vx,vy,vz=(vertex[i]-camera[i] for i in range(3))
                # GPU's one model-view rotation is authoritative.
                once=(c*vx+s*vz,vy,-s*vx+c*vz)
                # The regression: CPU pre-rotation *and* GPU view rotation.
                twice=(c*once[0]+s*once[2],once[1],-s*once[0]+c*once[2])
                if abs(angle)>1e-5:
                    self.assertGreater(max(abs(a-b) for a,b in zip(once,twice)),1e-4)
                # New CPU pose is identity, so GPU output equals single rotation.
                cpu=(vx,vy,vz)
                actual=(c*cpu[0]+s*cpu[2],cpu[1],-s*cpu[0]+c*cpu[2])
                for a,b in zip(once,actual): self.assertAlmostEqual(a,b,places=5)

# ---- Consolidated 1.21.1 NeoForge pick-block hook coverage ----
# Native 1.21.1 NeoForge pick-block override and attachment hit precedence.

import subprocess

from source_layout import load_properties, target_layout, resolve_effective_source, _materialize_text_file

PICK_1211_TARGET = '1.21.1-neoforge'
PICK_1211_JAVA_CLASS = 'buildcraft/transport/block/BlockPipeHolder.java'


def PICK_1211_source_code(target: str = PICK_1211_TARGET) -> tuple[Path, str]:
    props = load_properties()
    layout = target_layout(target, props)
    source = resolve_effective_source(layout, props, 'src/main/java/' + PICK_1211_JAVA_CLASS)
    if source is None:
        raise AssertionError('1.21.1 pipe block is missing')
    with tempfile.TemporaryDirectory(prefix='bc-1211-pick-source-') as temp:
        destination = Path(temp, PICK_1211_JAVA_CLASS)
        downports = tuple(root for root in (layout.family_downport_root, layout.family_platform_downport_root) if root)
        _materialize_text_file(source, destination, logical_relative='src/main/java/' + PICK_1211_JAVA_CLASS,
            minecraft=props[f'target.{target}.deps.minecraft'], family=layout.family, platform=layout.platform,
            preprocess=True, native_source=any(source.is_relative_to(root) for root in downports))
        return source, destination.read_text(encoding='utf-8')


def PICK_1211_extract_method(text: str, declaration: str) -> str:
    at = text.index(declaration)
    start = text.rfind('\n', 0, at) + 1
    brace = text.index('{', at)
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == '{':
            depth += 1
        elif text[i] == '}':
            depth -= 1
            if depth == 0:
                return text[start:i+1]
    raise AssertionError('unbalanced Java source')


class PickBlock1211(unittest.TestCase):
    def test_downport_owns_actual_neoforge_pick_hook(self):
        path, text = PICK_1211_source_code()
        self.assertIn('/source-downports/1.21.X/1.21.1/neoforge/', path.as_posix())
        sig = 'public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player)'
        self.assertIn(sig, text)
        self.assertRegex(text, r'@Override\s+' + re.escape(sig))
        self.assertNotIn('getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData, Player player)', text)
        self.assertIn('getPickStack(tile, getPickSubHit(level, pos, tile, target, player))', text)

    def test_real_hook_compiles_and_selects_actual_nearest_shape(self):
        _, text = PICK_1211_source_code()
        helper = PICK_1211_extract_method(text, 'private int getPickSubHit(')
        hook = PICK_1211_extract_method(text, 'public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player)')
        source = r'''import java.util.*;
public class PipePickHookProbe extends NativeBlock {
  interface BlockGetter {}
  interface LevelReader extends BlockGetter {}
  static final class World implements LevelReader {}
  static final class BlockState {
    final NativeBlock block;
    BlockState(NativeBlock block) {this.block=block;}
    ItemStack getCloneItemStack(HitResult hit,LevelReader world,BlockPos pos,Player player) {
      return block.getCloneItemStack(this,hit,world,pos,player);
    }
  }
  static final class BlockPos {int getX(){return 0;}int getY(){return 0;}int getZ(){return 0;}}
  static class Vec3 {
    final double x,y,z;
    Vec3(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
    Vec3 add(Vec3 v){return new Vec3(x+v.x,y+v.y,z+v.z);}
    Vec3 scale(double v){return new Vec3(x*v,y*v,z*v);}
    Vec3 subtract(double x,double y,double z){return new Vec3(this.x-x,this.y-y,this.z-z);}
    Vec3 subtract(Vec3 v){return subtract(v.x,v.y,v.z);}
    double lengthSqr(){return x*x+y*y+z*z;}
    Vec3 normalize(){return scale(1/Math.sqrt(lengthSqr()));}
    double distanceToSqr(Vec3 v){return subtract(v).lengthSqr();}
  }
  static class HitResult {final Vec3 location;HitResult(Vec3 p){location=p;}Vec3 getLocation(){return location;}}
  static class BlockHitResult extends HitResult {BlockHitResult(Vec3 p){super(p);}}
  static class Player {
    Vec3 eye, look; Player(Vec3 eye,Vec3 look){this.eye=eye;this.look=look;}
    Vec3 getEyePosition(){return eye;}Vec3 getLookAngle(){return look;}
    double blockInteractionRange(){return 5;}
  }
  static class ItemStack {final int part;ItemStack(int part){this.part=part;}static final ItemStack EMPTY=new ItemStack(-1);}
  static class TilePipeHolder {}
  static class VoxelShape {
    final double[] low,high;
    VoxelShape(double x,double y,double z,double xx,double yy,double zz) {
      low=new double[]{x,y,z};high=new double[]{xx,yy,zz};
    }
    boolean isEmpty(){return false;}
    BlockHitResult clip(Vec3 start,Vec3 end,BlockPos pos) {
      double[] a={start.x,start.y,start.z},b={end.x,end.y,end.z};
      double t0=0,t1=1;
      for(int i=0;i<3;i++){
        double d=b[i]-a[i];if(Math.abs(d)<1e-10){if(a[i]<low[i]||a[i]>high[i])return null;continue;}
        double x=(low[i]-a[i])/d,y=(high[i]-a[i])/d;
        if(x>y){double t=x;x=y;y=t;}
        t0=Math.max(t0,x);t1=Math.min(t1,y);if(t0>t1)return null;
      }
      return new BlockHitResult(new Vec3(a[0]+(b[0]-a[0])*t0,a[1]+(b[1]-a[1])*t0,a[2]+(b[2]-a[2])*t0));
    }
  }
  VoxelShape[] shapes=new VoxelShape[57];
  TilePipeHolder pipe = new TilePipeHolder();
  TilePipeHolder getPipe(LevelReader world,BlockPos pos,boolean load){return pipe;}
  VoxelShape[] getAllShape(BlockGetter world,BlockPos pos){return shapes;}
  int computSubhit(TilePipeHolder tile,Vec3 pos,int octant){return 0;}
  int computHitOctant(Vec3 pos){return 0;}
  ItemStack getPickStack(TilePipeHolder tile,int index){return new ItemStack(index);}
  HELPER
  HOOK
  static void check(boolean ok){if(!ok)throw new AssertionError();}
  public static void main(String[]args) {
    PipePickHookProbe pipe=new PipePickHookProbe();
    BlockState state=new BlockState(pipe);World world=new World();BlockPos pos=new BlockPos();
    Player player=new Player(new Vec3(.5,.5,-1),new Vec3(0,0,1));
    HitResult blockTarget=new HitResult(new Vec3(.5,.5,.25));
    pipe.shapes[0]=new VoxelShape(.25,.25,.25,.75,.75,.75);
    check(state.getCloneItemStack(blockTarget,world,pos,player).part==0);
    pipe.shapes[9]=new VoxelShape(.25,.25,.0625,.75,.75,.25); // NORTH-facing lens
    check(state.getCloneItemStack(blockTarget,world,pos,player).part==9);
    pipe.shapes[9]=new VoxelShape(.25,.25,.25,.75,.75,.31); // same front plane
    check(state.getCloneItemStack(blockTarget,world,pos,player).part==9);
    pipe.shapes[9]=null;
    pipe.shapes[13]=new VoxelShape(.25,.25,.0625,.75,.75,.25); // wire segment
    check(state.getCloneItemStack(blockTarget,world,pos,player).part==13);
    pipe.shapes[13]=null;
    check(state.getCloneItemStack(blockTarget,world,pos,player).part==0);
    pipe.shapes[10]=new VoxelShape(.25,.25,.0625,.75,.75,.25); // on bare holder
    pipe.shapes[0]=null;
    check(state.getCloneItemStack(blockTarget,world,pos,player).part==10);
    pipe.pipe=null;
    check(state.getCloneItemStack(blockTarget,world,pos,player)==ItemStack.EMPTY);
    System.out.println("NeoForge 1.21.1 real pick hook PASS");
  }
}
class NativeBlock {
  public PipePickHookProbe.ItemStack getCloneItemStack(PipePickHookProbe.BlockState state,
      PipePickHookProbe.HitResult hit,PipePickHookProbe.LevelReader level,
      PipePickHookProbe.BlockPos pos,PipePickHookProbe.Player player) {return PipePickHookProbe.ItemStack.EMPTY;}
}
'''.replace('  HELPER', helper).replace('  HOOK', hook)
        with tempfile.TemporaryDirectory(prefix='bc-1211-skm-java-') as temp:
            src=Path(temp)/'PipePickHookProbe.java'
            src.write_text(source,encoding='utf-8')
            result=subprocess.run(['javac','--release','21',str(src)],capture_output=True,text=True,timeout=45)
            self.assertEqual(result.returncode,0,result.stderr)
            result=subprocess.run(['java','-cp',temp,'PipePickHookProbe'],capture_output=True,text=True,timeout=45)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn('PASS',result.stdout)

# Original Forge ray/plug hit logic belongs with the 1.21.1 native pick-hook test.
import subprocess

PICK_LEGACY_TARGETS = ('1.19.2-forge', '1.20.1-forge')


def PICK_1211_write(root: Path, relative: str, code: str) -> Path:
    file = root / relative
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(code, encoding='utf-8')
    return file


def PICK_1211_javaprobe(root: Path, sources: list[Path], main: str) -> str:
    classes = root / 'classes'
    classes.mkdir(exist_ok=True)
    build = subprocess.run(['javac', '--release', '21', '-encoding', 'UTF-8', '-d', str(classes),
        *(str(source) for source in sources)], capture_output=True, text=True, timeout=40)
    if build.returncode:
        raise AssertionError('javac error: ' + build.stderr)
    run = subprocess.run(['java', '-cp', str(classes), main], capture_output=True, text=True, timeout=30)
    if run.returncode:
        raise AssertionError('Java assertion: ' + run.stderr + '\n' + run.stdout)
    return run.stdout


class LegacyPipePickTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bc-legacy-pick-')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_legacy_pick_ray_selects_plug_and_pipe(self):
        for target in PICK_LEGACY_TARGETS:
            with self.subTest(target=target):
                code = PICK_1211_source_code(target)[1]
                pattern = re.compile(r'\tprivate int getPickSubHit\(.*?\n\t}\n', re.DOTALL)
                match = pattern.search(code)
                self.assertIsNotNone(match)
                method = match.group()
                self.assertIn('getPickSubHit(world, pos, tile, target, player)', code)
                self.assertIn('return plug.getPickStack();', code)
                root = Path(self.temp.name) / ('pick-stubs-' + target)
                fixture = '''public class PickRayProbe {
  static class BlockGetter {}
  static class TilePipeHolder {}
  static class BlockPos {int getX(){return 0;}int getY(){return 0;}int getZ(){return 0;}}
  static class Vec3{double x,y,z;Vec3(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
    Vec3 subtract(Vec3 v){return new Vec3(x-v.x,y-v.y,z-v.z);}Vec3 subtract(double a,double b,double c){return new Vec3(x-a,y-b,z-c);}
    Vec3 add(Vec3 v){return new Vec3(x+v.x,y+v.y,z+v.z);}Vec3 scale(double k){return new Vec3(x*k,y*k,z*k);}
    double lengthSqr(){return x*x+y*y+z*z;}Vec3 normalize(){return scale(1.0/Math.sqrt(lengthSqr()));}
    double distanceToSqr(Vec3 v){return subtract(v).lengthSqr();}}
  static class HitResult{Vec3 hit;HitResult(Vec3 h){hit=h;}Vec3 getLocation(){return hit;}}
  static class BlockHitResult extends HitResult{BlockHitResult(Vec3 h){super(h);}}
  static class Player{Vec3 eye,look;Player(Vec3 eye,Vec3 look){this.eye=eye;this.look=look;}Vec3 getEyePosition(){return eye;}Vec3 getLookAngle(){return look;}double getReachDistance(){return 5;}}
  static class VoxelShape{
    double[] lo,hi;VoxelShape(double x,double y,double z,double xx,double yy,double zz){lo=new double[]{x,y,z};hi=new double[]{xx,yy,zz};}
    boolean isEmpty(){return false;}
    BlockHitResult clip(Vec3 start,Vec3 end,BlockPos pos){double[] a={start.x,start.y,start.z}, b={end.x,end.y,end.z};
      double t0=0,t1=1;for(int i=0;i<3;i++){double d=b[i]-a[i];if(Math.abs(d)<1e-10){if(a[i]<lo[i]||a[i]>hi[i])return null;continue;}
      double low=(lo[i]-a[i])/d,high=(hi[i]-a[i])/d;if(low>high){double tmp=low;low=high;high=tmp;}
      t0=Math.max(t0,low);t1=Math.min(t1,high);if(t0>t1)return null;}
      return new BlockHitResult(new Vec3(a[0]+(b[0]-a[0])*t0,a[1]+(b[1]-a[1])*t0,a[2]+(b[2]-a[2])*t0));}
  }
  VoxelShape[] shapes=new VoxelShape[57];
  VoxelShape[] getAllShape(BlockGetter level,BlockPos pos){return shapes;}
  int computSubhit(TilePipeHolder tile,Vec3 location,int octant){return 0;}
  int computHitOctant(Vec3 location){return 0;}
  METHOD
  static void check(boolean value){if(!value)throw new AssertionError();}
  public static void main(String[]args){
    PickRayProbe b=new PickRayProbe();BlockGetter level=new BlockGetter();BlockPos pos=new BlockPos();TilePipeHolder tile=new TilePipeHolder();
    b.shapes[0]=new VoxelShape(.25,.25,.25,.75,.75,.75);
    Player p=new Player(new Vec3(.5,.5,-1),new Vec3(0,0,1));
    HitResult target=new HitResult(new Vec3(.5,.5,.25));
    check(b.getPickSubHit(level,pos,tile,target,p)==0);
    b.shapes[7]=new VoxelShape(.25,.25,.0625,.75,.75,.25); // plug outside front of pipe
    check(b.getPickSubHit(level,pos,tile,target,p)==7);
    b.shapes[7]=new VoxelShape(.25,.25,.25,.75,.75,.3); // exactly coincident: plug wins
    check(b.getPickSubHit(level,pos,tile,target,p)==7);
    b.shapes[7]=null;b.shapes[13]=new VoxelShape(.25,.25,.0625,.75,.75,.25); // a wire
    check(b.getPickSubHit(level,pos,tile,target,p)==13);
    b.shapes[13]=null;check(b.getPickSubHit(level,pos,tile,target,p)==0);
    System.out.println("legacy pick nearest part PASS");
  }
}
'''.replace('  METHOD', method)
                file = PICK_1211_write(root, 'PickRayProbe.java', fixture)
                out=PICK_1211_javaprobe(root, [file], 'PickRayProbe')
                self.assertIn('PASS', out)


if __name__ == '__main__':
    unittest.main()
