#!/usr/bin/env python3
"""Source-level visual parity guards for BCCE Builders 1.21.11 NeoForge.

These inspect the *effective* Stonecutter sources. Javac's syntax parser validates
syntax only; this is not Minecraft client execution or a Gradle compilation.
"""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source
from source_lookup import read_materialized_java
from minecraft_compat_fixture import parse_sources

SRC = 'src/main/java/buildcraft/builders/'
PIP = SRC + 'client/render/pip/BlueprintPipRenderer.java'
SCENE = SRC + 'client/render/pip/BlueprintPreviewScene.java'
FLYING = SRC + 'client/render/RenderSnapshotBuilder.java'


def effective(target: str, logical: str) -> str:
    return read_materialized_java(target, logical)


class Builders12111VisualParityTests(unittest.TestCase):
    def test_native_3d_scene_is_selected_only_for_12111(self):
        props = load_properties()
        for target in ('1.21.1-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge'):
            with self.subTest(target=target):
                self.assertIsNone(resolve_effective_source(target_layout(target, props), props, SCENE))
        path = resolve_effective_source(target_layout('1.21.11-neoforge', props), props, SCENE)
        self.assertIsNotNone(path)
        self.assertIn('source-family-platforms/1.21.X/neoforge/', str(path))

    def test_client_renderer_overlay_cannot_replace_other_targets(self):
        props = load_properties()
        for target in ('26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge'):
            with self.subTest(target=target):
                for rel in (PIP, FLYING):
                    selected = resolve_effective_source(target_layout(target, props), props, rel)
                    self.assertNotIn('source-family-platforms/1.21.X/neoforge/', str(selected))
        # The functional 1.21.1 preview remains the downport, not the new overlay.
        self.assertIn('source-downports/', str(resolve_effective_source(
            target_layout('1.21.1-neoforge', props), props, FLYING)))

    def test_pip_preserves_pipes_fluids_and_templates(self):
        code = effective('1.21.11-neoforge', PIP)
        for required in ('PreviewPipeTopology', 'PreviewPipeModel.reconstruct(',
                         'PipeWireRenderer.renderPreviewWires(', 'PreviewFluidRenderer.render(',
                         'PreviewBlockModelRenderer.renderBlock(', 'TemplateGhostGeometry.visibleFaces(',
                         'previewStack(', 'ClientArchitectPreview.RenderBlock'):
            with self.subTest(required=required):
                self.assertIn(required, code)

    def test_pip_submits_entity_and_blockentity_states_to_offscreen_queue(self):
        code = effective('1.21.11-neoforge', PIP)
        self.assertIn('BlueprintPreviewScene.forSnapshot(', code)
        self.assertIn('scene.submitBlockEntity(at, poseStack, storage, previewCamera)', code)
        self.assertIn('scene.submitEntities(poseStack, storage, previewCamera)', code)
        self.assertIn('previewCamera.initialized = true;', code)
        self.assertIn('previewCamera.entityPos = net.minecraft.world.phys.Vec3.ZERO;', code)
        self.assertIn('RenderShape.MODEL', code)
        self.assertIn('if (submittedBlockEntity || drawnBlockModel) continue;', code)
        # Flush only AFTER both native render states and normal geometry are queued.
        self.assertLess(code.index('scene.submitEntities('), code.index('dispatcher.renderAllFeatures();'))

    def test_scene_uses_local_world_and_captured_neighbor_states(self):
        code = effective('1.21.11-neoforge', SCENE)
        for required in ('extends FakeWorld', 'FakeWorld.BLUEPRINT_OFFSET',
                         'for (Direction side : Direction.values())',
                         'world.setBlock(', 'world.setBlockEntity(entity)',
                         'block.newBlockEntity(', 'BlueprintSecurity.forBlockEntity(',
                         'TagValueInput.create(', 'extractPreviewState(renderer, be)',
                         'dispatcher.submit(state, pose, storage, camera)',
                         'state.lightCoords = 15728880;'):
            with self.subTest(required=required):
                self.assertIn(required, code)
        self.assertLess(code.index('world.setBlock('), code.index('entity.loadWithComponents('))

    def test_scene_captures_1_21_1_decoration_types_with_1_21_11_api(self):
        code = effective('1.21.11-neoforge', SCENE)
        for required in ('minecraft:item_frame', 'minecraft:glow_item_frame',
                         'minecraft:armor_stand', 'minecraft:painting',
                         'BlueprintSecurity.stripUnsafeEntityState(tag)',
                         'BuildersNbtUtil.readBlockPos(', 'EntityType.create(TagValueInput.create(',
                         'dispatcher.extractEntity(entity, 0)',
                         'dispatcher.submit(state, camera, relative.x, relative.y, relative.z, pose, storage)'):
            with self.subTest(required=required):
                self.assertIn(required, code)
        self.assertNotIn('world.addFreshEntity(', code)
        self.assertNotIn('schematic.buildWithoutChecks(', code)

    def test_preview_world_implements_12111_explosion_hook_without_side_effects(self):
        # This overload is abstract in Minecraft 1.21.11 Level (void return,
        # new WeightedList<ExplosionParticleInfo> argument). It is deliberately
        # NO-OP: rendering a stored blueprint must never trigger real explosions.
        code = effective('1.21.11-neoforge', SCENE)
        for imp in ('net.minecraft.core.particles.ExplosionParticleInfo',
                    'net.minecraft.core.particles.ParticleOptions',
                    'net.minecraft.util.random.WeightedList',
                    'net.minecraft.world.level.ExplosionDamageCalculator',
                    'net.minecraft.world.damagesource.DamageSource',
                    'net.minecraft.core.Holder', 'net.minecraft.sounds.SoundEvent'):
            self.assertIn('import ' + imp + ';', code)
        signature = 'public void explode(Entity source, DamageSource damageSource,'
        self.assertIn(signature, code)
        body = code.split(signature, 1)[1].split('}', 1)[0]
        self.assertIn('WeightedList<ExplosionParticleInfo> blockParticles,', body)
        self.assertIn('Holder<SoundEvent> explosionSound)', body)
        self.assertNotIn('superLevel.explode(', body)
        self.assertNotIn('super.explode(', body)
        self.assertNotIn('new Explosion(', body)

    def test_preview_world_implements_both_12111_seeded_sound_overloads(self):
        # Since 1.21.11, both abstract Level.playSeededSound overloads
        # accept Entity instead of Player as their first parameter.
        code = effective('1.21.11-neoforge', SCENE)
        self.assertIn('import net.minecraft.sounds.SoundSource;', code)
        positional = 'public void playSeededSound(Entity source, double x, double y, double z,'
        entity = 'public void playSeededSound(Entity source, Entity soundEntity,'
        self.assertEqual(code.count('public void playSeededSound('), 2)
        for signature in (positional, entity):
            with self.subTest(signature=signature):
                self.assertIn(signature, code)
                method_body = code.split(signature, 1)[1].split('}', 1)[0]
                self.assertIn('Holder<SoundEvent> sound, SoundSource category,', method_body)
                self.assertIn('float volume, float pitch, long seed)', method_body)
                self.assertNotIn('super.playSeededSound(', method_body)
                self.assertNotIn('superLevel.playSeededSound(', method_body)
                self.assertNotIn('Minecraft.getInstance().getSoundManager()', method_body)
        # Keep the 1.21.1 shared parent unchanged: the new overloads must
        # exist only in the 1.21.11 overlay, not affect other targets.
        shared = (ROOT / 'source-families/1.21.X/src/main/java/'
                  'buildcraft/builders/snapshot/FakeWorld.java').read_text(encoding='utf-8')
        self.assertNotIn('playSeededSound(Entity source, Entity soundEntity', shared)

    def test_preview_world_overrides_12111_level_event_without_side_effects(self):
        # 1.21.11 LevelAccessor.levelEvent(Entity,int,BlockPos,int) is abstract.
        # FakeWorld's legacy Player overload is not an implementation.
        code = effective('1.21.11-neoforge', SCENE)
        signature = 'public void levelEvent(Entity source, int eventId, BlockPos pos, int data)'
        self.assertIn(signature, code)
        self.assertIn('import net.minecraft.world.entity.Entity;', code)
        self.assertIn('import net.minecraft.core.BlockPos;', code)
        method = code.split(signature, 1)[1].split('}', 1)[0]
        self.assertNotIn('super.levelEvent(', method)
        self.assertNotIn('superLevel.levelEvent(', method)
        self.assertNotIn('Minecraft.getInstance()', method)
        shared = (ROOT / 'source-families/1.21.X/src/main/java/'
                  'buildcraft/builders/snapshot/FakeWorld.java').read_text(encoding='utf-8')
        self.assertIn('levelEvent(@Nullable Player player,', shared)
        self.assertNotIn('levelEvent(Entity source,', shared)

    def test_preview_world_overrides_12111_sea_level(self):
        # LevelReader.getSeaLevel() is abstract in 1.21.11, but not provided
        # by the 1.21.X shared FakeWorld implementation.
        code = effective('1.21.11-neoforge', SCENE)
        shared = (ROOT / 'source-families/1.21.X/src/main/java/'
                  'buildcraft/builders/snapshot/FakeWorld.java').read_text(encoding='utf-8')
        self.assertNotIn('int getSeaLevel(', shared)
        signature = 'public int getSeaLevel()'
        self.assertEqual(code.count(signature), 1)
        method_body = code.split(signature, 1)[1].split('}', 1)[0]
        self.assertIn('return superLevel.getSeaLevel();', method_body)
        self.assertNotIn('return 63;', method_body)
        self.assertNotIn('super.getSeaLevel()', method_body)

    def test_preview_world_overrides_12111_world_border(self):
        # CollisionGetter requires this on 1.21.11. The parent FakeWorld
        # does not provide it; native BERs can query collision boundaries.
        code = effective('1.21.11-neoforge', SCENE)
        shared = (ROOT / 'source-families/1.21.X/src/main/java/'
                  'buildcraft/builders/snapshot/FakeWorld.java').read_text(encoding='utf-8')
        self.assertIn('import net.minecraft.world.level.border.WorldBorder;', code)
        self.assertNotIn('getWorldBorder()', shared)
        signature = 'public WorldBorder getWorldBorder()'
        self.assertEqual(code.count(signature), 1)
        method_body = code.split(signature, 1)[1].split('}', 1)[0]
        self.assertIn('return superLevel.getWorldBorder();', method_body)
        self.assertNotIn('new WorldBorder(', method_body)
        self.assertNotIn('super.getWorldBorder()', method_body)

    def test_all_known_12111_level_hooks_are_present(self):
        # Regression guard: prevents another single-method-at-a-time compile
        # failure when the preview's abstract Level superclass changes.
        code = effective('1.21.11-neoforge', SCENE)
        shared = (ROOT / 'source-families/1.21.X/src/main/java/'
                  'buildcraft/builders/snapshot/FakeWorld.java').read_text(encoding='utf-8')
        for signature in (
            'void explode(Entity source, DamageSource damageSource,',
            'void playSeededSound(Entity source, double x, double y, double z,',
            'void playSeededSound(Entity source, Entity soundEntity,',
            'void levelEvent(Entity source, int eventId, BlockPos pos, int data)',
            'int getSeaLevel()',
            'WorldBorder getWorldBorder()',
            'void setRespawnData(LevelData.RespawnData data)',
            'LevelData.RespawnData getRespawnData()',
            'Collection<EnderDragonPart> dragonParts()',
            'EnvironmentAttributeSystem environmentAttributes()',
        ):
            with self.subTest(signature=signature):
                self.assertIn(signature, code)
        for method in (
            'sendBlockUpdated(', 'gatherChunkSourceStats(', 'getEntity(',
            'getMapData(', 'destroyBlockProgress(', 'getScoreboard(',
            'recipeAccess(', 'tickRateManager(', 'getEntities(',
            'potionBrewing(', 'fuelValues(', 'getDayTimeFraction(',
            'getDayTimePerTick(', 'getChunkSource(', 'getBlockTicks(',
            'getFluidTicks(', 'gameEvent(',
        ):
            with self.subTest(method=method):
                self.assertIn(method, shared)

    def test_filler_draws_world_models_but_builder_does_not_change(self):
        code = effective('1.21.11-neoforge', FLYING)
        self.assertIn('if (filler && item.getItem() instanceof BlockItem blockItem', code)
        self.assertIn('collector.submitBlock(matrix, blockItem.getBlock().defaultBlockState()', code)
        self.assertIn('matrix.translate(-0.5F, -0.5F, -0.5F)', code)
        self.assertIn('matrix.scale(.30F, .30F, .30F)', code)
        self.assertIn('LaserRenderer_BC8.computeLightmap(pos.x, pos.y, pos.z, 0)', code)
        self.assertIn('if (filler) matrix.scale', code)
        self.assertIn('ItemStackRenderState itemState', code)
        self.assertIn('ItemDisplayContext.GROUND', code)
        self.assertIn('findFirst().orElse(current)', code)
        self.assertNotIn('collector.submitBlock(', effective('26.2-neoforge', FLYING))

    def test_existing_world_projection_and_scan_are_preserved(self):
        target = '1.21.11-neoforge'
        filler = effective(target, SRC + 'client/render/RenderFiller.java')
        architect = effective(target, SRC + 'client/render/RenderArchitectTable.java')
        self.assertIn('FillerGhostPreview.render(', filler)
        self.assertIn('FillerWorkRenderer.render(', filler)
        self.assertIn('RenderSnapshotBuilder.submitFiller(', filler)
        self.assertTrue('ClientArchitectPreview' in architect or 'BuildCraftLaserManager' in architect)

    def test_java_sources_are_syntactically_valid_after_materialization(self):
        with tempfile.TemporaryDirectory(prefix='builders12111-parity-') as tmp:
            files=[]
            for logical in (PIP, SCENE, FLYING):
                file = Path(tmp) / Path(logical).name
                file.write_text(effective('1.21.11-neoforge', logical), encoding='utf-8')
                files.append(file)
            self.assertIn('3 units, 0 errors', parse_sources([Path(tmp)]))


if __name__ == '__main__':
    unittest.main()
