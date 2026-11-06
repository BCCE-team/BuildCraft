#!/usr/bin/env python3
"""26.x NeoForge blueprint PiP: native blocks, block entities and attachments.

These are source-contract and javac parse tests, not an in-game screenshot or
Gradle dependency-resolution build. They assert version selection for all 26.x.
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

PIP = 'src/main/java/buildcraft/builders/client/render/pip/BlueprintPipRenderer.java'
SCENE = 'src/main/java/buildcraft/builders/client/render/pip/BlueprintPreviewScene.java'
TARGETS = ('26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')


class Builders26xNativePreviewSceneTests(unittest.TestCase):
    def test_native_scene_overlay_selected_only_for_neoforge_26x(self):
        props = load_properties()
        for target in TARGETS:
            layout = target_layout(target, props)
            with self.subTest(target=target):
                for relative in (PIP, SCENE):
                    src = resolve_effective_source(layout, props, relative)
                    self.assertIsNotNone(src)
                    self.assertIn('source-family-platforms/26.X/neoforge/', src.as_posix())
        for target in ('1.21.1-neoforge', '1.21.11-neoforge'):
            with self.subTest(target=target):
                # The separate 1.21.11 scene may exist in the user's already
                # patched tree; it must not resolve to this new 26.X overlay.
                src = resolve_effective_source(target_layout(target, props), props, SCENE)
                if src is not None:
                    self.assertNotIn('source-family-platforms/26.X/neoforge/', src.as_posix())

    def test_prior_captured_blockstate_fix_remains_enabled_in_26x(self):
        # The newest PiP is intentionally layered on top of the earlier
        # SchematicBlockDefault fix, which preserves lever/bed/sign orientation.
        logical = 'src/main/java/buildcraft/builders/snapshot/SchematicBlockDefault.java'
        for target in TARGETS:
            with self.subTest(target=target):
                source = read_materialized_java(target, logical)
                self.assertIn('public BlockState getBlockStateForRender()', source)
                self.assertIn('return blockState;', source)

    def test_render_state_uses_real_block_entities_and_decorations(self):
        for target in TARGETS:
            with self.subTest(target=target):
                scene = read_materialized_java(target, SCENE)
                pip = read_materialized_java(target, PIP)
                for token in (
                    'extends FakeWorld',
                    'world.setBlock(', 'world.setBlockEntity(blockEntity)',
                    'for (Direction facing : Direction.values())',
                    'BlueprintSecurity.forBlockEntity(state, tag)',
                    'blockEntity.loadWithComponents(TagValueInput.create(',
                    'renderer.extractRenderState(tile, state, 0.0F,',
                    'dispatcher.submit(state, pose, sink, camera)',
                    'minecraft:item_frame', 'minecraft:glow_item_frame',
                    'minecraft:painting', 'minecraft:armor_stand',
                    'renderer.extractEntity(entity, 0.0F)',
                    'renderer.submit(state, camera, local.x, local.y, local.z, pose, sink)',
                ):
                    self.assertIn(token, scene, (target, token))
                self.assertIn('EntityType.create(TagValueInput.create(' if target == TARGETS[0] else 'EntityType.create(type,', scene)
                self.assertIn('BlueprintPreviewScene.forSnapshot(', pip)
                self.assertIn('scene.submitBlockEntity(at, poseStack,', pip)
                self.assertIn('scene.submitEntities(poseStack,', pip)
                # It must not build an actual blueprint or mutate the client world.
                for bad in ('buildWithoutChecks(', 'world.superLevel.setBlock(',
                            'world.addFreshEntity(', 'minecraft.level.setBlock('):
                    self.assertNotIn(bad, scene)

    def test_state_model_and_blockentity_are_not_mutually_exclusive(self):
        for target in TARGETS:
            with self.subTest(target=target):
                pip = read_materialized_java(target, PIP)
                before_model = pip.index('boolean submittedBlockEntity =')
                model = pip.index('boolean drawnBlockModel = false;', before_model)
                skip = pip.index('if (submittedBlockEntity || drawnBlockModel) continue;', model)
                self.assertLess(before_model, model)
                self.assertLess(model, skip)
                self.assertIn('renderedState.getRenderShape() == RenderShape.MODEL', pip)
                self.assertIn('&& !engine', pip)
                self.assertIn('&& renderedState.hasBlockEntity()', pip)

    def test_2612_uses_native_storage_and_later_versions_collector(self):
        original = read_materialized_java('26.1.2-neoforge', PIP)
        self.assertIn('scene.submitBlockEntity(at, poseStack, storage, previewCamera)', original)
        self.assertIn('scene.submitEntities(poseStack, storage, previewCamera)', original)
        self.assertIn('dispatcher.renderAllFeatures();', original)
        for target in TARGETS[1:]:
            with self.subTest(target=target):
                code = read_materialized_java(target, PIP)
                self.assertIn('scene.submitBlockEntity(at, poseStack, collector, previewCamera)', code)
                self.assertIn('scene.submitEntities(poseStack, collector, previewCamera)', code)
                self.assertNotIn('dispatcher.renderAllFeatures();', code)

    def test_26x_world_api_abstract_methods_implemented(self):
        scene = read_materialized_java('26.3-neoforge', SCENE)
        for signature in (
            'void explode(Entity source, DamageSource damageSource,',
            'void playSeededSound(Entity source, double x, double y, double z,',
            'void playSeededSound(Entity source, Entity soundEntity,',
            'void levelEvent(Entity source, int event, BlockPos pos, int data)',
            'int getSeaLevel()', 'WorldBorder getWorldBorder()',
            'ClockManager clockManager()', 'EnvironmentAttributeSystem environmentAttributes()',
            'Collection<? extends PartEntity<?>> dragonParts()',
            'void setRespawnData(LevelData.RespawnData data)',
            'LevelData.RespawnData getRespawnData()',
        ):
            self.assertIn(signature, scene)

    def test_engine_fallback_uses_saved_variant_not_block_as_item(self):
        for target in TARGETS:
            with self.subTest(target=target):
                pip = read_materialized_java(target, PIP)
                self.assertIn('BCCoreItems.ENGINE_ITEM_MAP.get(state.getValue(engine.getEngineProperty()))', pip)
                self.assertIn('instanceof BlockEngine_BC8 engine', pip)
                self.assertIn('instanceof BlockEngineBase_BC8', pip)

    def test_syntax_of_effective_scene_and_pip_all_targets(self):
        for target in TARGETS:
            with self.subTest(target=target), tempfile.TemporaryDirectory(prefix='preview26x-') as tmp:
                for i, path in enumerate((PIP, SCENE)):
                    text = read_materialized_java(target, path)
                    dest = Path(tmp) / ('Scene%d.java' % i)
                    dest.write_text(text, encoding='utf-8')
                result = parse_sources([Path(tmp)])
                self.assertIn('2 units, 0 errors', result, result)


if __name__ == '__main__':
    unittest.main()
